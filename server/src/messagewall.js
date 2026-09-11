const { getDB } = require("./db");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

// 默认留言板用户（兼容旧的 /webhook/messagewall 不带用户名的地址）
const DEFAULT_WALL_USER = "gybeyond";

// 将留言板图片（base64 data URI）落盘到 data/uploads，返回媒体字段。
function saveMessagewallImage(dataUri) {
  if (!dataUri) return null;
  const m = /^data:image\/(jpeg|png|webp);base64,(.+)$/i.exec(dataUri.trim());
  if (!m) throw new Error("invalid image (expected data:image/jpeg|png|webp;base64,...)");
  const sub = m[1].toLowerCase();
  const ext = sub === "jpeg" ? "jpg" : sub;
  let buf;
  try { buf = Buffer.from(m[2], "base64"); } catch (_) { throw new Error("image base64 decode failed"); }
  if (!buf || buf.length === 0) throw new Error("empty image");
  if (buf.length > 10 * 1024 * 1024) throw new Error("image too large (>10MB)");

  const dataDir = path.dirname(process.env.DB_PATH || "./data/echolink.db");
  const uploadsDir = path.join(dataDir, "uploads");
  if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
  const fname = crypto.randomBytes(12).toString("hex") + "." + ext;
  fs.writeFileSync(path.join(uploadsDir, fname), buf);
  return { media_type: "image", media_url: "/uploads/" + fname, media_name: fname, media_size: buf.length };
}

// 将留言板语音（base64 data URI）落盘到 data/uploads，返回媒体字段。
function saveMessagewallVoice(dataUri) {
  if (!dataUri) return null;
  const m = /^data:audio\/(webm|mp4|ogg|amr|x-m4a|mpeg|wav)(?:;.*)?;base64,(.+)$/i.exec(dataUri.trim());
  if (!m) throw new Error("invalid voice (expected data:audio/...;base64,...)");
  let sub = m[1].toLowerCase();
  const ext = sub === "x-m4a" ? "m4a" : sub === "mpeg" ? "mp3" : sub;
  let buf;
  try { buf = Buffer.from(m[2], "base64"); } catch (_) { throw new Error("voice base64 decode failed"); }
  if (!buf || buf.length === 0) throw new Error("empty voice");
  if (buf.length > 20 * 1024 * 1024) throw new Error("voice too large (>20MB)");

  const dataDir = path.dirname(process.env.DB_PATH || "./data/echolink.db");
  const uploadsDir = path.join(dataDir, "uploads");
  if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
  const fname = crypto.randomBytes(12).toString("hex") + "." + ext;
  fs.writeFileSync(path.join(uploadsDir, fname), buf);
  return { media_type: "voice", media_url: "/uploads/" + fname, media_name: fname, media_size: buf.length };
}

// 根据用户名查找用户 id
function getUserIdByUsername(username) {
  const db = getDB();
  const row = db.prepare("SELECT id FROM users WHERE username = ?").get(username);
  return row ? row.id : null;
}

// 确保某用户的留言板话题存在，话题名为 messagewall_<username>，该用户是唯一成员。
function ensureUserMessagewallTopic(userId, username, description) {
  const db = getDB();
  const topicName = "messagewall_" + username;
  let topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  if (!topic) {
    db.prepare(
      "INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, ?, 'messagewall')"
    ).run(topicName, userId, "留言板", description || "门边留言板推送的访客留言");
    topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  } else {
    db.prepare("UPDATE topics SET title = ?, description = ?, kind = 'messagewall' WHERE id = ?").run(
      "留言板", description || topic.description || "门边留言板推送的访客留言", topic.id
    );
  }
  // 确保该用户是成员
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userId);
  return topic;
}

// 写入一条留言板消息，并实时推送给目标用户的所有在线设备。
// username 决定消息进入哪个用户的留言板话题；为空则用默认 gybeyond。
// 返回 { delivered: 账号数, message } 或 { delivered:0, message:null, error }
function appendMessagewallMessage(title, text, description, imageDataUri, voiceDataUri, username) {
  const db = getDB();
  const wallUser = (username && username.trim()) ? username.trim() : DEFAULT_WALL_USER;
  const userId = getUserIdByUsername(wallUser);
  if (!userId) {
    return { delivered: 0, message: null, error: "用户不存在: " + wallUser };
  }

  const topic = ensureUserMessagewallTopic(userId, wallUser, description);
  const topicName = topic.name;
  const ts = Date.now();
  const t = String(title || "").slice(0, 500);
  const c = String(text || "").slice(0, 2000);

  // 媒体（可选）：语音优先，其次图片
  let media = { media_type: "text", media_url: null, media_name: null, media_size: 0 };
  if (voiceDataUri) {
    try {
      media = saveMessagewallVoice(voiceDataUri);
      if (!media) media = { media_type: "text", media_url: null, media_name: null, media_size: 0 };
    } catch (e) {
      return { delivered: 0, message: null, error: e.message };
    }
  } else if (imageDataUri) {
    try {
      media = saveMessagewallImage(imageDataUri);
      if (!media) media = { media_type: "text", media_url: null, media_name: null, media_size: 0 };
    } catch (e) {
      return { delivered: 0, message: null, error: e.message };
    }
  }

  const result = db
    .prepare(
      `INSERT INTO topic_messages (topic, user_id, sender_name, title, text, media_type, media_url, media_name, media_size, timestamp)
       VALUES (?, NULL, ?, '', ?, ?, ?, ?, ?, ?)`
    )
    .run(topicName, t, c, media.media_type, media.media_url, media.media_name || null, media.media_size, ts);

  const message = {
    id: result.lastInsertRowid,
    topic: topicName,
    title: "",
    text: c,
    sender_name: t,
    sender_display_name: null,
    sender_avatar: null,
    user_id: 0,
    timestamp: ts,
    device_id: null,
    device_name: null,
    media_type: media.media_type,
    media_url: media.media_url,
    media_name: media.media_name,
    media_size: media.media_size,
    peer_avatar: null,
  };

  // 推送给目标用户的所有在线设备（只用 broadcastToUser，避免和 publishToTopic 重复导致双通知）
  const { broadcastToUser } = require("./websocket");
  try {
    broadcastToUser(userId, { type: "topic_message", topic: topicName, data: message });
  } catch (_) { /* WS 不可用忽略 */ }

  // 清理旧消息
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(
    `DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
       SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)`
  ).run(topicName, topicName, maxHistory);

  return { delivered: 1, message };
}

// 获取开启了留言板功能的用户列表
function getMessagewallEnabledUsers() {
  const db = getDB();
  try {
    const row = db.prepare("SELECT value FROM settings WHERE key = 'messagewall_enabled_users'").get();
    if (row && row.value) {
      return JSON.parse(row.value);
    }
  } catch (_) {}
  // 默认所有用户都开启
  const users = db.prepare("SELECT username FROM users").all();
  return users.map(u => u.username);
}

// 设置开启了留言板功能的用户列表
function setMessagewallEnabledUsers(usernames) {
  const db = getDB();
  const val = JSON.stringify(usernames || []);
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('messagewall_enabled_users', ?)").run(val);
}

// 迁移旧的 messagewall 话题 → messagewall_gybeyond
function migrateLegacyMessagewallTopic() {
  const db = getDB();
  const oldTopic = db.prepare("SELECT * FROM topics WHERE name = 'messagewall'").get();
  if (!oldTopic) return;
  const newName = "messagewall_" + DEFAULT_WALL_USER;
  const existing = db.prepare("SELECT id FROM topics WHERE name = ?").get(newName);
  if (existing) {
    // 新话题已存在，把旧话题的消息移过去然后删除旧话题
    db.prepare("UPDATE topic_messages SET topic = ? WHERE topic = 'messagewall'").run(newName);
    db.prepare("DELETE FROM topic_members WHERE topic_id = ?").run(oldTopic.id);
    db.prepare("DELETE FROM topics WHERE id = ?").run(oldTopic.id);
    console.log("[migrate] 旧 messagewall 话题消息已合并到 " + newName);
  } else {
    db.prepare("UPDATE topics SET name = ? WHERE id = ?").run(newName, oldTopic.id);
    db.prepare("UPDATE topic_messages SET topic = ? WHERE topic = 'messagewall'").run(newName);
    console.log("[migrate] 旧 messagewall 话题已重命名为 " + newName);
  }
}

module.exports = {
  DEFAULT_WALL_USER,
  getUserIdByUsername,
  ensureUserMessagewallTopic,
  appendMessagewallMessage,
  getMessagewallEnabledUsers,
  setMessagewallEnabledUsers,
  migrateLegacyMessagewallTopic,
};
