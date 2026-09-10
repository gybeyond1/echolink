/**
 * Telegram 桥接模块
 * 
 * 功能：
 * 1. 把 EchoLink 用户的消息通过 Telegram Bot API 发送给 MP 的 Telegram 机器人
 * 2. 长轮询监控 Telegram Bot 更新，把 MP 发的消息接收到 EchoLink
 * 3. 处理交互按钮的回调
 * 4. 只有本模块走代理，其他模块不走代理
 */

const https = require("https");
const http = require("http");
const { URL } = require("url");
const { HttpsProxyAgent } = require("https-proxy-agent");
const { SocksProxyAgent } = require("socks-proxy-agent");

const { getDB, getUserIdByUsername } = require("./db");
const { ensureUserMoviepilotTopic, appendMoviepilotMessage } = require("./moviepilot");

// 长轮询任务映射：userId -> { running, stopFlag }
const pollingTasks = new Map();

/**
 * 根据通道配置创建带代理的 HTTP agent
 * 只有 Telegram 模块的请求使用这个 agent，其他模块不走代理
 */
function createProxyAgent(channel) {
  if (!channel.telegram_proxy_enabled) return null;

  const proxyType = channel.telegram_proxy_type || "http";
  const proxyHost = channel.telegram_proxy_host;
  const proxyPort = channel.telegram_proxy_port;

  if (!proxyHost || !proxyPort) return null;

  let proxyUrl;
  if (proxyType === "socks5") {
    proxyUrl = `socks5://${proxyHost}:${proxyPort}`;
    if (channel.telegram_proxy_username) {
      proxyUrl = `socks5://${channel.telegram_proxy_username}:${channel.telegram_proxy_password}@${proxyHost}:${proxyPort}`;
    }
    return new SocksProxyAgent(proxyUrl);
  } else {
    proxyUrl = `http://${proxyHost}:${proxyPort}`;
    if (channel.telegram_proxy_username) {
      proxyUrl = `http://${channel.telegram_proxy_username}:${channel.telegram_proxy_password}@${proxyHost}:${proxyPort}`;
    }
    return new HttpsProxyAgent(proxyUrl);
  }
}

/**
 * 发送 HTTP 请求到 Telegram Bot API（带代理）
 */
function telegramRequest(channel, method, params = {}) {
  const botToken = channel.telegram_bot_token;
  if (!botToken) {
    return Promise.resolve({ ok: false, error: "未配置 Telegram Bot Token" });
  }

  const url = new URL(`https://api.telegram.org/bot${botToken}/${method}`);
  const data = JSON.stringify(params);
  const agent = createProxyAgent(channel);

  const options = {
    hostname: url.hostname,
    port: url.port || 443,
    path: url.pathname,
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(data),
    },
    timeout: 30000,
    agent: agent, // 只有这个请求走代理
  };

  return new Promise((resolve) => {
    const req = https.request(options, (res) => {
      let respData = "";
      res.on("data", (chunk) => { respData += chunk; });
      res.on("end", () => {
        try {
          resolve(JSON.parse(respData));
        } catch (e) {
          resolve({ ok: false, error: "响应解析失败", raw: respData });
        }
      });
    });
    req.on("error", (e) => { resolve({ ok: false, error: e.message }); });
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, error: "请求超时" }); });
    req.write(data);
    req.end();
  });
}

/**
 * 发送文本消息到 Telegram（用户在 EchoLink 发消息给 MP）
 */
async function sendMessageToTelegram(channel, text) {
  const chatId = channel.telegram_chat_id;
  if (!chatId) {
    return { ok: false, error: "未配置 Telegram Chat ID" };
  }
  return await telegramRequest(channel, "sendMessage", {
    chat_id: chatId,
    text: text,
  });
}

/**
 * 回答回调查询（用户点击交互按钮后，告诉 Telegram 按钮已响应）
 */
async function answerCallbackQuery(channel, callbackQueryId, text = "") {
  return await telegramRequest(channel, "answerCallbackQuery", {
    callback_query_id: callbackQueryId,
    text: text,
  });
}

/**
 * 把 Telegram 消息转换成 EchoLink 卡片消息格式
 * 
 * Telegram 消息可能包含：
 * - text: 文本内容
 * - reply_markup.inline_keyboard: 内联键盘（交互按钮）
 * - photo: 图片
 * - caption: 图片说明
 */
function convertTelegramMessageToCard(telegramMessage) {
  const cardData = {
    title: "MoviePilot",
    text: "",
    details: [],
    buttons: [],
    poster: null,
  };

  // 文本内容
  if (telegramMessage.text) {
    cardData.text = telegramMessage.text;
    // 尝试从文本中提取标题（第一行）
    const lines = telegramMessage.text.split("\n");
    if (lines.length > 0 && lines[0].length < 50) {
      cardData.title = lines[0];
    }
  }

  // 图片说明（带图片的消息）
  if (telegramMessage.caption) {
    cardData.text = telegramMessage.caption;
  }

  // 图片（海报）
  if (telegramMessage.photo && telegramMessage.photo.length > 0) {
    // Telegram 返回的是图片文件 ID，需要通过 getFile 获取 URL
    // 这里先留空，后续可以扩展
    cardData.poster = null;
  }

  // 内联键盘（交互按钮）
  if (telegramMessage.reply_markup && telegramMessage.reply_markup.inline_keyboard) {
    const keyboard = telegramMessage.reply_markup.inline_keyboard;
    for (const row of keyboard) {
      for (const button of row) {
        if (button.callback_data) {
          cardData.buttons.push({
            text: button.text,
            callback_data: button.callback_data,
          });
        } else if (button.url) {
          cardData.buttons.push({
            text: button.text,
            url: button.url,
          });
        }
      }
    }
  }

  return cardData;
}

/**
 * 处理一条 Telegram 更新
 */
async function processTelegramUpdate(userId, username, channel, update) {
  const db = getDB();

  // 回调查询（用户点击了交互按钮，MP 返回的响应）
  if (update.callback_query) {
    const callbackQuery = update.callback_query;
    // 回答回调查询
    await answerCallbackQuery(channel, callbackQuery.id);
    // 如果回调查询包含消息，处理消息
    if (callbackQuery.message) {
      const cardData = convertTelegramMessageToCard(callbackQuery.message);
      appendMoviepilotMessage(username, cardData, cardData.text);
    }
    return;
  }

  // 普通消息
  if (update.message) {
    const message = update.message;
    // 只处理来自 MP 的消息（chat_id 匹配）
    if (String(message.chat.id) !== String(channel.telegram_chat_id)) {
      return;
    }
    const cardData = convertTelegramMessageToCard(message);
    appendMoviepilotMessage(username, cardData, cardData.text);
  }

  // 编辑后的消息（MP 编辑了消息，比如更新交互按钮状态）
  if (update.edited_message) {
    const message = update.edited_message;
    if (String(message.chat.id) !== String(channel.telegram_chat_id)) {
      return;
    }
    // 编辑消息暂时也作为新消息处理（后续可以优化为更新已有消息）
    const cardData = convertTelegramMessageToCard(message);
    appendMoviepilotMessage(username, cardData, cardData.text);
  }
}

/**
 * 长轮询监控 Telegram 更新
 * 这个函数会一直运行，直到 stopFlag 被设置为 true
 */
async function startPolling(userId, username, channel) {
  const task = pollingTasks.get(userId);
  if (task && task.running) {
    return; // 已经在运行了
  }

  const taskState = { running: true, stopFlag: false };
  pollingTasks.set(userId, taskState);

  console.log(`[TelegramBridge] 开始监控用户 ${username} 的 Telegram 更新`);

  while (!taskState.stopFlag) {
    try {
      const db = getDB();
      const latestChannel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);

      if (!latestChannel || !latestChannel.telegram_bot_token || !latestChannel.telegram_chat_id) {
        console.log(`[TelegramBridge] 用户 ${username} 未配置 Telegram，停止监控`);
        break;
      }

      if (latestChannel.channel_mode !== "telegram") {
        console.log(`[TelegramBridge] 用户 ${username} 不是 Telegram 模式，停止监控`);
        break;
      }

      const lastUpdateId = latestChannel.telegram_last_update_id || 0;

      // 长轮询获取更新（timeout=30 秒）
      const result = await telegramRequest(latestChannel, "getUpdates", {
        offset: lastUpdateId + 1,
        timeout: 30,
        allowed_updates: ["message", "edited_message", "callback_query"],
      });

      if (result.ok && result.result && result.result.length > 0) {
        for (const update of result.result) {
          await processTelegramUpdate(userId, username, latestChannel, update);
          // 更新 last_update_id
          db.prepare("UPDATE moviepilot_channels SET telegram_last_update_id = ? WHERE user_id = ?")
            .run(update.update_id, userId);
        }
      } else if (!result.ok) {
        console.error(`[TelegramBridge] 用户 ${username} 获取更新失败: ${result.error || result.description}`);
        // 出错后等待 5 秒再重试
        await new Promise((resolve) => setTimeout(resolve, 5000));
      }
    } catch (e) {
      console.error(`[TelegramBridge] 用户 ${username} 监控异常:`, e.message);
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }

  taskState.running = false;
  console.log(`[TelegramBridge] 用户 ${username} 的 Telegram 监控已停止`);
}

/**
 * 停止某个用户的 Telegram 监控
 */
function stopPolling(userId) {
  const task = pollingTasks.get(userId);
  if (task) {
    task.stopFlag = true;
  }
}

/**
 * 启动所有 Telegram 模式用户的监控（服务器启动时调用）
 */
function startAllPolling() {
  const db = getDB();
  const channels = db.prepare("SELECT * FROM moviepilot_channels WHERE channel_mode = 'telegram' AND enabled = 1").all();

  for (const channel of channels) {
    const user = db.prepare("SELECT username FROM users WHERE id = ?").get(channel.user_id);
    if (user && channel.telegram_bot_token && channel.telegram_chat_id) {
      // 异步启动，不阻塞服务器启动
      startPolling(channel.user_id, user.username, channel).catch((e) => {
        console.error(`[TelegramBridge] 启动用户 ${user.username} 监控失败:`, e.message);
      });
    }
  }

  console.log(`[TelegramBridge] 已启动 ${channels.length} 个用户的 Telegram 监控`);
}

/**
 * 用户发消息给 MP（Telegram 模式下通过 Telegram Bot API 发送）
 */
async function sendUserMessageViaTelegram(userId, username, text) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);

  if (!channel) {
    return { ok: false, error: "未找到 MP 通道配置" };
  }

  if (channel.channel_mode !== "telegram") {
    return { ok: false, error: "当前不是 Telegram 模式" };
  }

  return await sendMessageToTelegram(channel, text);
}

/**
 * 按钮点击回调（Telegram 模式下通过 Telegram Bot API 发送回调数据）
 * 
 * 注意：Telegram 的按钮回调不是直接发送文本，而是需要用户在 Telegram 里点击按钮。
 * 在 EchoLink 里点击按钮时，我们把 callback_data 作为文本消息发送给 MP，
 * MP 收到后会处理这个回调。
 */
async function sendButtonCallbackViaTelegram(userId, username, callbackData, messageId) {
  const db = getDB();
  const channel = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(userId);

  if (!channel) {
    return { ok: false, error: "未找到 MP 通道配置" };
  }

  if (channel.channel_mode !== "telegram") {
    return { ok: false, error: "当前不是 Telegram 模式" };
  }

  // 把 callback_data 作为文本消息发送给 MP
  // MP 的 Telegram 机器人收到后，会根据文本内容处理
  return await sendMessageToTelegram(channel, callbackData);
}

module.exports = {
  createProxyAgent,
  telegramRequest,
  sendMessageToTelegram,
  answerCallbackQuery,
  convertTelegramMessageToCard,
  processTelegramUpdate,
  startPolling,
  stopPolling,
  startAllPolling,
  sendUserMessageViaTelegram,
  sendButtonCallbackViaTelegram,
};
