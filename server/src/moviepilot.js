const { getDB, getSetting } = require("./db");
const crypto = require("crypto");
const http = require("http");
const https = require("https");
const { URL } = require("url");

// 默认 MP 用户（兼容旧的 /webhook/moviepilot 不带用户名的地址）
const DEFAULT_MP_USER = "gybeyond";

// ========== 长轮询消息队列 ==========
// 内存消息队列：username -> [{update_id, message}]
const mpMessageQueues = new Map();
let mpUpdateIdCounter = 0;
// 长轮询等待者：username -> [resolve函数]
const mpPollWaiters = new Map();

// MP 消息去重缓存：防止 MP 端重复发送导致数据库里有重复消息
// key = username + "|" + text，value = timestamp
const mpDedupCache = new Map();
const MP_DEDUP_WINDOW = 10000; // 10秒内相同内容的消息视为重复

function isMPDuplicate(username, text) {
  const key = username + "|" + (text || "");
  const now = Date.now();
  // 清理过期缓存
  for (const [k, v] of mpDedupCache) {
    if (now - v > MP_DEDUP_WINDOW) mpDedupCache.delete(k);
  }
  if (mpDedupCache.has(key)) {
    console.log("[moviepilot] 重复消息跳过:", username, text?.slice(0, 50));
    return true;
  }
  mpDedupCache.set(key, now);
  return false;
}

// 添加消息到队列（用户发的消息、按钮回调都走这里，等 MP 长轮询拉取）
function enqueueMPMessage(username, message) {
  if (!mpMessageQueues.has(username)) {
    mpMessageQueues.set(username, []);
  }
  const queue = mpMessageQueues.get(username);
  mpUpdateIdCounter++;
  // 如果 message 里有 callback_query，把它提到顶层（Telegram 格式：callback_query 与 message 同级）
  let update;
  if (message && message.callback_query) {
    update = {
      update_id: mpUpdateIdCounter,
      callback_query: message.callback_query,
    };
  } else {
    update = {
      update_id: mpUpdateIdCounter,
      message: message,
    };
  }
  queue.push(update);
  // 限制队列长度，最多保留 200 条
  if (queue.length > 200) {
    queue.shift();
  }
  // 通知等待中的长轮询
  const waiters = mpPollWaiters.get(username) || [];
  while (waiters.length > 0) {
    const resolve = waiters.shift();
    resolve([update]);
  }
}

// 长轮询获取消息（类似 Telegram getUpdates）
// 有消息立即返回，没消息挂起 timeout 秒后返回空
function getUpdates(username, offset, limit, timeout) {
  return new Promise((resolve) => {
    const queue = mpMessageQueues.get(username) || [];
    // 找到 offset 之后的消息
    const updates = queue.filter((u) => u.update_id > offset).slice(0, limit);
    if (updates.length > 0) {
      resolve(updates);
      return;
    }
    // 没有消息，挂起等待
    if (!mpPollWaiters.has(username)) {
      mpPollWaiters.set(username, []);
    }
    const waiters = mpPollWaiters.get(username);
    waiters.push(resolve);
    // 超时后返回空
    setTimeout(() => {
      const idx = waiters.indexOf(resolve);
      if (idx >= 0) {
        waiters.splice(idx, 1);
        resolve([]);
      }
    }, timeout * 1000);
  });
}

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

// 更新用户的 MP 通道配置（只保留 token 和 enabled）
function updateChannel(userId, updates) {
  const db = getDB();
  const fields = [];
  const values = [];
  if (updates.enabled !== undefined) { fields.push("enabled = ?"); values.push(updates.enabled ? 1 : 0); }
  if (updates.token !== undefined) { fields.push("token = ?"); values.push(updates.token); }
  if (fields.length === 0) return getOrCreateChannel(userId);
  values.push(userId);
  db.prepare(`UPDATE moviepilot_channels SET ${fields.join(", ")} WHERE user_id = ?`).run(...values);
  return db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
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
    SELECT c.id, c.user_id, c.token, c.enabled, c.created_at,
           u.username, u.display_name
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

  const msgText = String(text || cardData?.text || "").slice(0, 2000);

  // 消息去重：10秒内相同用户+相同内容的消息视为重复，防止 MP 端重复发送
  if (isMPDuplicate(mpUser, msgText)) {
    return { delivered: 0, message: null, duplicate: true };
  }

  const topic = ensureUserMoviepilotTopic(userId, mpUser);
  const topicName = topic.name;
  const ts = Date.now();
  const cardJson = cardData ? JSON.stringify(cardData) : null;

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

  const { broadcastToUser } = require("./websocket");
  // 只推一次：broadcastToUser 覆盖用户所有设备（含未订阅话题的设备），避免与 publishToTopic 重复推送导致多条通知
  try { broadcastToUser(userId, { type: "topic_message", topic: topicName, data: message }); } catch (_) {}

  // 清理旧消息
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(
    `DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
       SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)`
  ).run(topicName, topicName, maxHistory);

  return { delivered: 1, message };
}

// 向 MP 发送 HTTP 请求（按钮回调或用户消息）
// MP 地址和 API Key 从全局设置中读取（管理员在服务器设置中配置）
function _postToMP(path, body) {
  const db = getDB();
  const settings = getSetting ? getSetting() : {};
  // 从全局设置中读取 MP 配置
  const mpBase = (settings.moviepilot_callback_url || process.env.MP_CALLBACK_URL || "").trim();
  if (!mpBase) {
    return { error: "未配置 MoviePilot 回调地址，请在服务器设置中填写 moviepilot_callback_url" };
  }
  const mpApiKey = (settings.moviepilot_api_key || process.env.MP_API_KEY || "").trim();
  let url;
  try {
    const base = mpBase.replace(/\/+$/, "");
    url = new URL(base + path);
    if (mpApiKey) {
      url.searchParams.set("apikey", mpApiKey);
      url.searchParams.set("request", JSON.stringify({ method: "POST", headers: {}, body: JSON.stringify(body) }));
    }
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

// 按钮点击回调，存入消息队列，等 MP 长轮询拉取
async function callbackButton(username, callbackData, messageId) {
  const userId = getUserIdByUsername(username);
  if (!userId) return { error: "用户不存在: " + username };

  // 存入消息队列（格式跟 Telegram callback_query 一致）
  enqueueMPMessage(username, {
    callback_query: {
      id: Date.now().toString(),
      from: { id: userId, username: username },
      message: { message_id: messageId },
      data: callbackData,
    },
  });
  return { ok: true, queued: true };
}

// 用户在 EchoLink 发文字给 MP，存入消息队列，等 MP 长轮询拉取
async function sendUserMessageToMP(username, text) {
  const userId = getUserIdByUsername(username);
  if (!userId) return { error: "用户不存在: " + username };

  // 存入消息队列（格式跟 Telegram message 一致）
  enqueueMPMessage(username, {
    message_id: Date.now(),
    from: { id: userId, username: username },
    text: text,
    date: Math.floor(Date.now() / 1000),
  });
  return { ok: true, queued: true };
}

module.exports = {
  DEFAULT_MP_USER,
  getUserIdByUsername,
  ensureUserMoviepilotTopic,
  generateToken,
  getOrCreateChannel,
  updateChannel,
  verifyToken,
  getAllChannels,
  deleteChannel,
  toggleChannel,
  appendMoviepilotMessage,
  callbackButton,
  sendUserMessageToMP,
  enqueueMPMessage,
  getUpdates,
};
