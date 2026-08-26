const express = require("express");
const path = require("path");
const fs = require("fs");
const multer = require("multer");
const { getDB, getMediaLimitBytes } = require("../db");
const { authMiddleware } = require("../middleware/auth");
const { publishToTopic, normalizeTopic } = require("../websocket");

const router = express.Router();

// 媒体上传（图片/语音/文件）落地目录：与数据库同级的 uploads/
const UPLOAD_ROOT = path.join(process.env.DB_PATH ? path.dirname(process.env.DB_PATH) : "./data", "uploads");
if (!fs.existsSync(UPLOAD_ROOT)) fs.mkdirSync(UPLOAD_ROOT, { recursive: true });
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_ROOT),
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname || "").slice(0, 12);
    cb(null, Date.now() + "-" + Math.random().toString(36).slice(2, 8) + ext);
  },
});
// 上传大小硬限：默认 0 = 不限制（multer 层不设限）。
// 如需限制（比如反代或磁盘有限），设置环境变量 MAX_UPLOAD_MB=100。
const MAX_UPLOAD_MB = parseFloat(process.env.MAX_UPLOAD_MB || "0") || 0;
const upload = multer({
  storage,
  ...(MAX_UPLOAD_MB > 0 ? { limits: { fileSize: Math.round(MAX_UPLOAD_MB * 1024 * 1024) } } : {}),
});

// 安全删除：某些环境（如受限沙箱）会在 unlink 时抛错，这里吞掉异常，
// 避免"拒绝超大文件"等正常分支意外把整个服务进程搞崩。
function safeUnlink(p) {
  try { if (p) fs.unlinkSync(p); } catch (_) { /* 忽略：文件可能已不存在 */ }
}

function getTopic(name) {
  return getDB().prepare("SELECT * FROM topics WHERE name = ?").get(name);
}
function getMembership(topicId, userId) {
  return getDB().prepare("SELECT * FROM topic_members WHERE topic_id = ? AND user_id = ?").get(topicId, userId);
}

// 确保同账号「设备会话」存在：u{userId}-devices，kind='devices'。
// 同一账号所有设备自动进入这个会话；置顶、不可删除、不可退出。
function ensureDeviceTopic(userId) {
  const db = getDB();
  const name = `u${userId}-devices`;
  let topic = getTopic(name);
  if (!topic) {
    db.prepare("INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, '', 'devices')")
      .run(name, userId, "我的设备");
    topic = getTopic(name);
  }
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userId);
  return topic;
}

// 确保好友私聊话题存在：dm-{minId}-{maxId}，kind='dm'，成员仅两人
function ensureDmTopic(userA, userB) {
  const db = getDB();
  const a = Math.min(userA, userB);
  const b = Math.max(userA, userB);
  const name = `dm-${a}-${b}`;
  let topic = getTopic(name);
  if (!topic) {
    db.prepare("INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, '', 'dm')").run(name, a, "");
    topic = getTopic(name);
  }
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userA);
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userB);
  return topic;
}

// 标记 dm 私聊里「对方发来的」消息为已读，并实时通知发送者（已读回执）。
// 仅标记 user_id != viewerUserId 且尚未已读的消息，避免误标自己的消息。
function markDmRead(topic, ids, viewerUserId) {
  if (!ids || ids.length === 0) return;
  const db = getDB();
  const placeholders = ids.map(() => "?").join(",");
  const toMark = db
    .prepare(`SELECT id, user_id FROM topic_messages WHERE topic = ? AND id IN (${placeholders}) AND user_id != ? AND read = 0`)
    .all(topic, ...ids, viewerUserId);
  if (toMark.length === 0) return;
  const markIds = toMark.map((r) => r.id);
  const markPh = markIds.map(() => "?").join(",");
  db.prepare(`UPDATE topic_messages SET read = 1 WHERE topic = ? AND id IN (${markPh})`).run(topic, ...markIds);
  // 按发送者分组，逐一通知原发送者其消息已被读取
  const bySender = {};
  toMark.forEach((r) => { (bySender[r.user_id] = bySender[r.user_id] || []).push(r.id); });
  const { broadcastToUser } = require("../websocket");
  Object.keys(bySender).forEach((senderId) => {
    try {
      broadcastToUser(parseInt(senderId), { type: "message_read", data: { topic, ids: bySender[senderId] } });
    } catch (_) { /* WS 推送失败不影响标记 */ }
  });
}

// 列出我参与（成员）的话题
// GET /api/topics
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  // 每次拉取都确保设备默认会话存在（登录后的兜底，防旧账号缺失）
  ensureDeviceTopic(req.userId);
  // 管理员：默认可见全部设备群组与所有人建的群聊，且默认可发消息/文件（my_role 置为 'admin'）
  const isAdmin = req.role === "admin";
  const topics = db
    .prepare(
      `SELECT DISTINCT t.id, t.name, t.title, t.description, t.owner_id, t.kind, u.username as owner_name,
              ${isAdmin ? "'admin'" : "m.role"} as my_role,
              CASE t.kind
                WHEN 'devices' THEN '我的设备（' || COALESCE(NULLIF(u.display_name, ''), u.username, '未知') || '）'
                WHEN 'dm' THEN (SELECT COALESCE(u2.display_name, u2.username) FROM topic_members m2 LEFT JOIN users u2 ON m2.user_id = u2.id
                                WHERE m2.topic_id = t.id AND m2.user_id != ? LIMIT 1)
                WHEN 'messagewall' THEN COALESCE(t.title, '留言板')
                ELSE t.name
              END as display_name,
              CASE t.kind
                WHEN 'dm' THEN (SELECT u2.avatar FROM topic_members m2 LEFT JOIN users u2 ON m2.user_id = u2.id
                                WHERE m2.topic_id = t.id AND m2.user_id != ? LIMIT 1)
                ELSE NULL
              END as avatar,
              (SELECT COUNT(*) FROM topic_messages tm WHERE tm.topic = t.name
                 AND tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)) as message_count,
              (SELECT MAX(timestamp) FROM topic_messages tm WHERE tm.topic = t.name
                 AND tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)) as last_message_at,
              (SELECT text FROM topic_messages tm WHERE tm.topic = t.name
                 AND tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)
                 ORDER BY id DESC LIMIT 1) as last_message,
              (SELECT COUNT(*) FROM topic_join_requests jr WHERE jr.topic_id = t.id AND jr.status='pending') as pending_requests,
              COALESCE((SELECT mbr.last_read_id FROM topic_members mbr WHERE mbr.topic_id = t.id AND mbr.user_id = ?), 0) as last_read_id,
              (SELECT COUNT(*) FROM topic_messages tm WHERE tm.topic = t.name AND tm.id > COALESCE((SELECT mbr.last_read_id FROM topic_members mbr WHERE mbr.topic_id = t.id AND mbr.user_id = ?), 0) AND (tm.user_id IS NULL OR tm.user_id != ?) AND tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)) as unread_count
       FROM topics t
       ${isAdmin ? "LEFT" : "INNER"} JOIN topic_members m ON m.topic_id = t.id
       LEFT JOIN users u ON t.owner_id = u.id
       WHERE ${isAdmin ? "1=1" : "m.user_id = ?"}
         AND (t.kind != 'devices' OR u.id IS NOT NULL)
         AND (
           t.kind = 'devices'
           OR EXISTS (
             SELECT 1 FROM topic_messages tm
             WHERE tm.topic = t.name
               AND tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)
           )
         )
       ORDER BY CASE t.kind WHEN 'devices' THEN 0 WHEN 'messagewall' THEN 1 WHEN 'dm' THEN 2 ELSE 3 END, last_message_at DESC, t.created_at DESC
       LIMIT 100`
    )
    .all(req.userId, req.userId, req.userId, req.userId, req.userId, req.userId, req.userId, req.userId, req.userId, req.userId, ...(isAdmin ? [] : [req.userId]));
  res.json({ topics });
});

// 发现话题（非成员可见，用于申请加入）
// GET /api/topics/discover?q=
router.get("/discover", authMiddleware, (req, res) => {
  const db = getDB();
  const q = (req.query.q || "").trim().toLowerCase();
  const sql = `SELECT t.id, t.name, t.title, t.owner_id, u.username as owner_name,
              (SELECT COUNT(*) FROM topic_members tm WHERE tm.topic_id = t.id) as member_count,
              (SELECT COUNT(*) FROM topic_messages tm2 WHERE tm2.topic = t.name) as message_count
       FROM topics t LEFT JOIN users u ON t.owner_id = u.id
       WHERE (t.kind IS NULL OR t.kind = 'normal')
       AND NOT EXISTS (SELECT 1 FROM topic_members m WHERE m.topic_id = t.id AND m.user_id = ?)
       ${q ? "AND t.name LIKE ?" : ""}
       ORDER BY t.created_at DESC LIMIT 50`;
  const rows = q ? db.prepare(sql).all(req.userId, "%" + q + "%") : db.prepare(sql).all(req.userId);
  res.json({ topics: rows });
});

// 创建话题（群聊）。创建者成为 owner。
// POST /api/topics  body: { name, title?, description? }
router.post("/", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.body.name || req.body.topic);
  if (!name) {
    return res.status(400).json({ error: "Invalid topic name (1-64 chars of a-z0-9_-)" });
  }
  const db = getDB();
  if (getTopic(name)) {
    return res.status(409).json({ error: "Topic already exists" });
  }
  const info = db
    .prepare("INSERT INTO topics (name, owner_id, title, description) VALUES (?, ?, ?, ?)")
    .run(name, req.userId, String(req.body.title || "").slice(0, 120), String(req.body.description || "").slice(0, 500));
  db.prepare("INSERT INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'owner')").run(info.lastInsertRowid, req.userId);
  res.status(201).json({
    message: "Topic created",
    topic: { id: info.lastInsertRowid, name, owner_id: req.userId, my_role: "owner" },
  });
});

// 申请加入话题（需创建者审批）
// POST /api/topics/:topic/join  body: { message? }
router.post("/:topic/join", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  // 设备会话/好友私聊不允许外部申请加入
  if (topic.kind === "devices" || topic.kind === "dm") {
    return res.status(403).json({ error: "This topic does not accept join requests" });
  }
  if (getMembership(topic.id, req.userId)) return res.status(409).json({ error: "You are already a member" });
  const existing = db.prepare("SELECT * FROM topic_join_requests WHERE topic_id = ? AND user_id = ?").get(topic.id, req.userId);
  if (existing && existing.status === "pending") {
    return res.status(409).json({ error: "Join request already pending", request: existing });
  }
  db.prepare(
    "INSERT OR REPLACE INTO topic_join_requests (topic_id, user_id, status, message, requested_at, handled_at) VALUES (?, ?, 'pending', ?, datetime('now'), NULL)"
  ).run(topic.id, req.userId, String(req.body.message || "").slice(0, 300));

  // 通知话题创建者有人申请加入（写入其通知列表 + WS 实时推送，创建者话题页「新的申请」即时出现）
  try {
    db.prepare(
      `INSERT INTO notifications (user_id, device_id, package_name, app_name, title, text, timestamp)
       VALUES (?, NULL, 'topic', '话题', ?, ?, ?)`
    ).run(
      topic.owner_id,
      `申请加入话题「${name}」`,
      `${req.username || "某用户"} 申请加入你创建的话题「${name}」`,
      Date.now()
    );
  } catch (e) {
    console.error("[topics] notify owner failed:", e);
  }
  try {
    const { broadcastToUser } = require("../websocket");
    broadcastToUser(topic.owner_id, {
      type: "topic_request",
      data: { topic: name, request_id: Number(process.hrtime.bigint()), username: req.username || "某用户" },
    });
  } catch (e) { /* WS 推送失败不影响申请 */ }

  res.status(201).json({ message: "Join request sent, awaiting owner approval", status: "pending" });
});

// 列出某话题成员（成员/管理员）
// GET /api/topics/:topic/members
router.get("/:topic/members", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
    return res.status(403).json({ error: "Not a member of this topic" });
  }
  const members = db
    .prepare(
      `SELECT m.user_id, m.role, m.joined_at, u.username
       FROM topic_members m LEFT JOIN users u ON m.user_id = u.id
       WHERE m.topic_id = ? ORDER BY (m.role='owner') DESC, m.joined_at ASC`
    )
    .all(topic.id);
  res.json({ members });
});

// 列出待审批申请（仅 owner/管理员）
// GET /api/topics/:topic/requests
router.get("/:topic/requests", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  const mem = getMembership(topic.id, req.userId);
  if (!(mem && mem.role === "owner") && req.role !== "admin") {
    return res.status(403).json({ error: "Only the topic owner or admin can view requests" });
  }
  const requests = db
    .prepare(
      `SELECT r.id, r.user_id, r.status, r.message, r.requested_at, u.username
       FROM topic_join_requests r LEFT JOIN users u ON r.user_id = u.id
       WHERE r.topic_id = ? ORDER BY r.requested_at DESC`
    )
    .all(topic.id);
  res.json({ requests });
});

// 审批通过 / 拒绝 / 忽略
// POST /api/topics/:topic/requests/:id/approve | /reject | /ignore
router.post("/:topic/requests/:id/approve", authMiddleware, (req, res) => handleRequest(req, res, "approved"));
router.post("/:topic/requests/:id/reject", authMiddleware, (req, res) => handleRequest(req, res, "rejected"));
router.post("/:topic/requests/:id/ignore", authMiddleware, (req, res) => handleRequest(req, res, "ignored"));

function handleRequest(req, res, status) {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  const mem = getMembership(topic.id, req.userId);
  if (!(mem && mem.role === "owner") && req.role !== "admin") {
    return res.status(403).json({ error: "Only the topic owner can handle requests" });
  }
  const reqRow = db.prepare("SELECT * FROM topic_join_requests WHERE id = ? AND topic_id = ?").get(parseInt(req.params.id), topic.id);
  if (!reqRow) return res.status(404).json({ error: "Request not found" });
  db.prepare("UPDATE topic_join_requests SET status = ?, handled_at = datetime('now') WHERE id = ?").run(status, reqRow.id);
  if (status === "approved" && !getMembership(topic.id, reqRow.user_id)) {
    db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, reqRow.user_id);
  }
  // WS 通知申请人审批结果（客户端用于刷新话题列表）
  try {
    const { broadcastToUser } = require("../websocket");
    broadcastToUser(reqRow.user_id, {
      type: "topic_request_handled",
      data: { topic: name, status },
    });
  } catch (e) { /* ignore */ }
  res.json({ message: "Request " + status, request: { id: reqRow.id, status } });
}

// 退出话题（成员可退出；owner 不可退出）
// POST /api/topics/:topic/leave
router.post("/:topic/leave", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  // 设备会话为默认会话，不可退出；私聊会话允许从列表移除（仅删除自己的 membership，不删好友关系）
  if (topic.kind === "devices") return res.status(400).json({ error: "设备会话不可退出" });
  if (topic.kind === "messagewall") return res.status(400).json({ error: "留言板会话不可退出" });
  // 管理员不是普通 member，但从列表移除/关闭普通话题时不应报错；按 delete 权限处理
  const mem = getMembership(topic.id, req.userId);
  const isAdmin = req.role === "admin";
  if (!mem && !isAdmin) return res.status(404).json({ error: "You are not a member" });
  if (mem && mem.role === "owner") return res.status(400).json({ error: "Owner cannot leave; delete the topic instead" });
  if (isAdmin) {
    // 管理员视角下所有话题始终可见（GET /api/topics 用 LEFT JOIN），「离开」对管理员无意义；
    // 故管理员主动「移除」= 彻底关闭该话题（删除消息与话题本身），使其真正从列表消失。
    db.prepare("DELETE FROM topic_messages WHERE topic = ?").run(name);
    db.prepare("DELETE FROM topics WHERE id = ?").run(topic.id); // 级联删除 members/requests
    res.json({ message: "Topic closed by admin" });
    return;
  }
  // 普通成员：从自己的 membership 移除（保留话题与消息，对其他人仍可见）
  if (mem) {
    db.prepare("DELETE FROM topic_members WHERE topic_id = ? AND user_id = ?").run(topic.id, req.userId);
  }
  res.json({ message: "Left topic" });
});

// 上传话题媒体（图片/语音/文件）。需为该话题成员/管理员。
// POST /api/topics/:topic/media?kind=image|voice|file   (multipart, field "file")
router.post("/:topic/media", authMiddleware, (req, res) => {
  upload.single("file")(req, res, (err) => {
    if (err) return res.status(400).json({ error: "上传失败: " + (err.message || err.code || "未知错误") });
    const name = normalizeTopic(req.params.topic);
    const file = req.file;
    if (!file) return res.status(400).json({ error: "未收到文件" });
    const kind = String(req.query.kind || req.body.kind || "file");
    const allowed = { image: true, voice: true, file: true };
    if (!allowed[kind]) {
      safeUnlink(file.path);
      return res.status(400).json({ error: "无效的媒体类型" });
    }
    const limit = getMediaLimitBytes(kind);
    if (limit > 0 && file.size > limit) {
      safeUnlink(file.path);
      return res.status(413).json({ error: `文件大小超过上限（${Math.round(limit / 1024 / 1024)}MB）` });
    }
    const db = getDB();
    const topic = getTopic(name);
    if (!topic) {
      safeUnlink(file.path);
      return res.status(404).json({ error: "Topic not found" });
    }
    if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
      safeUnlink(file.path);
      return res.status(403).json({ error: "Not a member of this topic" });
    }
    const url = "/uploads/" + encodeURIComponent(path.basename(file.path));
    res.status(201).json({
      url,
      name: file.originalname || path.basename(file.path),
      size: file.size,
      type: kind,
    });
  });
});

// 发布消息到话题（成员/管理员可发）
// POST /api/topics/:topic/publish  body: { title, text, sender_name?, device_id? }
router.post("/:topic/publish", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) {
    return res.status(400).json({ error: "Invalid topic name (1-64 chars of a-z0-9_-)" });
  }

  const { title, text, media_type, media_url, media_name, media_size } = req.body;
  const hasText = (title && String(title).trim()) || (text && String(text).trim());
  const hasMedia = media_type && media_type !== "text" && media_url;
  if (!hasText && !hasMedia) {
    return res.status(400).json({ error: "title, text or media is required" });
  }

  const db = getDB();
  let topic = getTopic(name);
  // 首次发布且话题不存在：自动创建，发布者成为 owner（保持"输入即建"的易用性）
  if (!topic) {
    const info = db.prepare("INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, ?, 'normal')").run(name, req.userId, name, "");
    topic = { id: info.lastInsertRowid, name };
    db.prepare("INSERT INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'owner')").run(topic.id, req.userId);
  } else if (topic.kind === "devices" || topic.kind === "dm") {
    // 设备会话：仅同账号；私聊：仅 dm-<a>-<b> 中的两位好友（名字里就能校验）
    let allowed = false;
    if (topic.kind === "devices") {
      allowed = req.userId === topic.owner_id || req.role === "admin";
    } else {
      const m = /^dm-(\d+)-(\d+)$/.exec(name);
      allowed = !!m && (req.userId === parseInt(m[1]) || req.userId === parseInt(m[2]) || req.role === "admin");
    }
    if (!allowed) return res.status(403).json({ error: "You are not a member of this topic" });
    // 兜底补齐成员关系（历史数据可能缺）
    db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, req.userId);
  } else if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
    return res.status(403).json({ error: "You are not a member of this topic. Request to join first." });
  }

  const deviceId = req.body.device_id || null;
  const sender = String(req.body.sender_name || req.username || "unknown").slice(0, 64);
  // 设备名：客户端上报优先；未上报则回退查 devices 表
  let deviceName = String(req.body.device_name || "").slice(0, 64);
  if (!deviceName && deviceId) {
    const dev = db.prepare("SELECT device_name FROM devices WHERE id = ?").get(deviceId);
    if (dev && dev.device_name) deviceName = String(dev.device_name).slice(0, 64);
  }
  const ts = Date.now();
  const mediaType = hasMedia ? String(media_type).slice(0, 16) : "text";

  const result = db
    .prepare(`INSERT INTO topic_messages (topic, user_id, device_id, sender_name, title, text, media_type, media_url, media_name, media_size, device_name, timestamp)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(
      name, req.userId, deviceId, sender,
      String(title || "").slice(0, 500), String(text || "").slice(0, 2000),
      mediaType, hasMedia ? String(media_url).slice(0, 500) : null,
      hasMedia ? String(media_name || "").slice(0, 200) : null,
      hasMedia ? (parseInt(media_size) || 0) : 0,
      deviceName || null,
      ts
    );

  // 查询发送者的头像和昵称（用于客户端聊天页头像渲染）
  const userRow = db.prepare("SELECT avatar, display_name FROM users WHERE id = ?").get(req.userId);

  // dm 私聊：取对方头像作为 peer_avatar 随消息下发，彻底杜绝实时消息缺头像
  let peerAvatarForPublish = null;
  if (topic.kind === "dm") {
    const peerRow = db
      .prepare(`SELECT u2.avatar FROM topic_members m2 LEFT JOIN users u2 ON m2.user_id = u2.id WHERE m2.topic_id = ? AND m2.user_id != ? LIMIT 1`)
      .get(topic.id, req.userId);
    peerAvatarForPublish = peerRow?.avatar || null;
  }

  const message = {
    id: result.lastInsertRowid,
    topic: name,
    title: title || "",
    text: text || "",
    media_type: mediaType,
    media_url: hasMedia ? media_url : null,
    media_name: hasMedia ? (media_name || "") : null,
    media_size: hasMedia ? (parseInt(media_size) || 0) : 0,
    sender_name: sender,
    sender_display_name: userRow?.display_name || null,
    sender_avatar: userRow?.avatar || null,
    user_id: req.userId,
    timestamp: ts,
    device_id: deviceId,
    device_name: deviceName || null,
    peer_avatar: peerAvatarForPublish,
  };

  const sent = publishToTopic(name, message, { excludeDeviceId: deviceId });

  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(`DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
      SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)
    `).run(name, name, maxHistory);

  res.status(201).json({ message: "Published", topic_message: message, delivered: sent });
});

// 获取话题消息列表（成员/管理员可见）
// GET /api/topics/:topic/messages?limit=50&since=<timestamp>
router.get("/:topic/messages", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });

  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
    return res.status(403).json({ error: "Not a member of this topic" });
  }

  const limit = Math.min(parseInt(req.query.limit) || 50, 200);
  const since = parseInt(req.query.since) || 0;

  // dm 私聊：预先取「对方」头像，作为每条消息的兜底头像（peer_avatar）。
  // 客户端历史消息 sender_avatar 可能为空（对方设头像前发的），此时用 peer_avatar 兜底，
  // 彻底杜绝「首条/老消息没头像」。群聊/设备会话无统一对方头像，peer_avatar 为 null。
  let peerAvatar = null;
  if (topic.kind === "dm") {
    const peerRow = db
      .prepare(`SELECT u2.avatar FROM topic_members m2 LEFT JOIN users u2 ON m2.user_id = u2.id WHERE m2.topic_id = ? AND m2.user_id != ? LIMIT 1`)
      .get(topic.id, req.userId);
    peerAvatar = peerRow?.avatar || null;
  }

  // 过滤掉「本用户已软删除」的消息（单向隐藏，不影响他人）
  const hiddenSql = `tm.id NOT IN (SELECT message_id FROM topic_message_deletes WHERE user_id = ?)`;
  const messages =
    since > 0
      ? db.prepare(`SELECT tm.*, u.avatar as sender_avatar, u.display_name as sender_display_name FROM topic_messages tm LEFT JOIN users u ON tm.user_id = u.id WHERE tm.topic = ? AND ${hiddenSql} AND tm.timestamp > ? ORDER BY tm.id ASC LIMIT ?`).all(name, req.userId, since, limit)
      : db.prepare(`SELECT tm.*, u.avatar as sender_avatar, u.display_name as sender_display_name FROM topic_messages tm LEFT JOIN users u ON tm.user_id = u.id WHERE tm.topic = ? AND ${hiddenSql} ORDER BY tm.id DESC LIMIT ?`).all(name, req.userId, limit).reverse();

  // 给每条消息补上 peer_avatar（dm 才有意义），客户端用于兜底头像
  messages.forEach((m) => { m.peer_avatar = peerAvatar; });

  // dm 私聊：把「对方发来且未读」的消息标记为已读，并通知对方（已读回执）
  if (topic.kind === "dm") {
    const unread = messages
      .filter((m) => m.user_id != null && m.user_id !== req.userId && m.read === 0)
      .map((m) => m.id);
    if (unread.length) markDmRead(name, unread, req.userId);
  }

  res.json({ topic: name, messages });
});

// 标记进入话题：把当前用户在该话题的 last_read_id 设为最新消息 id，用于未读数清零。
// 所有话题类型通用（devices / normal / dm）。
// POST /api/topics/:topic/read
router.post("/:topic/read", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  const mem = getMembership(topic.id, req.userId);
  if (!mem && req.role !== "admin") return res.status(403).json({ error: "Not a member of this topic" });
  const maxId = db.prepare("SELECT COALESCE(MAX(id), 0) as max_id FROM topic_messages WHERE topic = ?").get(name);
  // 管理员无普通 membership（GET 列表用 LEFT JOIN 可见全部），需先补一条再更新 last_read_id，
  // 否则 last_read_id 永远为 0 → 未读数基于 0 永远 > 0 → 点进会话看过、刷新列表后红点又弹出。
  let membership = mem;
  if (!membership && req.role === "admin") {
    db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'admin')")
      .run(topic.id, req.userId);
    membership = getMembership(topic.id, req.userId);
  }
  if (membership) {
    db.prepare("UPDATE topic_members SET last_read_id = ? WHERE topic_id = ? AND user_id = ?")
      .run(maxId.max_id || 0, topic.id, req.userId);
  }
  // dm 同时保持旧的 read 列已读回执（通知对方）
  if (topic.kind === "dm") {
    const unread = db.prepare("SELECT id FROM topic_messages WHERE topic = ? AND user_id != ? AND read = 0").all(name, req.userId).map((r) => r.id);
    if (unread.length) markDmRead(name, unread, req.userId);
  }
  res.json({ ok: true, last_read_id: maxId.max_id || 0 });
});

// 显式标记消息已读（已读回执）。仅 dm 私聊；只影响「对方发来的」消息。
// POST /api/topics/:topic/messages/read  body: { ids: [id, ...] }
router.post("/:topic/messages/read", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  if (topic.kind !== "dm") {
    return res.status(400).json({ error: "Read receipts only supported in direct messages" });
  }
  if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
    return res.status(403).json({ error: "Not a member of this topic" });
  }
  const ids = Array.isArray(req.body.ids)
    ? req.body.ids.map((x) => parseInt(x)).filter((x) => x > 0)
    : [];
  markDmRead(name, ids, req.userId);
  res.json({ ok: true });
});

// 删除单条话题消息（软删除，单向本侧隐藏）
// DELETE /api/topics/:topic/messages/:id
// 规则：会话内任何成员（或管理员）都可在「自己这一侧」删除任意消息——仅插入一条
// (user_id, message_id) 软删除标记，对方视图不受影响、消息仍存于服务器。
// 当该话题「全部成员」都已软删除同一条消息时，才物理清除该消息（双方都删 → 服务器删除）。
router.delete("/:topic/messages/:id", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  if (!getMembership(topic.id, req.userId) && req.role !== "admin") {
    return res.status(403).json({ error: "Not a member of this topic" });
  }
  const msgId = parseInt(req.params.id);
  // 确认消息确实存在（否则报 not found）
  const msg = db.prepare("SELECT id, topic FROM topic_messages WHERE id = ? AND topic = ?").get(msgId, name);
  if (!msg) return res.status(404).json({ error: "Message not found or no permission" });

  // 软删除：本用户标记删除（幂等）
  db.prepare("INSERT OR IGNORE INTO topic_message_deletes (user_id, message_id) VALUES (?, ?)")
    .run(req.userId, msgId);

  // 判断该话题全部成员是否都已软删除此消息 → 物理清除
  const memberCount = db.prepare(
    "SELECT COUNT(*) AS c FROM topic_members WHERE topic_id = ?"
  ).get(topic.id).c;
  const deletedByCount = db.prepare(
    "SELECT COUNT(DISTINCT user_id) AS c FROM topic_message_deletes WHERE message_id = ?"
  ).get(msgId).c;
  let purged = false;
  if (memberCount > 0 && deletedByCount >= memberCount) {
    db.prepare("DELETE FROM topic_message_deletes WHERE message_id = ?").run(msgId);
    db.prepare("DELETE FROM topic_messages WHERE id = ?").run(msgId);
    purged = true;
  }

  // 仅通知「删除者本人」的其他设备隐藏该消息（不影响对方）
  try {
    const { broadcastToUser } = require("../websocket");
    broadcastToUser(req.userId, { type: "message_deleted", topic: name, message_id: msgId });
  } catch (e) { /* WS 模块不可用时忽略 */ }

  res.json({ message: "Message deleted on your side", purged });
});

// 删除整个话题（owner/管理员；同时清理消息）
// DELETE /api/topics/:topic
router.delete("/:topic", authMiddleware, (req, res) => {
  const name = normalizeTopic(req.params.topic);
  if (!name) return res.status(400).json({ error: "Invalid topic name" });
  const db = getDB();
  const topic = getTopic(name);
  if (!topic) return res.status(404).json({ error: "Topic not found" });
  // 设备会话/好友私聊不可删除
  if (topic.kind === "devices") return res.status(400).json({ error: "设备会话不可删除" });
  if (topic.kind === "dm") return res.status(400).json({ error: "私聊会话不可删除" });
  const mem = getMembership(topic.id, req.userId);
  if (!(mem && mem.role === "owner") && req.role !== "admin") {
    return res.status(403).json({ error: "Only the topic owner or admin can delete this topic" });
  }
  db.prepare("DELETE FROM topic_messages WHERE topic = ?").run(name);
  db.prepare("DELETE FROM topics WHERE id = ?").run(topic.id); // 级联删除 members/requests
  res.json({ message: "Topic deleted" });
});

module.exports = router;
module.exports.ensureDmTopic = ensureDmTopic;
module.exports.ensureDeviceTopic = ensureDeviceTopic;
