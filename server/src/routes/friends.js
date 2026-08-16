const express = require("express");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");
const { broadcastToUser } = require("../websocket");
const { ensureDmTopic } = require("./topics");

const router = express.Router();

function getFriend(userId, friendId) {
  return getDB().prepare("SELECT * FROM friends WHERE user_id = ? AND friend_id = ?").get(userId, friendId);
}
function getUserByName(username) {
  return getDB().prepare("SELECT id, username FROM users WHERE username = ?").get(username);
}

// 搜索用户（加好友用）
// GET /api/friends/search?q=
router.get("/friends/search", authMiddleware, (req, res) => {
  const q = String(req.query.q || "").trim();
  if (!q) return res.json({ users: [] });
  const db = getDB();
  const users = db
    .prepare(
      `SELECT u.id, u.username,
              CASE WHEN EXISTS (SELECT 1 FROM friends f WHERE f.user_id = ? AND f.friend_id = u.id) THEN 1 ELSE 0 END as is_friend,
              CASE WHEN EXISTS (SELECT 1 FROM friend_requests r WHERE r.from_user = ? AND r.to_user = u.id AND r.status = 'pending') THEN 1 ELSE 0 END as requested
       FROM users u
       WHERE u.username LIKE ? AND u.id != ?
       ORDER BY u.username LIMIT 20`
    )
    .all(req.userId, req.userId, "%" + q + "%", req.userId);
  res.json({ users });
});

// 申请加好友
// POST /api/friends/requests  body: { username, message? }
router.post("/friends/requests", authMiddleware, (req, res) => {
  const username = String(req.body.username || "").trim();
  if (!username) return res.status(400).json({ error: "username is required" });
  if (username === req.username) return res.status(400).json({ error: "不能添加自己为好友" });
  const db = getDB();
  const target = getUserByName(username);
  if (!target) return res.status(404).json({ error: "用户不存在" });
  if (getFriend(req.userId, target.id)) return res.status(409).json({ error: "你们已经是好友了" });

  const existing = db
    .prepare("SELECT * FROM friend_requests WHERE from_user = ? AND to_user = ?")
    .get(req.userId, target.id);
  if (existing && existing.status === "pending") {
    return res.status(409).json({ error: "申请已发送，等待对方处理" });
  }
  db.prepare(
    `INSERT INTO friend_requests (from_user, to_user, status, message, requested_at, handled_at)
     VALUES (?, ?, 'pending', ?, datetime('now'), NULL)
     ON CONFLICT(from_user, to_user) DO UPDATE SET status = 'pending', message = excluded.message,
       requested_at = datetime('now'), handled_at = NULL`
  ).run(req.userId, target.id, String(req.body.message || "").slice(0, 300));

  // WS 实时推送：对方话题页「新的申请」即时出现红点
  try {
    broadcastToUser(target.id, {
      type: "friend_request",
      data: { username: req.username || "某用户", message: String(req.body.message || "").slice(0, 300) },
    });
  } catch (e) { /* ignore */ }

  res.status(201).json({ message: "Friend request sent" });
});

// 我收到/发出的好友申请
// GET /api/friends/requests
router.get("/friends/requests", authMiddleware, (req, res) => {
  const db = getDB();
  const incoming = db
    .prepare(
      `SELECT r.id, r.status, r.message, r.requested_at, u.username
       FROM friend_requests r LEFT JOIN users u ON r.from_user = u.id
       WHERE r.to_user = ? ORDER BY r.requested_at DESC LIMIT 100`
    )
    .all(req.userId);
  const outgoing = db
    .prepare(
      `SELECT r.id, r.status, r.message, r.requested_at, u.username
       FROM friend_requests r LEFT JOIN users u ON r.to_user = u.id
       WHERE r.from_user = ? ORDER BY r.requested_at DESC LIMIT 100`
    )
    .all(req.userId);
  res.json({ incoming, outgoing });
});

// 处理好友申请：accept | reject | ignore
router.post("/friends/requests/:id/accept", authMiddleware, (req, res) => handleFriendRequest(req, res, "accepted"));
router.post("/friends/requests/:id/reject", authMiddleware, (req, res) => handleFriendRequest(req, res, "rejected"));
router.post("/friends/requests/:id/ignore", authMiddleware, (req, res) => handleFriendRequest(req, res, "ignored"));

function handleFriendRequest(req, res, status) {
  const db = getDB();
  const row = db.prepare("SELECT * FROM friend_requests WHERE id = ? AND to_user = ?").get(parseInt(req.params.id), req.userId);
  if (!row) return res.status(404).json({ error: "Request not found" });
  if (row.status !== "pending" && status !== "ignored") {
    return res.status(409).json({ error: "该申请已处理" });
  }
  db.prepare("UPDATE friend_requests SET status = ?, handled_at = datetime('now') WHERE id = ?").run(status, row.id);
  if (status === "accepted") {
    db.prepare("INSERT OR IGNORE INTO friends (user_id, friend_id) VALUES (?, ?)").run(row.to_user, row.from_user);
    db.prepare("INSERT OR IGNORE INTO friends (user_id, friend_id) VALUES (?, ?)").run(row.from_user, row.to_user);
    try {
      broadcastToUser(row.from_user, {
        type: "friend_accepted",
        data: { username: req.username || "对方" },
      });
    } catch (e) { /* ignore */ }
  }
  res.json({ message: "Request " + status });
}

// 好友列表
// GET /api/friends
router.get("/friends", authMiddleware, (req, res) => {
  const db = getDB();
  const friends = db
    .prepare(
      `SELECT u.id as user_id, u.username, f.created_at
       FROM friends f LEFT JOIN users u ON f.friend_id = u.id
       WHERE f.user_id = ? ORDER BY u.username LIMIT 500`
    )
    .all(req.userId);
  res.json({ friends });
});

// 删除好友
// DELETE /api/friends/:username
router.delete("/friends/:username", authMiddleware, (req, res) => {
  const db = getDB();
  const target = getUserByName(req.params.username);
  if (!target) return res.status(404).json({ error: "用户不存在" });
  const r1 = db.prepare("DELETE FROM friends WHERE user_id = ? AND friend_id = ?").run(req.userId, target.id);
  db.prepare("DELETE FROM friends WHERE user_id = ? AND friend_id = ?").run(target.id, req.userId);
  if (r1.changes === 0) return res.status(404).json({ error: "你们不是好友" });
  res.json({ message: "Friend removed" });
});

// 打开/创建与好友的私聊会话（dm 话题，复用话题消息全套能力）
// POST /api/friends/chat/:username
router.post("/friends/chat/:username", authMiddleware, (req, res) => {
  const target = getUserByName(req.params.username);
  if (!target) return res.status(404).json({ error: "用户不存在" });
  if (!getFriend(req.userId, target.id)) return res.status(403).json({ error: "你们不是好友" });
  const topic = ensureDmTopic(req.userId, target.id);
  res.json({ topic: topic.name, title: target.username });
});

// 统一的「新的申请」汇总（好友申请 + 我创建话题的加群申请，pending）
// GET /api/requests
router.get("/requests", authMiddleware, (req, res) => {
  const db = getDB();
  const friendRequests = db
    .prepare(
      `SELECT r.id, u.username, r.message, r.requested_at
       FROM friend_requests r LEFT JOIN users u ON r.from_user = u.id
       WHERE r.to_user = ? AND r.status = 'pending'
       ORDER BY r.requested_at DESC LIMIT 50`
    )
    .all(req.userId);
  const topicRequests = db
    .prepare(
      `SELECT r.id, t.name as topic, u.username, r.message, r.requested_at
       FROM topic_join_requests r
       JOIN topics t ON r.topic_id = t.id
       LEFT JOIN users u ON r.user_id = u.id
       WHERE t.owner_id = ? AND r.status = 'pending'
       ORDER BY r.requested_at DESC LIMIT 50`
    )
    .all(req.userId);
  res.json({ friend_requests: friendRequests, topic_requests: topicRequests });
});

module.exports = router;
