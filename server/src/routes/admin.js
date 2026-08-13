const express = require("express");
const bcrypt = require("bcryptjs");
const { getDB, getSettings, setSettings } = require("../db");
const { authMiddleware, requireAdmin } = require("../middleware/auth");

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

// 删除用户（不能删自己）
router.delete("/users/:id", (req, res) => {
  const db = getDB();
  const id = parseInt(req.params.id);
  if (id === req.userId) return res.status(400).json({ error: "Cannot delete yourself" });
  const user = db.prepare("SELECT id FROM users WHERE id = ?").get(id);
  if (!user) return res.status(404).json({ error: "User not found" });
  db.prepare("DELETE FROM users WHERE id = ?").run(id); // 级联删除其通知/设备/话题成员等
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

module.exports = router;
