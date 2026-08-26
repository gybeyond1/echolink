const { getDB } = require("./db");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

// 留言板 Webhook 的全局话题名（topics.name 唯一）。所有授权账号都是它的成员，
// 留言统一汇进这一个会话，不与通知/私聊/设备会话混淆。
const TOPIC_NAME = "messagewall";

// 将留言板图片（base64 data URI）落盘到 data/uploads，返回媒体字段。
// 仅支持 image/jpeg | image/png | image/webp；失败时抛错由调用方决定如何响应。
function saveMessagewallImage(dataUri) {
  if (!dataUri) return null;
  const m = /^data:image\/(jpeg|png|webp);base64,(.+)$/i.exec(dataUri.trim());
  if (!m) throw new Error("invalid image (expected data:image/jpeg|png|webp;base64,...)");
  const sub = m[1].toLowerCase();
  const ext = sub === "jpeg" ? "jpg" : sub;
  let buf;
  try {
    buf = Buffer.from(m[2], "base64");
  } catch (_) {
    throw new Error("image base64 decode failed");
  }
  if (!buf || buf.length === 0) throw new Error("empty image");
  if (buf.length > 10 * 1024 * 1024) throw new Error("image too large (>10MB)");

  const dataDir = path.dirname(process.env.DB_PATH || "./data/echolink.db");
  const uploadsDir = path.join(dataDir, "uploads");
  if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
  const fname = crypto.randomBytes(12).toString("hex") + "." + ext;
  fs.writeFileSync(path.join(uploadsDir, fname), buf);
  return {
    media_type: "image",
    media_url: "/uploads/" + fname,
    media_name: fname,
    media_size: buf.length,
  };
}

// 将留言板语音（base64 data URI）落盘到 data/uploads，返回媒体字段。
// 支持 audio/webm | audio/mp4 | audio/ogg | audio/amr | audio/x-m4a；失败时抛错。
function saveMessagewallVoice(dataUri) {
  if (!dataUri) return null;
  const m = /^data:audio\/(webm|mp4|ogg|amr|x-m4a|mpeg|wav)(?:;.*)?;base64,(.+)$/i.exec(dataUri.trim());
  if (!m) throw new Error("invalid voice (expected data:audio/...;base64,...)");
  let sub = m[1].toLowerCase();
  const ext = sub === "x-m4a" ? "m4a" : sub === "mpeg" ? "mp3" : sub;
  let buf;
  try {
    buf = Buffer.from(m[2], "base64");
  } catch (_) {
    throw new Error("voice base64 decode failed");
  }
  if (!buf || buf.length === 0) throw new Error("empty voice");
  if (buf.length > 20 * 1024 * 1024) throw new Error("voice too large (>20MB)");

  const dataDir = path.dirname(process.env.DB_PATH || "./data/echolink.db");
  const uploadsDir = path.join(dataDir, "uploads");
  if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
  const fname = crypto.randomBytes(12).toString("hex") + "." + ext;
  fs.writeFileSync(path.join(uploadsDir, fname), buf);
  return {
    media_type: "voice",
    media_url: "/uploads/" + fname,
    media_name: fname,
    media_size: buf.length,
  };
}

// 读取「接收留言板通知的账号」配置（settings.messagewall_targets，JSON 字符串数组）。
// 为空 / 缺失 → 默认对【全部账号】开放（开箱即用）。
function getMessagewallTargets() {
  const db = getDB();
  const row = db.prepare("SELECT value FROM settings WHERE key = 'messagewall_targets'").get();
  if (!row || !row.value) return [];
  try {
    const arr = JSON.parse(row.value);
    return Array.isArray(arr) ? arr : [];
  } catch (_) {
    return [];
  }
}

// 解析应接收留言板消息的用户 id 列表
function resolveMessagewallUserIds() {
  const db = getDB();
  const targets = getMessagewallTargets();
  if (targets.length === 0) {
    return db.prepare("SELECT id FROM users").all().map((r) => r.id);
  }
  const placeholders = targets.map(() => "?").join(",");
  return db
    .prepare(`SELECT id FROM users WHERE username IN (${placeholders})`)
    .all(...targets)
    .map((r) => r.id);
}

// 确保留言板话题存在，并把成员同步为 targetIds（不在其中的成员移除）。
// description 为首次建话题时的描述（来自留言板应用的 sourceDesc）。
function ensureMessagewallTopic(targetIds, description) {
  const db = getDB();
  let topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(TOPIC_NAME);
  if (!topic) {
    const ownerId = targetIds.length > 0 ? targetIds[0] : 1;
    db.prepare(
      "INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, ?, 'messagewall')"
    ).run(TOPIC_NAME, ownerId, "留言板", description || "门边留言板推送的访客留言");
    topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(TOPIC_NAME);
  } else {
    db.prepare("UPDATE topics SET title = ?, description = ?, kind = 'messagewall' WHERE id = ?").run(
      "留言板",
      description || topic.description || "门边留言板推送的访客留言",
      topic.id
    );
  }

  const existing = db
    .prepare("SELECT user_id FROM topic_members WHERE topic_id = ?")
    .all(topic.id)
    .map((r) => r.user_id);
  const toAdd = targetIds.filter((id) => !existing.includes(id));
  const toRemove = existing.filter((id) => !targetIds.includes(id));
  for (const id of toAdd) {
    db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(
      topic.id,
      id
    );
  }
  for (const id of toRemove) {
    db.prepare("DELETE FROM topic_members WHERE topic_id = ? AND user_id = ?").run(topic.id, id);
  }
  return topic;
}

// 写入一条留言板消息，并实时推送给所有目标账号的设备。
// title = 留言人（如「张三（13800138000）」），text = 留言正文（可空），
// imageDataUri = 可选 base64 图片，voiceDataUri = 可选 base64 语音。
// 同时有图和语音时优先展示语音（表结构单 media 字段限制）。
// 返回 { delivered: 账号数, message } 或 { delivered:0, message:null, error }
function appendMessagewallMessage(title, text, description, imageDataUri, voiceDataUri) {
  const db = getDB();
  const targetIds = resolveMessagewallUserIds();
  if (targetIds.length === 0) {
    return { delivered: 0, message: null };
  }

  const topic = ensureMessagewallTopic(targetIds, description);
  const ts = Date.now();
  const t = String(title || "").slice(0, 500);
  const c = String(text || "").slice(0, 2000);

  // 媒体（可选）：语音优先，其次图片，均为 base64 data URI → 落盘到 data/uploads
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
       VALUES (?, NULL, '留言板', ?, ?, ?, ?, ?, ?, ?)`
    )
    .run(TOPIC_NAME, t, c, media.media_type, media.media_url, media.media_name || null, media.media_size, ts);

  const message = {
    id: result.lastInsertRowid,
    topic: TOPIC_NAME,
    title: t,
    text: c,
    sender_name: "留言板",
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

  // 推送给每个目标账号的所有在线设备（安卓 / Windows / WebUI 都覆盖）
  const { broadcastToUser, publishToTopic } = require("./websocket");
  for (const uid of targetIds) {
    try {
      broadcastToUser(uid, { type: "topic_message", topic: TOPIC_NAME, data: message });
    } catch (_) {
      /* WS 不可用忽略 */
    }
  }
  // 同时经话题订阅广播（若有连接已显式订阅该话题）
  try {
    publishToTopic(TOPIC_NAME, message, {});
  } catch (_) {
    /* 忽略 */
  }

  // 清理旧消息（与全局上限一致）
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(
    `DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
       SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)`
  ).run(TOPIC_NAME, TOPIC_NAME, maxHistory);

  return { delivered: targetIds.length, message };
}

module.exports = {
  TOPIC_NAME,
  getMessagewallTargets,
  resolveMessagewallUserIds,
  ensureMessagewallTopic,
  appendMessagewallMessage,
};