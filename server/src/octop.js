const { getDB, getSetting } = require("./db");
const crypto = require("crypto");
const http = require("http");
const https = require("https");
const { URL } = require("url");

// 默认 Octop 用户（兼容不带用户名的旧调用地址）
const DEFAULT_OCTOP_USER = "gybeyond";

// ========== 长轮询消息队列 ==========
// 内存消息队列：username -> [{update_id, message}]
const octopMessageQueues = new Map();
let octopUpdateIdCounter = 0;
// 长轮询等待者：username -> [resolve 函数]
const octopPollWaiters = new Map();

// 去重缓存：10 秒内相同内容消息视为重复
const octopDedupCache = new Map();
const OCTOP_DEDUP_WINDOW = 10000;

function isOctopDuplicate(username, text) {
  const key = username + "|" + (text || "");
  const now = Date.now();
  for (const [k, v] of octopDedupCache) {
    if (now - v > OCTOP_DEDUP_WINDOW) octopDedupCache.delete(k);
  }
  if (octopDedupCache.has(key)) {
    console.log("[octop] 重复消息跳过:", username, text?.slice(0, 50));
    return true;
  }
  octopDedupCache.set(key, now);
  return false;
}

// 添加消息到队列（用户在 EchoLink 发的文字/按钮回调，等 Octop 拉取）
function enqueueOctopMessage(username, message) {
  if (!octopMessageQueues.has(username)) {
    octopMessageQueues.set(username, []);
  }
  const queue = octopMessageQueues.get(username);
  octopUpdateIdCounter++;
  let update;
  if (message && message.callback_query) {
    update = {
      update_id: octopUpdateIdCounter,
      callback_query: message.callback_query,
    };
  } else {
    update = {
      update_id: octopUpdateIdCounter,
      message: message,
    };
  }
  queue.push(update);
  if (queue.length > 200) queue.shift();
  const waiters = octopPollWaiters.get(username) || [];
  while (waiters.length > 0) {
    const resolve = waiters.shift();
    resolve([update]);
  }
}

// 长轮询：挂起等待新消息，超时返回空
function getUpdates(username, offset, limit, timeout) {
  return new Promise((resolve) => {
    const queue = octopMessageQueues.get(username) || [];
    const updates = queue.filter((u) => u.update_id > offset).slice(0, limit);
    if (updates.length > 0) {
      resolve(updates);
      return;
    }
    if (!octopPollWaiters.has(username)) {
      octopPollWaiters.set(username, []);
    }
    const waiters = octopPollWaiters.get(username);
    waiters.push(resolve);
    setTimeout(() => {
      const idx = waiters.indexOf(resolve);
      if (idx >= 0) {
        waiters.splice(idx, 1);
        resolve([]);
      }
    }, timeout * 1000);
  });
}

// 获取用户 ID
function getUserIdByUsername(username) {
  const db = getDB();
  const row = db.prepare("SELECT id FROM users WHERE username = ?").get(username);
  return row ? row.id : null;
}

// 确保用户的 Octop 话题存在（kind='octop'）
function ensureUserOctopTopic(userId, username) {
  const db = getDB();
  const topicName = "octop_" + username;
  let topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  if (!topic) {
    db.prepare(
      "INSERT INTO topics (name, owner_id, title, description, kind) VALUES (?, ?, ?, ?, 'octop')"
    ).run(topicName, userId, "Octop", "Octop 通知与交互");
    topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  } else {
    db.prepare("UPDATE topics SET title = ?, description = ?, kind = 'octop' WHERE id = ?").run(
      "Octop", topic.description || "Octop 通知与交互", topic.id
    );
  }
  db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(topic.id, userId);
  return topic;
}

// 生成 token
function generateToken() {
  return crypto.randomBytes(24).toString("hex");
}

// 获取或创建通道
function getOrCreateChannel(userId, name, device) {
  const db = getDB();
  const token = generateToken();
  db.prepare(
    "INSERT OR REPLACE INTO octop_channels (user_id, token, name, device, push_count, enabled) VALUES (?, ?, ?, ?, 0, 1)"
  ).run(userId, token, name || '', device || 'all');
  const row = db.prepare(
    "SELECT user_id, token, name, device, push_count, last_push_at, created_at FROM octop_channels WHERE user_id = ?"
  ).get(userId);
  return row;
}

// 更新通道配置
function updateChannel(userId, updates) {
  const db = getDB();
  const fields = [];
  const values = [];
  if (updates.enabled !== undefined) { fields.push("enabled = ?"); values.push(updates.enabled ? 1 : 0); }
  if (updates.token !== undefined) { fields.push("token = ?"); values.push(updates.token); }
  if (fields.length === 0) return getOrCreateChannel(userId);
  values.push(userId);
  db.prepare(`UPDATE octop_channels SET ${fields.join(", ")} WHERE user_id = ?`).run(...values);
  return db.prepare("SELECT * FROM octop_channels WHERE user_id = ?").get(userId);
}

// 验证 token
function verifyToken(token) {
  if (!token) return null;
  const db = getDB();
  const channel = db.prepare("SELECT * FROM octop_channels WHERE token = ? AND enabled = 1").get(token);
  if (!channel) return null;
  const user = db.prepare("SELECT id, username FROM users WHERE id = ?").get(channel.user_id);
  if (!user) return null;
  return { ...channel, username: user.username };
}

// 删除通道
function deleteChannel(userId) {
  const db = getDB();
  db.prepare("DELETE FROM octop_channels WHERE user_id = ?").run(userId);
}

// 切换启用状态
function toggleChannel(userId, enabled) {
  const db = getDB();
  db.prepare("UPDATE octop_channels SET enabled = ? WHERE user_id = ?").run(enabled ? 1 : 0, userId);
}

// 获取所有通道
function getAllChannels() {
  const db = getDB();
  const rows = db.prepare(
    `SELECT c.id, c.user_id, c.token, c.enabled, c.created_at,
            u.username, u.display_name
     FROM octop_channels c
     JOIN users u ON u.id = c.user_id
     ORDER BY u.username`
  ).all();
  return rows;
}

// 追加 Octop 消息到话题并广播
function appendOctopMessage(username, cardData, text) {
  const db = getDB();
  const octopUser = (username && username.trim()) ? username.trim() : DEFAULT_OCTOP_USER;
  const userId = getUserIdByUsername(octopUser);
  if (!userId) {
    return { delivered: 0, message: null, error: "用户不存在: " + octopUser };
  }

  const msgText = String(text || cardData?.text || "");

  // 10 秒去重
  if (isOctopDuplicate(octopUser, msgText)) {
    return { delivered: 0, message: null, duplicate: true };
  }

  const topic = ensureUserOctopTopic(userId, octopUser);
  const topicName = topic.name;
  const ts = Date.now();
  const cardJson = cardData ? JSON.stringify(cardData) : null;

  const result = db
    .prepare(
      `INSERT INTO topic_messages (topic, user_id, sender_name, title, text, media_type, media_url, media_name, media_size, timestamp, card_data)
       VALUES (?, NULL, 'Octop', ?, ?, 'card', NULL, NULL, 0, ?, ?)`
    )
    .run(topicName, cardData?.title || "Octop", msgText, ts, cardJson);

  const message = {
    id: result.lastInsertRowid,
    topic: topicName,
    title: cardData?.title || "Octop",
    text: msgText,
    sender_name: "Octop",
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
  try {
    broadcastToUser(userId, { type: "topic_message", topic: topicName, data: message });
  } catch (_) {}

  // 更新通道计数
  db.prepare("UPDATE octop_channels SET push_count = push_count + 1, last_push_at = CURRENT_TIMESTAMP WHERE user_id = ?").run(userId);

  // 历史裁剪
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(
    `DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
       SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)`
  ).run(topicName, topicName, maxHistory);

  return { delivered: 1, message };
}

// 编辑已发送的 Octop 消息
function editOctopMessage(messageId, text, buttons, details) {
  const db = getDB();
  const msg = db.prepare("SELECT * FROM topic_messages WHERE id = ?").get(messageId);
  if (!msg) return { error: "消息不存在: " + messageId };
  if (msg.sender_name !== "Octop") return { error: "这不是 Octop 富卡片消息" };

  const newText = text || msg.text;
  let cardData = {};
  try { cardData = msg.card_data ? JSON.parse(msg.card_data) : {}; } catch (_) { cardData = {}; }
  if (buttons) cardData.buttons = buttons;
  if (details) cardData.details = details;
  const cardJson = JSON.stringify(cardData);

  db.prepare("UPDATE topic_messages SET text = ?, card_data = ? WHERE id = ?").run(newText, cardJson, messageId);

  const updatedMsg = { ...msg, text: newText, card_data: cardData };
  const topicName = msg.topic;
  const octopUserName = topicName.startsWith("octop_") ? topicName.substring("octop_".length) : null;
  const uid1 = octopUserName ? (getUserIdByUsername(octopUserName) || 0) : (msg.user_id || 0);
  const { broadcastToUser } = require("./websocket");
  try {
    broadcastToUser(uid1, { type: "message_updated", topic: msg.topic, data: { id: messageId, text: newText, card_data: cardData, streaming: true } });
  } catch (_) {}
  return { ok: true, message_id: messageId, full_text: newText };
}

// 删除 Octop 消息
function deleteOctopMessage(messageId) {
  const db = getDB();
  const msg = db.prepare("SELECT * FROM topic_messages WHERE id = ?").get(messageId);
  if (!msg) return { error: "消息不存在: " + messageId };
  if (msg.sender_name !== "Octop") return { error: "只能删除 Octop 富卡片消息" };
  db.prepare("DELETE FROM topic_messages WHERE id = ?").run(messageId);
  const uid = msg.user_id || 0;
  const topicName = msg.topic;
  const { broadcastToUser } = require("./websocket");
  try {
    broadcastToUser(uid, { type: "message_deleted", topic: topicName, data: { message_id: messageId } });
  } catch (_) {}
  return { ok: true, message_id: messageId };
}

// 流式消息：创建空消息
function startStreamMessage(username, initialText) {
  const db = getDB();
  const octopUser = (username && username.trim()) ? username.trim() : DEFAULT_OCTOP_USER;
  const userId = getUserIdByUsername(octopUser);
  if (!userId) return { delivered: 0, message: null, error: "用户不存在: " + octopUser };

  const topic = ensureUserOctopTopic(userId, octopUser);
  const topicName = topic.name;
  const ts = Date.now();
  const text = initialText || "";
  const cardData = { title: "Octop", text: text, poster: "", details: [], buttons: [], streaming: true };
  const cardJson = JSON.stringify(cardData);

  const result = db
    .prepare(
      `INSERT INTO topic_messages (topic, user_id, sender_name, title, text, media_type, media_url, media_name, media_size, timestamp, card_data)
       VALUES (?, NULL, 'Octop', ?, ?, 'card', NULL, NULL, 0, ?, ?)`
    )
    .run(topicName, "Octop", text, ts, cardJson);

  const message = {
    id: result.lastInsertRowid,
    topic: topicName,
    title: "Octop",
    text: text,
    sender_name: "Octop",
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
  try { broadcastToUser(userId, { type: "topic_message", topic: topicName, data: message }); } catch (_) {}
  return { delivered: 1, message, message_id: result.lastInsertRowid };
}

// 流式追加
function appendStreamMessage(messageId, appendText) {
  const db = getDB();
  const msg = db.prepare("SELECT * FROM topic_messages WHERE id = ?").get(messageId);
  if (!msg) return { error: "消息不存在: " + messageId };
  if (msg.sender_name !== "Octop") return { error: "这不是 Octop 富卡片消息" };

  const newText = (msg.text || "") + (appendText || "");
  let cardData = {};
  try { cardData = msg.card_data ? JSON.parse(msg.card_data) : {}; } catch (_) { cardData = {}; }
  cardData.text = newText;
  const cardJson = JSON.stringify(cardData);

  db.prepare("UPDATE topic_messages SET text = ?, card_data = ? WHERE id = ?").run(newText, cardJson, messageId);

  const topicName = msg.topic;
  const octopUserName = topicName.startsWith("octop_") ? topicName.substring("octop_".length) : null;
  const uid1 = octopUserName ? (getUserIdByUsername(octopUserName) || 0) : (msg.user_id || 0);
  const { broadcastToUser } = require("./websocket");
  try {
    broadcastToUser(uid1, { type: "message_updated", topic: msg.topic, data: { id: messageId, text: newText, card_data: cardData, streaming: true } });
  } catch (_) {}
  return { ok: true, message_id: messageId, full_text: newText };
}

// 流式结束（移除 streaming 标记）
function endStreamMessage(messageId) {
  const db = getDB();
  const msg = db.prepare("SELECT * FROM topic_messages WHERE id = ?").get(messageId);
  if (!msg) return { error: "消息不存在: " + messageId };
  let cardData = {};
  try { cardData = msg.card_data ? JSON.parse(msg.card_data) : {}; } catch (_) { cardData = {}; }
  delete cardData.streaming;
  const cardJson = JSON.stringify(cardData);
  db.prepare("UPDATE topic_messages SET card_data = ? WHERE id = ?").run(cardJson, messageId);

  const topicName = msg.topic;
  const octopUserName = topicName.startsWith("octop_") ? topicName.substring("octop_".length) : null;
  const uid1 = octopUserName ? (getUserIdByUsername(octopUserName) || 0) : (msg.user_id || 0);
  const { broadcastToUser } = require("./websocket");
  try {
    broadcastToUser(uid1, { type: "message_updated", topic: msg.topic, data: { id: messageId, text: msg.text || "", card_data: cardData, streaming: false } });
  } catch (_) {}
  return { ok: true, message_id: messageId };
}

// 按钮回调（用户在 EchoLink 点按钮）
async function callbackButton(username, callbackData, messageId) {
  const userId = getUserIdByUsername(username);
  if (!userId) return { error: "用户不存在: " + username };

  // 如果 MP 设置了 callback_url，也同步回传给 MP（可选）
  const settings = getSetting();
  const octopCallbackUrl = (settings.octop_callback_url || process.env.OCTOP_CALLBACK_URL || "").trim();
  if (octopCallbackUrl) {
    const octopApiKey = (settings.octop_api_key || process.env.OCTOP_API_KEY || "").trim();
    try {
      const base = octopCallbackUrl.replace(/\/+$/, "");
      let url = new URL(base + "/api/octop/receive/callback");
      if (octopApiKey) {
        url.searchParams.set("apikey", octopApiKey);
        url.searchParams.set("request", JSON.stringify({ method: "POST", headers: {}, body: JSON.stringify({ callback_data: callbackData, message_id: messageId }) }));
      }
      const data = JSON.stringify({ callback_data: callbackData, message_id: messageId });
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
        req.on("timeout", () => { req.destroy(); resolve({ error: "timeout" }); });
        req.write(data);
        req.end();
      });
    } catch (e) {
      return { error: "Octop 回调失败: " + e.message };
    }
  }

  // 没有设置 Octop 回调，返回"已收到"
  return { status: 200, body: JSON.stringify({ ok: true, note: "Octop callback received (no callback_url configured)" }) };
}

// 用户在 EchoLink 发文字 → 入队等 Octop 拉取
function sendUserMessageToOctop(username, text) {
  const userId = getUserIdByUsername(username);
  if (!userId) return { error: "用户不存在: " + username };

  enqueueOctopMessage(username, {
    message_id: Date.now(),
    from: { id: userId, username: username },
    text: text,
    date: Math.floor(Date.now() / 1000),
  });
  return { ok: true, queued: true };
}


function getOctopRecentFeed(limit) {
  limit = limit || 50;
  const db = getDB();
  try {
    return db.prepare(
      "SELECT user_id, token, name, device, push_count, last_push_at, created_at FROM octop_channels ORDER BY last_push_at DESC, created_at DESC LIMIT ?"
    ).all(limit);
  } catch (e) {
    console.warn("getOctopRecentFeed failed:", e.message);
    return [];
  }
}

module.exports = {
  DEFAULT_OCTOP_USER,
  getUserIdByUsername,
  ensureUserOctopTopic,
  generateToken,
  getOrCreateChannel,
  updateChannel,
  verifyToken,
  getAllChannels,
  deleteChannel,
  toggleChannel,
  appendOctopMessage,
  editOctopMessage,
  deleteOctopMessage,
  answerCallbackQuery: (id, text, showAlert) => ({ ok: true, callback_query_id: id, text: text || "", show_alert: !!showAlert }),
  callbackButton,
  sendUserMessageToOctop,
  enqueueOctopMessage,
  getUpdates,
  startStreamMessage,
  appendStreamMessage,
  endStreamMessage,
  getOctopRecentFeed,
};
