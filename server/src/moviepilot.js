/**
 * MoviePilot 通道模块
 * 
 * 支持两种模式：
 * 1. direct（直接模式）：通过 WebHook 直接与 MP 通信
 * 2. telegram（Telegram 桥接模式）：通过 Telegram Bot API 中转，复用 MP 的 Telegram 渠道
 */

const crypto = require("crypto");
const https = require("https");
const http = require("http");
const { URL } = require("url");

const { getDB, getUserIdByUsername } = require("./db");
const telegramBridge = require("./telegram_bridge");

const DEFAULT_MP_USER = "gybeyond";

/**
 * 生成随机 Token
 */
function generateToken() {
  return crypto.randomBytes(32).toString("hex");
}

/**
 * 确保用户的 MoviePilot 话题存在
 */
function ensureUserMoviepilotTopic(username) {
  const db = getDB();
  const topicName = `moviepilot_${username}`;
  let topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
  if (!topic) {
    const userId = getUserIdByUsername(username);
    db.prepare("INSERT INTO topics (name, title, owner_id) VALUES (?, ?, ?)").run(
      topicName,
      `MoviePilot - ${username}`,
      userId
    );
    topic = db.prepare("SELECT * FROM topics WHERE name = ?").get(topicName);
    // 把用户加入话题
    if (userId) {
      db.prepare("INSERT OR IGNORE INTO topic_members (topic_id, user_id, role) VALUES (?, ?, 'member')").run(
        topic.id,
        userId
      );
    }
  }
  return topic;
}

/**
 * 追加 MoviePilot 消息到用户的话题
 */
function appendMoviepilotMessage(username, cardData, text) {
  const db = getDB();
  const topic = ensureUserMoviepilotTopic(username);
  const userId = getUserIdByUsername(username);

  // 构建消息文本
  let messageText = text || "";
  if (cardData && cardData.details && cardData.details.length > 0) {
    const detailsText = cardData.details.map(d => `${d.label}：${d.value}`).join("\n");
    if (messageText) {
      messageText += "\n\n" + detailsText;
    } else {
      messageText = detailsText;
    }
  }

  // 插入消息
  const result = db.prepare(
    "INSERT INTO messages (topic_id, sender_id, sender_name, text, image) VALUES (?, ?, ?, ?, ?)"
  ).run(
    topic.id,
    null,
    "MoviePilot",
    messageText,
    cardData && cardData.poster ? cardData.poster : null
  );

  // 如果有交互按钮，保存到消息的扩展字段（这里简化处理，直接把按钮信息附加到文本后面）
  if (cardData && cardData.buttons && cardData.buttons.length > 0) {
    const buttonsText = "\n\n[按钮]\n" + cardData.buttons.map((b, i) => `${i + 1}. ${b.text}`).join("\n");
    db.prepare("UPDATE messages SET text = text || ? WHERE id = ?").run(buttonsText, result.lastInsertRowid);
  }

  return result.lastInsertRowid;
}

/**
 * 获取所有 MP 通道
 */
function getAllChannels() {
  const db = getDB();
  return db.prepare(`
    SELECT mc.*, u.username, u.display_name 
    FROM moviepilot_channels mc 
    LEFT JOIN users u ON mc.user_id = u.id 
    ORDER BY mc.created_at DESC
  `).all();
}

/**
 * 获取用户的 MP 通道
 */
function getChannelByUserId(userId) {
  const db = getDB();
  return db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
}

/**
 * 创建用户的 MP 通道
 */
function createChannel(userId) {
  const db = getDB();
  const user = db.prepare("SELECT username, display_name FROM users WHERE id = ?").get(userId);
  if (!user) throw new Error("用户不存在");

  const existing = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
  if (existing) return existing;

  const token = generateToken();
  db.prepare(`
    INSERT INTO moviepilot_channels (user_id, username, display_name, token, enabled) 
    VALUES (?, ?, ?, ?, 1)
  `).run(userId, user.username, user.display_name || user.username, token);

  return db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
}

/**
 * 更新用户的 MP 通道配置
 */
function updateChannel(userId, updates) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
  if (!channel) throw new Error("通道不存在");

  const allowedFields = [
    "enabled", "public_base_url", "mp_api_key", "callback_url",
    "channel_mode", "telegram_bot_token", "telegram_chat_id",
    "telegram_proxy_enabled", "telegram_proxy_type", "telegram_proxy_host",
    "telegram_proxy_port", "telegram_proxy_username", "telegram_proxy_password"
  ];

  const sets = [];
  const values = [];
  for (const field of allowedFields) {
    if (updates[field] !== undefined) {
      sets.push(`${field} = ?`);
      values.push(updates[field]);
    }
  }

  if (sets.length > 0) {
    values.push(userId);
    db.prepare(`UPDATE moviepilot_channels SET ${sets.join(", ")} WHERE user_id = ?`).run(...values);
  }

  // 如果切换到 Telegram 模式且配置完整，启动长轮询
  const updatedChannel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
  if (updatedChannel.channel_mode === "telegram" && 
      updatedChannel.telegram_bot_token && 
      updatedChannel.telegram_chat_id &&
      updatedChannel.enabled) {
    const user = db.prepare("SELECT username FROM users WHERE id = ?").get(userId);
    if (user) {
      // 先停止旧的，再启动新的
      telegramBridge.stopPolling(userId);
      telegramBridge.startPolling(userId, user.username, updatedChannel).catch((e) => {
        console.error(`[MoviePilot] 启动用户 ${user.username} 的 Telegram 监控失败:`, e.message);
      });
    }
  } else if (updatedChannel.channel_mode === "direct" || !updatedChannel.enabled) {
    // 切换到直接模式或禁用时，停止 Telegram 长轮询
    telegramBridge.stopPolling(userId);
  }

  return updatedChannel;
}

/**
 * 重置用户的 Token
 */
function resetToken(userId) {
  const db = getDB();
  const token = generateToken();
  db.prepare("UPDATE moviepilot_channels SET token = ? WHERE user_id = ?").run(token, userId);
  return db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);
}

/**
 * 删除用户的 MP 通道
 */
function deleteChannel(userId) {
  const db = getDB();
  // 停止 Telegram 长轮询
  telegramBridge.stopPolling(userId);
  db.prepare("DELETE FROM moviepilot_channels WHERE user_id = ?").run(userId);
}

/**
 * 发送 HTTP 请求到 MP（直接模式）
 */
function _postToMP(channel, endpoint, data) {
  return new Promise((resolve, reject) => {
    const callbackUrl = channel.callback_url;
    if (!callbackUrl) {
      return resolve({ ok: false, error: "未配置 MP 回调地址" });
    }

    const url = new URL(callbackUrl.replace(/\/$/, "") + endpoint);
    const postData = JSON.stringify(data);

    const options = {
      hostname: url.hostname,
      port: url.port || (url.protocol === "https:" ? 443 : 80),
      path: url.pathname + url.search,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(postData),
      },
      timeout: 15000,
    };

    if (channel.mp_api_key) {
      options.headers["Authorization"] = "Bearer " + channel.mp_api_key;
    }

    const req = (url.protocol === "https:" ? https : http).request(options, (res) => {
      let respData = "";
      res.on("data", (chunk) => { respData += chunk; });
      res.on("end", () => {
        try {
          resolve({ ok: true, status: res.statusCode, data: JSON.parse(respData) });
        } catch (e) {
          resolve({ ok: true, status: res.statusCode, data: respData });
        }
      });
    });

    req.on("error", (e) => { resolve({ ok: false, error: e.message }); });
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, error: "请求超时" }); });
    req.write(postData);
    req.end();
  });
}

/**
 * 用户发消息给 MP
 * 根据通道模式选择发送方式
 */
async function sendUserMessageToMP(userId, username, text) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);

  if (!channel) {
    return { ok: false, error: "未找到 MP 通道配置" };
  }

  if (!channel.enabled) {
    return { ok: false, error: "MP 通道已禁用" };
  }

  // 根据通道模式选择发送方式
  if (channel.channel_mode === "telegram") {
    return await telegramBridge.sendMessageToTelegram(channel, text);
  } else {
    // 直接模式
    return await _postToMP(channel, "/api/v1/message/send", {
      username: username,
      text: text,
    });
  }
}

/**
 * 按钮点击回调
 * 根据通道模式选择发送方式
 */
async function callbackButton(userId, username, callbackData, messageId) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);

  if (!channel) {
    return { ok: false, error: "未找到 MP 通道配置" };
  }

  // 根据通道模式选择发送方式
  if (channel.channel_mode === "telegram") {
    return await telegramBridge.sendButtonCallbackViaTelegram(userId, username, callbackData, messageId);
  } else {
    // 直接模式
    return await _postToMP(channel, "/api/v1/message/callback", {
      username: username,
      callback_data: callbackData,
      message_id: messageId,
    });
  }
}

/**
 * 验证 Token
 */
function verifyToken(token) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE token = ?").get(token);
  return channel || null;
}

module.exports = {
  DEFAULT_MP_USER,
  generateToken,
  ensureUserMoviepilotTopic,
  appendMoviepilotMessage,
  getAllChannels,
  getChannelByUserId,
  createChannel,
  updateChannel,
  resetToken,
  deleteChannel,
  sendUserMessageToMP,
  callbackButton,
  verifyToken,
};
