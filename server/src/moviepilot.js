const { getDB, getSetting } = require("./db");
const crypto = require("crypto");
const http = require("http");
const https = require("https");
const { URL } = require("url");

// 默认 MP 用户（兼容旧的 /webhook/moviepilot 不带用户名的地址）
const DEFAULT_MP_USER = "gybeyond";

// 根据用户名查找用户 id
function getUserIdByUsername(username) {
  const db = getDB();
  const row = db.prepare("SELECT id FROM users WHERE username = ?").get(username);
  return row ? row.id : null;
}

// 确保某用户的 MP 话题存在，话题名为 moviepilot_<username>，该用户是唯一成员。
function ensureUserMoviepilotTopic(userId, username) {
  const db = getDB();
  const topicName = "moviepilot_" + username;
  let topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  if (!topic) {
    db.prepare(
      "INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, ?, 'moviepilot')"
    ).run(topicName, userId, "MoviePilot", "MoviePilot 通知与远程控制");
    topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  } else {
    db.prepare("UPDATE topics SET title = ?, description = ?, kind = 'moviepilot' WHERE id = ?").run(
      "MoviePilot", topic.description || "MoviePilot 通知与远程控制", topic.id
    );
  }
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userId);
  return topic;
}

// 生成通道 token
function generateToken() {
  return crypto.randomBytes(24).toString("hex");
}

// 获取用户的 MP 通道，不存在则创建
function getOrCreateChannel(userId) {
  const db = getDB();
  let channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
  if (!channel) {
    const token = generateToken();
    db.prepare("INSERT INTO moviepilot_channels (user_id, token, enabled) VALUES (?, ?, 1)").run(userId, token);
    channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
  }
  return channel;
}

// 根据 token 验证通道，返回通道信息
function verifyToken(token) {
  if (!token) return null;
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE token = ? AND enabled = 1").get(token);
  if (!channel) return null;
  const user = db.prepare("SELECT id, username FROM users WHERE id = ?").get(channel.user_id);
  if (!user) return null;
  return { ...channel, username: user.username };
}

// 获取所有用户的 MP 通道（管理员用）
function getAllChannels() {
  const db = getDB();
  const rows = db.prepare(`
    SELECT c.*, u.username, u.display_name
    FROM moviepilot_channels c
    JOIN users u ON u.id = c.user_id
    ORDER BY u.username
  `).all();
  return rows;
}

// 删除用户的 MP 通道
function deleteChannel(userId) {
  const db = getDB();
  db.prepare("DELETE FROM moviepilot_channels WHERE user_id = ?").run(userId);
}

// 切换通道启用状态
function toggleChannel(userId, enabled) {
  const db = getDB();
  db.prepare("UPDATE moviepilot_channels SET enabled = ? WHERE user_id = ?").run(enabled ? 1 : 0, userId);
}

// 写入一条 MP 卡片消息，并实时推送给目标用户的所有在线设备。
// cardData: { title, poster, details: [{key,value}], buttons: [{text,callback_data}], text }
// 返回 { delivered, message } 或 { delivered:0, error }
function appendMoviepilotMessage(username, cardData, text) {
  const db = getDB();
  const mpUser = (username && username.trim()) ? username.trim() : DEFAULT_MP_USER;
  const userId = getUserIdByUsername(mpUser);
  if (!userId) {
    return { delivered: 0, message: null, error: "用户不存在: " + mpUser };
  }

  const topic = ensureUserMoviepilotTopic(userId, mpUser);
  const topicName = topic.name;
  const ts = Date.now();
  const cardJson = cardData ? JSON.stringify(cardData) : null;
  const msgText = String(text || cardData?.text || "").slice(0, 2000);

  const result = db
    .prepare(
      `INSERT INTO topic_messages (topic, user_id, sender_name, title, text, media_type, media_url, media_name, media_size, timestamp, card_data)
       VALUES (?, NULL, 'MoviePilot', ?, ?, 'card', NULL, NULL, 0, ?, ?)`
    )
    .run(topicName, cardData?.title || "MoviePilot", msgText, ts, cardJson);

  const message = {
    id: result.lastInsertRowid,
    topic: topicName,
    title: cardData?.title || "MoviePilot",
    text: msgText,
    sender_name: "MoviePilot",
    sender_display_name: null,
    sender_avatar: null,
    user_id: 0,
    timestamp: ts,
    device_id: null,
    device_name: null,
    media_type: "card",
    media_url: null,
    media_name: null,
    media_size: 0,
    card_data: cardData,
    peer_avatar: null,
  };

  const { broadcastToUser, publishToTopic } = require("./websocket");
  try { broadcastToUser(userId, { type: "topic_message", topic: topicName, data: message }); } catch (_) {}
  try { publishToTopic(topicName, message, {}); } catch (_) {}

  // 清理旧消息
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(
    `DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
       SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)`
  ).run(topicName, topicName, maxHistory);

  return { delivered: 1, message };
}

// 向 MP 插件发送 HTTP 请求（按钮回调或用户消息）
function _postToMP(path, body) {
  const mpBase = getSetting("moviepilot_callback_url") || "";
  if (!mpBase) {
    return { error: "未配置 MoviePilot 回调地址，请在管理员设置页填写" };
  }
  let url;
  try {
    const base = mpBase.replace(/\/+$/, "");
    url = new URL(base + path);
  } catch (e) {
    return { error: "MoviePilot 地址格式错误: " + e.message };
  }

  const data = JSON.stringify(body);
  const options = {
    hostname: url.hostname,
    port: url.port || (url.protocol === "https:" ? 443 : 80),
    path: url.pathname + url.search,
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(data),
    },
    timeout: 10000,
  };

  return new Promise((resolve) => {
    const lib = url.protocol === "https:" ? https : http;
    const req = lib.request(options, (res) => {
      let respData = "";
      res.on("data", (chunk) => { respData += chunk; });
      res.on("end", () => {
        resolve({ status: res.statusCode, body: respData });
      });
    });
    req.on("error", (e) => { resolve({ error: e.message }); });
    req.on("timeout", () => { req.destroy(); resolve({ error: "请求超时" }); });
    req.write(data);
    req.end();
  });
}

// 按钮点击回调，转发给 MP 插件
async function callbackButton(username, callbackData, messageId) {
  return await _postToMP("/api/v1/plugin/echolink/callback", {
    username,
    callback_data: callbackData,
    message_id: messageId,
    timestamp: Date.now(),
  });
}

// 用户在 EchoLink 发文字给 MP
async function sendUserMessageToMP(username, text) {
  return await _postToMP("/api/v1/plugin/echolink/message", {
    username,
    text,
    timestamp: Date.now(),
  });
}

module.exports = {
  DEFAULT_MP_USER,
  getUserIdByUsername,
  ensureUserMoviepilotTopic,
  generateToken,
  getOrCreateChannel,
  verifyToken,
  getAllChannels,
  deleteChannel,
  toggleChannel,
  appendMoviepilotMessage,
  callbackButton,
  sendUserMessageToMP,
};
