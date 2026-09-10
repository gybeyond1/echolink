/**
 * 管理员路由
 */

const express = require("express");
const router = express.Router();
const crypto = require("crypto");

const { getDB, getUserIdByUsername } = require("../db");
const moviepilot = require("../moviepilot");
const telegramBridge = require("../telegram_bridge");

// 中间件：验证管理员权限
router.use((req, res, next) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({ error: "未授权" });
  }
  const token = authHeader.substring(7);
  const db = getDB();
  // 这里简化处理，实际应该验证 token
  const user = db.prepare("SELECT * FROM users WHERE role = 'admin' LIMIT 1").get();
  if (!user) {
    return res.status(403).json({ error: "需要管理员权限" });
  }
  req.adminUser = user;
  next();
});

// 获取所有用户
router.get("/users", (req, res) => {
  const db = getDB();
  const users = db.prepare(`
    SELECT u.id, u.username, u.display_name, u.role, u.created_at,
      (SELECT COUNT(*) FROM notifications n WHERE n.user_id = u.id) as notification_count,
      (SELECT COUNT(*) FROM topic_members tm WHERE tm.user_id = u.id) as topic_count,
      (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id) as device_count
    FROM users u ORDER BY u.created_at DESC
  `).all();
  res.json({ users });
});

// 创建用户
router.post("/users", (req, res) => {
  const { username, password, role } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: "用户名和密码不能为空" });
  }
  const db = getDB();
  const existing = db.prepare("SELECT id FROM users WHERE username = ?").get(username);
  if (existing) {
    return res.status(409).json({ error: "用户名已存在" });
  }
  const passwordHash = crypto.createHash("sha256").update(password).digest("hex");
  const result = db.prepare("INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)").run(
    username, passwordHash, role || "user"
  );
  res.json({ id: result.lastInsertRowid, username, role: role || "user" });
});

// 删除用户
router.delete("/users/:id", (req, res) => {
  const { id } = req.params;
  const db = getDB();
  db.prepare("DELETE FROM users WHERE id = ?").run(id);
  res.json({ ok: true });
});

// 获取所有话题
router.get("/topics", (req, res) => {
  const db = getDB();
  const topics = db.prepare(`
    SELECT t.*, u.username as owner_name,
      (SELECT COUNT(*) FROM topic_members tm WHERE tm.topic_id = t.id) as member_count,
      (SELECT COUNT(*) FROM messages m WHERE m.topic_id = t.id) as message_count
    FROM topics t LEFT JOIN users u ON t.owner_id = u.id ORDER BY t.created_at DESC
  `).all();
  res.json({ topics });
});

// 删除话题
router.delete("/topics/:name", (req, res) => {
  const { name } = req.params;
  const db = getDB();
  db.prepare("DELETE FROM topics WHERE name = ?").run(name);
  res.json({ ok: true });
});

// 获取话题消息
router.get("/topics/:name/messages", (req, res) => {
  const { name } = req.params;
  const limit = parseInt(req.query.limit) || 100;
  const db = getDB();
  const topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(name);
  if (!topic) {
    return res.status(404).json({ error: "话题不存在" });
  }
  const messages = db.prepare(
    "SELECT * FROM messages WHERE topic_id = ? ORDER BY id DESC LIMIT ?"
  ).all(topic.id, limit);
  res.json({ messages: messages.reverse() });
});

// 获取所有通知
router.get("/notifications", (req, res) => {
  const db = getDB();
  let query = `
    SELECT n.*, u.username 
    FROM notifications n 
    LEFT JOIN users u ON n.user_id = u.id
  `;
  const params = [];
  if (req.query.userId) {
    query += " WHERE n.user_id = ?";
    params.push(req.query.userId);
  }
  query += " ORDER BY n.timestamp DESC LIMIT ?";
  params.push(parseInt(req.query.limit) || 200);
  const notifications = db.prepare(query).all(...params);
  res.json({ notifications });
});

// 删除通知
router.delete("/notifications/:id", (req, res) => {
  const { id } = req.params;
  const db = getDB();
  db.prepare("DELETE FROM notifications WHERE id = ?").run(id);
  res.json({ ok: true });
});

// 获取服务器设置
router.get("/settings", (req, res) => {
  const db = getDB();
  const settings = db.prepare("SELECT * FROM settings WHERE id = 1").get();
  res.json({ settings });
});

// 更新服务器设置
router.put("/settings", (req, res) => {
  const { settings } = req.body;
  const db = getDB();
  const allowedFields = [
    "max_image_size", "max_voice_size", "max_file_size",
    "max_topic_history", "messagewall_sync_url"
  ];
  const sets = [];
  const values = [];
  for (const field of allowedFields) {
    if (settings[field] !== undefined) {
      sets.push(`${field} = ?`);
      values.push(settings[field]);
    }
  }
  if (sets.length > 0) {
    db.prepare(`UPDATE settings SET ${sets.join(", ")} WHERE id = 1`).run(...values);
  }
  const updated = db.prepare("SELECT * FROM settings WHERE id = 1").get();
  res.json({ settings: updated });
});

// ============================================
// MoviePilot 通道管理
// ============================================

// 获取所有 MP 通道
router.get("/moviepilot/channels", (req, res) => {
  const channels = moviepilot.getAllChannels();
  res.json({ channels });
});

// 创建用户的 MP 通道
router.post("/moviepilot/channels/:userId", (req, res) => {
  const { userId } = req.params;
  try {
    const channel = moviepilot.createChannel(parseInt(userId));
    res.json({ ok: true, channel });
  } catch (e) {
    res.status(400).json({ ok: false, error: e.message });
  }
});

// 更新用户的 MP 通道配置
router.put("/moviepilot/channels/:userId", (req, res) => {
  const { userId } = req.params;
  try {
    const channel = moviepilot.updateChannel(parseInt(userId), req.body);
    res.json({ ok: true, channel });
  } catch (e) {
    res.status(400).json({ ok: false, error: e.message });
  }
});

// 切换通道启用/禁用
router.put("/moviepilot/channels/:userId/toggle", (req, res) => {
  const { userId } = req.params;
  const { enabled } = req.body;
  try {
    const channel = moviepilot.updateChannel(parseInt(userId), { enabled: enabled ? 1 : 0 });
    res.json({ ok: true, channel });
  } catch (e) {
    res.status(400).json({ ok: false, error: e.message });
  }
});

// 重置 Token
router.post("/moviepilot/channels/:userId/reset-token", (req, res) => {
  const { userId } = req.params;
  try {
    const channel = moviepilot.resetToken(parseInt(userId));
    res.json({ ok: true, channel });
  } catch (e) {
    res.status(400).json({ ok: false, error: e.message });
  }
});

// 删除通道
router.delete("/moviepilot/channels/:userId", (req, res) => {
  const { userId } = req.params;
  try {
    moviepilot.deleteChannel(parseInt(userId));
    res.json({ ok: true });
  } catch (e) {
    res.status(400).json({ ok: false, error: e.message });
  }
});

// 测试 Telegram 连接
router.post("/moviepilot/channels/:userId/test-telegram", async (req, res) => {
  const { userId } = req.params;
  try {
    const db = getDB();
    const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(parseInt(userId));
    if (!channel) {
      return res.status(404).json({ ok: false, error: "通道不存在" });
    }
    if (!channel.telegram_bot_token) {
      return res.json({ ok: false, error: "未配置 Telegram Bot Token" });
    }
    const result = await telegramBridge.telegramRequest(channel, "getMe", {});
    if (result.ok) {
      res.json({ ok: true, bot: result.result });
    } else {
      res.json({ ok: false, error: result.description || result.error || "连接失败" });
    }
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

// ============================================
// 留言板 WebHook 配置（已废弃，保留兼容）
// ============================================

// 获取留言板配置（已废弃，返回提示）
router.get("/messagewall", (req, res) => {
  const db = getDB();
  const users = db.prepare("SELECT id, username, display_name, role FROM users ORDER BY created_at DESC").all();
  const enabledUsers = db.prepare(`
    SELECT u.username 
    FROM messagewall_targets mwt 
    JOIN users u ON mwt.user_id = u.id 
    WHERE mwt.enabled = 1
  `).all().map(u => u.username);
  const settings = db.prepare("SELECT * FROM settings WHERE id = 1").get();
  res.json({
    deprecated: true,
    message: "留言板 WebHook 配置已迁移到留言板用户管理",
    users,
    enabledUsers,
    webhookBase: settings?.messagewall_sync_url || "",
  });
});

// 更新留言板配置（已废弃，返回提示）
router.put("/messagewall", (req, res) => {
  res.json({
    deprecated: true,
    message: "留言板 WebHook 配置已迁移到留言板用户管理",
  });
});

// 获取统计信息
router.get("/stats", (req, res) => {
  const db = getDB();
  const users = db.prepare("SELECT COUNT(*) as count FROM users").get().count;
  const devices = db.prepare("SELECT COUNT(*) as count FROM devices").get().count;
  const topics = db.prepare("SELECT COUNT(*) as count FROM topics").get().count;
  const messages = db.prepare("SELECT COUNT(*) as count FROM messages").get().count;
  const notifications = db.prepare("SELECT COUNT(*) as count FROM notifications").get().count;
  res.json({ users, devices, topics, messages, notifications });
});

module.exports = router;
