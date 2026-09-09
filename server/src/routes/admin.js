const express = require("express");
const bcrypt = require("bcryptjs");
const { getDB, getSettings, setSettings } = require("../db");
const { authMiddleware, requireAdmin } = require("../middleware/auth");
const { getAllChannels, getOrCreateChannel, deleteChannel, toggleChannel, updateChannel } = require("../moviepilot");

const router = express.Router();

// 所有管理接口都需要管理员权限
router.use(authMiddleware);
router.use(requireAdmin);

// 概览统计
router.get("/stats", (req, res) => {
  const db = getDB();
  const count = (sql) => db.prepare(sql).get().c;
  res.json({
    users: count("SELECT COUNT(*) c FROM users"),
    admins: count("SELECT COUNT(*) c FROM users WHERE role='admin'"),
    topics: count("SELECT COUNT(*) c FROM topics"),
    messages: count("SELECT COUNT(*) c FROM topic_messages"),
    notifications: count("SELECT COUNT(*) c FROM notifications"),
    devices: count("SELECT COUNT(*) c FROM devices"),
  });
});

// 服务器设置（文件/图片/语音大小上限等）
router.get("/settings", (req, res) => {
  res.json({ settings: getSettings() });
});

router.put("/settings", (req, res) => {
  const patch = req.body && req.body.settings ? req.body.settings : req.body;
  try {
    const updated = setSettings(patch);
    res.json({ message: "Settings updated", settings: updated });
  } catch (e) {
    res.status(400).json({ error: "更新失败: " + e.message });
  }
});

// 用户列表（含各用户的数据量统计）
router.get("/users", (req, res) => {
  const db = getDB();
  const users = db
    .prepare(
      `SELECT u.id, u.username, u.role, u.created_at,
              (SELECT COUNT(*) FROM notifications n WHERE n.user_id = u.id) as notification_count,
              (SELECT COUNT(*) FROM topic_members tm WHERE tm.user_id = u.id) as topic_count,
              (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id) as device_count
       FROM users u ORDER BY u.created_at DESC`
    )
    .all();
  res.json({ users });
});

// 新建用户（可指定角色）
router.post("/users", (req, res) => {
  const { username, password, role } = req.body;
  if (!username || !password) return res.status(400).json({ error: "username and password required" });
  if (username.length < 3 || username.length > 32) return res.status(400).json({ error: "Username must be 3-32 chars" });
  if (password.length < 6) return res.status(400).json({ error: "Password must be at least 6 chars" });
  const r = role === "admin" ? "admin" : "user";
  const db = getDB();
  if (db.prepare("SELECT id FROM users WHERE username = ?").get(username)) {
    return res.status(409).json({ error: "Username already exists" });
  }
  const hash = bcrypt.hashSync(password, 10);
  const info = db.prepare("INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)").run(username, hash, r);
  res.status(201).json({ message: "User created", user: { id: info.lastInsertRowid, username, role: r } });
});

// 删除用户（不能删自己）。
// 开启 foreign_keys 后数据库会级联清理其 devices/topic_members/owned topics 等；
// 这里显式清理设备会话与该用户作为 owner 的群聊消息，避免 topic_messages 等无主残留。
router.delete("/users/:id", (req, res) => {
  const db = getDB();
  const id = parseInt(req.params.id);
  if (id === req.userId) return res.status(400).json({ error: "Cannot delete yourself" });
  const user = db.prepare("SELECT id, username FROM users WHERE id = ?").get(id);
  if (!user) return res.status(404).json({ error: "User not found" });

  // 联动删除留言板对应用户（异步，不阻塞删除）
  try {
    const { getSettings } = require("../db");
    const settings = getSettings();
    const mwSyncUrl = settings.messagewall_sync_url || '';
    if (mwSyncUrl && user.username) {
      fetch(mwSyncUrl.replace(/\/$/, '') + '/api/delete-user', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: user.username })
      }).catch(e => console.error("[admin] delete messagewall user failed:", e.message));
    }
  } catch (e) { console.error("[admin] delete messagewall user error:", e.message); }

  // 1. 清理默认设备会话 u{id}-devices
  const deviceTopic = `u${id}-devices`;
  db.prepare(
    "DELETE FROM topic_message_deletes WHERE message_id IN (SELECT id FROM topic_messages WHERE topic = ?)"
  ).run(deviceTopic);
  db.prepare("DELETE FROM topic_messages WHERE topic = ?").run(deviceTopic);
  db.prepare("DELETE FROM topic_members WHERE topic_id IN (SELECT id FROM topics WHERE name = ?)").run(deviceTopic);
  db.prepare("DELETE FROM topics WHERE name = ?").run(deviceTopic);

  // 2. 清理该用户作为 owner 的普通群聊（否则 topic_messages 会因 topic 被级联删除而残留）
  const owned = db.prepare("SELECT name FROM topics WHERE owner_id = ?").all(id);
  for (const t of owned) {
    db.prepare(
      "DELETE FROM topic_message_deletes WHERE message_id IN (SELECT id FROM topic_messages WHERE topic = ?)"
    ).run(t.name);
    db.prepare("DELETE FROM topic_messages WHERE topic = ?").run(t.name);
    db.prepare("DELETE FROM topic_join_requests WHERE topic_id IN (SELECT id FROM topics WHERE name = ?)").run(t.name);
    db.prepare("DELETE FROM topic_members WHERE topic_id IN (SELECT id FROM topics WHERE name = ?)").run(t.name);
    db.prepare("DELETE FROM topics WHERE name = ?").run(t.name);
  }

  // 3. 删除用户本身（外键级联处理其余关联）
  db.prepare("DELETE FROM users WHERE id = ?").run(id);
  res.json({ message: "User deleted" });
});

// 某用户的设备
router.get("/users/:id/devices", (req, res) => {
  const db = getDB();
  const devices = db.prepare("SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen DESC").all(parseInt(req.params.id));
  res.json({ devices });
});

// 所有话题（含拥有者）
router.get("/topics", (req, res) => {
  const db = getDB();
  const topics = db
    .prepare(
      `SELECT t.id, t.name, t.title, t.description, t.owner_id, u.username as owner_name, t.created_at,
              (SELECT COUNT(*) FROM topic_members tm WHERE tm.topic_id = t.id) as member_count,
              (SELECT COUNT(*) FROM topic_messages tm2 WHERE tm2.topic = t.name) as message_count
       FROM topics t LEFT JOIN users u ON t.owner_id = u.id
       ORDER BY t.created_at DESC LIMIT 200`
    )
    .all();
  res.json({ topics });
});

// 话题消息（管理员可见全部）
router.get("/topics/:topic/messages", (req, res) => {
  const db = getDB();
  const name = req.params.topic;
  const limit = Math.min(parseInt(req.query.limit) || 100, 500);
  const messages = db.prepare("SELECT * FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?").all(name, limit).reverse();
  res.json({ topic: name, messages });
});

// 删除某话题（管理员）
router.delete("/topics/:topic", (req, res) => {
  const db = getDB();
  const name = req.params.topic;
  const topic = db.prepare("SELECT id FROM topics WHERE name = ?").get(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  db.prepare("DELETE FROM topic_messages WHERE topic = ?").run(name);
  db.prepare("DELETE FROM topics WHERE id = ?").run(topic.id);
  res.json({ message: "Topic deleted by admin" });
});

// 所有通知（可按 ?userId= 过滤）
router.get("/notifications", (req, res) => {
  const db = getDB();
  const limit = Math.min(parseInt(req.query.limit) || 100, 500);
  const userId = req.query.userId ? parseInt(req.query.userId) : null;
  let rows;
  if (userId) {
    rows = db
      .prepare(`SELECT n.*, u.username, d.device_name FROM notifications n
                LEFT JOIN users u ON n.user_id = u.id LEFT JOIN devices d ON n.device_id = d.id
                WHERE n.user_id = ? ORDER BY n.timestamp DESC LIMIT ?`)
      .all(userId, limit);
  } else {
    rows = db
      .prepare(`SELECT n.*, u.username, d.device_name FROM notifications n
                LEFT JOIN users u ON n.user_id = u.id LEFT JOIN devices d ON n.device_id = d.id
                ORDER BY n.timestamp DESC LIMIT ?`)
      .all(limit);
  }
  res.json({ notifications: rows });
});

// 删除某条通知（管理员）
router.delete("/notifications/:id", (req, res) => {
  const db = getDB();
  const result = db.prepare("DELETE FROM notifications WHERE id = ?").run(parseInt(req.params.id));
  if (result.changes === 0) return res.status(404).json({ error: "Not found" });
  res.json({ message: "Notification deleted" });
});

// 留言板 Webhook 配置（已废弃）：留言板现在按 webhook 地址中的用户名自动路由到对应用户，
// 无需管理员手动配置接收账号。保留接口仅为向后兼容，返回空列表。
// 留言板配置：获取所有用户 + 开启了留言功能的用户列表
router.get("/messagewall", async (req, res) => {
  try {
    const { getMessagewallEnabledUsers } = require("../messagewall");
    const db = require("../db").getDB();
    const users = db.prepare("SELECT id, username, display_name, role FROM users ORDER BY id ASC").all();
    const enabled = getMessagewallEnabledUsers();
    const baseRow = db.prepare("SELECT value FROM settings WHERE key = 'messagewall_webhook_base'").get();
    const webhookBase = baseRow ? baseRow.value : "";
    res.json({ users, enabledUsers: enabled, webhookBase });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 留言板配置：保存开启了留言功能的用户列表
router.put("/messagewall", async (req, res) => {
  try {
    const { setMessagewallEnabledUsers } = require("../messagewall");
    const { enabledUsers, webhookBase } = req.body || {};
    if (enabledUsers !== undefined && !Array.isArray(enabledUsers)) return res.status(400).json({ error: "enabledUsers must be array" });
    if (enabledUsers !== undefined) setMessagewallEnabledUsers(enabledUsers);
    if (webhookBase !== undefined) {
      const val = (webhookBase || "").trim().replace(/\/+$/, "");
      db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('messagewall_webhook_base', ?)").run(val);
    }
    const baseRow = db.prepare("SELECT value FROM settings WHERE key = 'messagewall_webhook_base'").get();
    res.json({ ok: true, enabledUsers: getMessagewallEnabledUsers(), webhookBase: baseRow ? baseRow.value : "" });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

router.put("/messagewall", (req, res) => {
  res.json({ ok: true, targets: [], deprecated: true, note: "留言板已改为按 webhook 地址 /:username 自动路由，无需配置接收账号" });
});


// ========== TOTP 两步验证（注册时需要输入动态码） ==========
const totp = require("../totp");

// 获取当前 TOTP 配置
router.get("/totp", (req, res) => {
  const settings = getSettings();
  const enabled = settings.totp_enabled === true || settings.totp_enabled === "true";
  const secret = settings.totp_secret || "";
  res.json({
    enabled,
    secret,
    otpauth_url: secret ? totp.otpauthUrl(secret, "EchoLink", "admin") : "",
  });
});

// 生成新的 TOTP 密钥（未启用，需验证后启用）
router.post("/totp/generate", (req, res) => {
  const secret = totp.generateSecret();
  res.json({
    secret,
    otpauth_url: totp.otpauthUrl(secret, "EchoLink", "admin"),
  });
});

// 启用 TOTP（需要输入当前6位码验证）
router.post("/totp/enable", (req, res) => {
  const { secret, code } = req.body || {};
  if (!secret || !code) return res.status(400).json({ error: "secret and code required" });
  if (!totp.verifyTOTP(secret, code)) {
    return res.status(400).json({ error: "验证码错误，请检查 OTP 应用时间是否同步" });
  }
  setSettings({ totp_secret: secret, totp_enabled: true });
  res.json({ ok: true, message: "TOTP 两步验证已启用" });
});

// 禁用 TOTP
router.post("/totp/disable", (req, res) => {
  setSettings({ totp_enabled: false });
  res.json({ ok: true, message: "TOTP 两步验证已禁用" });
});

// ===== MoviePilot 通道管理 =====

// 获取所有用户的 MP 通道
router.get("/moviepilot/channels", (req, res) => {
  try {
    const channels = getAllChannels();
    res.json({ channels });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 为用户创建/重置 MP 通道（生成新 token）
router.post("/moviepilot/channels/:userId", (req, res) => {
  const userId = parseInt(req.params.userId);
  if (!userId) return res.status(400).json({ error: "invalid user id" });
  const db = getDB();
  const user = db.prepare("SELECT id, username FROM users WHERE id = ?").get(userId);
  if (!user) return res.status(404).json({ error: "用户不存在" });
  // 先删旧的再创建新的（重置 token）
  deleteChannel(userId);
  const channel = getOrCreateChannel(userId);
  res.json({ ok: true, channel: { ...channel, username: user.username } });
});

// 删除用户的 MP 通道
router.delete("/moviepilot/channels/:userId", (req, res) => {
  const userId = parseInt(req.params.userId);
  if (!userId) return res.status(400).json({ error: "invalid user id" });
  deleteChannel(userId);
  res.json({ ok: true, message: "通道已删除" });
});

// 切换通道启用状态
router.put("/moviepilot/channels/:userId/toggle", (req, res) => {
  const userId = parseInt(req.params.userId);
  const { enabled } = req.body || {};
  if (!userId) return res.status(400).json({ error: "invalid user id" });
  toggleChannel(userId, enabled ? 1 : 0);
  res.json({ ok: true, enabled: enabled ? 1 : 0 });
});

// 更新通道配置（callback_url、public_base_url、mp_api_key 等）
router.put("/moviepilot/channels/:userId", (req, res) => {
  const userId = parseInt(req.params.userId);
  if (!userId) return res.status(400).json({ error: "invalid user id" });
  const { callback_url, public_base_url, mp_api_key, enabled } = req.body || {};
  const updates = {};
  if (callback_url !== undefined) updates.callback_url = callback_url;
  if (public_base_url !== undefined) updates.public_base_url = public_base_url;
  if (mp_api_key !== undefined) updates.mp_api_key = mp_api_key;
  if (enabled !== undefined) updates.enabled = enabled ? 1 : 0;
  const channel = updateChannel(userId, updates);
  const db = getDB();
  const user = db.prepare("SELECT username FROM users WHERE id = ?").get(userId);
  res.json({ ok: true, channel: { ...channel, username: user ? user.username : null } });
});

module.exports = router;
