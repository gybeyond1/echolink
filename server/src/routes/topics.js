const express = require("express");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");
const { publishToTopic, normalizeTopic } = require("../websocket");

const router = express.Router();

// 发布消息到话题（HTTP 方式，也可以走 WebSocket）
// POST /api/topics/:topic/publish  body: { title, text, sender_name?, device_id? }
router.post("/:topic/publish", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) {
    return res.status(400).json({ error: "Invalid topic name (1-64 chars of a-z0-9_-)" });
  }

  const { title, text } = req.body;
  if ((!title || !String(title).trim()) && (!text || !String(text).trim())) {
    return res.status(400).json({ error: "title or text is required" });
  }

  const db = getDB();
  const deviceId = req.body.device_id || null;
  const sender = String(req.body.sender_name || req.username || "unknown").slice(0, 64);
  const ts = Date.now();

  const result = db
    .prepare(`INSERT INTO topic_messages (topic, user_id, device_id, sender_name, title, text, timestamp)
              VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .run(name, req.userId, deviceId, sender, String(title || "").slice(0, 500), String(text || "").slice(0, 2000), ts);

  const message = {
    id: result.lastInsertRowid,
    topic: name,
    title: title || "",
    text: text || "",
    sender_name: sender,
    timestamp: ts,
    device_id: deviceId,
  };

  // 推送给订阅者，排除发送者自己的设备连接（本机去重）
  const sent = publishToTopic(name, message, { excludeDeviceId: deviceId });

  // 清理旧消息
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(`DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
      SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)
    `).run(name, name, maxHistory);

  res.status(201).json({ message: "Published", topic_message: message, delivered: sent });
});

// 获取话题消息列表
// GET /api/topics/:topic/messages?limit=50&since=<timestamp>
router.get("/:topic/messages", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) {
    return res.status(400).json({ error: "Invalid topic name" });
  }

  const db = getDB();
  const limit = Math.min(parseInt(req.query.limit) || 50, 200);
  const since = parseInt(req.query.since) || 0;

  const messages =
    since > 0
      ? db
          .prepare(`SELECT * FROM topic_messages WHERE topic = ? AND timestamp > ? ORDER BY id ASC LIMIT ?`)
          .all(name, since, limit)
      : db
          .prepare(`SELECT * FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?`)
          .all(name, limit)
          .reverse();

  res.json({ topic: name, messages });
});

// 列出服务器上有消息记录的话题
// GET /api/topics
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  const topics = db
    .prepare(`SELECT topic, COUNT(*) as message_count, MAX(timestamp) as last_message_at
              FROM topic_messages GROUP BY topic ORDER BY last_message_at DESC LIMIT 100`)
    .all();
  res.json({ topics });
});

module.exports = router;
