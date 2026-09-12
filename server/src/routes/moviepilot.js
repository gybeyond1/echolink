const express = require("express");
const { authMiddleware } = require("../middleware/auth");
const { callbackButton, sendUserMessageToMP, ensureUserMoviepilotTopic, getOrCreateChannel, verifyToken, appendMoviepilotMessage, editMoviepilotMessage, deleteMoviepilotMessage, answerCallbackQuery, getUpdates } = require("../moviepilot");

const router = express.Router();

// MP 消息接收端点（MP 通知渠道调用此接口发送消息到 EchoLink）
// 不需要登录，通过 token 鉴权
// 兼容两种路径：/receive 和 /send_message（MP 端重构后用 /send_message）
router.post(["/receive", "/send_message"], (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;

  // 兼容两种格式：MP 插件发送 {card: {...}}，旧格式直接平铺
  const card = (body.card && typeof body.card === "object") ? body.card : body;
  // 优先使用 rich_message 中的结构化数据
  let richData = null;
  if (body.rich_message) {
    try {
      if (typeof body.rich_message === "string") {
        richData = JSON.parse(body.rich_message);
      } else {
        richData = body.rich_message;
      }
    } catch (e) {
      console.log("[moviepilot] 解析 rich_message 失败:", e.message);
    }
  }
  
  const cardData = {
    title: (richData && richData.title) || card.title || body.title || "MoviePilot",
    text: (richData && richData.text) || card.text || body.text || "",
    poster: (richData && richData.poster) || (richData && richData.image) || card.poster || body.poster || body.image || "",
    details: (richData && Array.isArray(richData.details)) ? richData.details : (Array.isArray(card.details) ? card.details : (Array.isArray(body.details) ? body.details : [])),
    buttons: Array.isArray(card.buttons) ? card.buttons : (Array.isArray(body.buttons) ? body.buttons : []),
  };
  const text = cardData.text || body.text || body.content || "";

  try {
    const r = appendMoviepilotMessage(username, cardData, text);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[moviepilot] receive error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 编辑消息端点（MP 通知渠道调用此接口编辑已发送的消息，如更新交互菜单状态）
// 不需要登录，通过 token 鉴权
router.post("/edit_message", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }

  const messageId = parseInt(body.message_id) || 0;
  const text = body.text || "";
  const buttons = Array.isArray(body.buttons) ? body.buttons : [];
  const details = Array.isArray(body.details) ? body.details : [];

  if (!messageId) {
    return res.status(400).json({ error: "message_id 是必填的" });
  }

  try {
    const r = editMoviepilotMessage(messageId, text, buttons, details);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, message_id: messageId });
  } catch (e) {
    console.error("[moviepilot] edit_message error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 删除消息端点（MP 通知渠道调用此接口删除已发送的消息）
// 不需要登录，通过 token 鉴权
router.post("/delete_message", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }

  const messageId = parseInt(body.message_id) || 0;
  if (!messageId) {
    return res.status(400).json({ error: "message_id 是必填的" });
  }

  try {
    const r = deleteMoviepilotMessage(messageId);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, message_id: messageId });
  } catch (e) {
    console.error("[moviepilot] delete_message error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 回答按钮回调端点（MP 通知渠道调用此接口给用户一个反馈提示）
// 不需要登录，通过 token 鉴权
router.post("/answer_callback", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }

  const callbackQueryId = body.callback_query_id || "";
  const text = body.text || "";
  const showAlert = !!body.show_alert;

  try {
    const r = answerCallbackQuery(callbackQueryId, text, showAlert);
    return res.status(200).json({ ok: true, ...r });
  } catch (e) {
    console.error("[moviepilot] answer_callback error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 发送语音消息端点（MP 通知渠道调用此接口发送语音到 EchoLink）
router.post("/send_voice", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;

  const voice = body.voice || ""; // base64 编码的语音数据
  const voiceName = body.voice_name || "voice.ogg";
  const caption = body.caption || "";
  const chatId = body.chat_id;

  try {
    // 语音消息作为特殊卡片发送，标题标识为语音
    const cardData = {
      title: "🎤 语音消息",
      text: caption || `[语音消息] ${voiceName}`,
      poster: "",
      details: [],
      buttons: [],
    };
    const r = appendMoviepilotMessage(username, cardData, caption || `[语音消息] ${voiceName}`);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[moviepilot] send_voice error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 发送文件消息端点（MP 通知渠道调用此接口发送文件到 EchoLink）
router.post("/send_file", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;

  const file = body.file || ""; // base64 编码的文件数据
  const fileName = body.file_name || "file.bin";
  const caption = body.caption || "";
  const chatId = body.chat_id;

  try {
    // 文件消息作为特殊卡片发送
    const cardData = {
      title: "📎 文件",
      text: caption || `[文件] ${fileName}`,
      poster: "",
      details: [],
      buttons: [],
    };
    const r = appendMoviepilotMessage(username, cardData, caption || `[文件] ${fileName}`);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[moviepilot] send_file error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 发送 typing 状态端点（MP 通知渠道调用此接口显示"正在输入..."）
router.post("/send_typing", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  // typing 状态目前不需要实际推送，直接返回成功
  return res.status(200).json({ ok: true });
});

// 下载文件端点（MP 通知渠道调用此接口下载 EchoLink 上的文件）
router.get("/download_file", (req, res) => {
  const token = req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const fileId = req.query.file_id || "";
  if (!fileId) {
    return res.status(400).json({ error: "file_id 是必填的" });
  }
  // 文件下载目前返回空（EchoLink 端文件存储需要后续实现）
  return res.status(200).json({ ok: true, file_id: fileId, data: "" });
});

// 注册命令菜单端点（MP 通知渠道调用此接口注册斜杠命令菜单）
router.post("/register_commands", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const commands = body.commands || {};
  const username = channel.username;
  // 存储命令菜单到内存（后续可以持久化到数据库）
  if (!global.mpCommandMenus) global.mpCommandMenus = new Map();
  global.mpCommandMenus.set(username, commands);
  console.log(`[moviepilot] 命令菜单已注册: ${username}, ${Object.keys(commands).length} 个命令`);
  return res.status(200).json({ ok: true, count: Object.keys(commands).length });
});

// 删除命令菜单端点
router.post("/delete_commands", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;
  if (global.mpCommandMenus) {
    global.mpCommandMenus.delete(username);
  }
  console.log(`[moviepilot] 命令菜单已删除: ${username}`);
  return res.status(200).json({ ok: true });
});

// 长轮询获取消息端点（MP 通知渠道调用此接口拉取用户消息）
// 不需要登录，通过 token 鉴权
// 类似 Telegram getUpdates：有消息立即返回，没消息挂起 timeout 秒后返回空
router.get("/updates/:username", async (req, res) => {
  const { username } = req.params;
  const token = req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel || channel.username !== username) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const offset = parseInt(req.query.offset) || 0;
  const limit = parseInt(req.query.limit) || 100;
  const timeout = Math.min(parseInt(req.query.timeout) || 30, 60);

  try {
    const updates = await getUpdates(username, offset, limit, timeout);
    res.json({ ok: true, updates });
  } catch (e) {
    console.error("[moviepilot] getUpdates error:", e);
    res.status(500).json({ error: "internal error" });
  }
});

// 所有接口需要登录
router.use(authMiddleware);

// 查询当前用户的 MP 通道状态（用于前端判断是否显示常驻 MP 入口）
router.get("/status", (req, res) => {
  try {
    const channel = getOrCreateChannel(req.userId);
    res.json({
      enabled: channel.enabled === 1,
      hasChannel: true,
      topic: "moviepilot_" + req.username,
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 按钮点击回调：用户在 EchoLink 点击 MP 卡片上的按钮，转发给 MP 插件
router.post("/callback", async (req, res) => {
  const { callback_data, message_id } = req.body || {};
  if (!callback_data) {
    return res.status(400).json({ error: "callback_data is required" });
  }
  const username = req.username;
  try {
    const result = await callbackButton(username, callback_data, message_id);
    if (result.error) {
      return res.status(502).json({ error: result.error });
    }
    res.json({ ok: true, mp_status: result.status, mp_response: result.body });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 确保 MP 话题存在（用户从好友页点击 MP 入口时调用，避免删除会话后找不到入口）
router.get("/ensure", (req, res) => {
  try {
    const topic = ensureUserMoviepilotTopic(req.userId, req.username);
    res.json({ ok: true, topic: { name: topic.name, title: topic.title, kind: topic.kind } });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 用户在 MP 话题发文字消息，转发给 MP 插件当作远程命令
router.post("/send", async (req, res) => {
  const { text } = req.body || {};
  if (!text || !text.trim()) {
    return res.status(400).json({ error: "text is required" });
  }
  const username = req.username;
  // 确保 MP 话题存在
  ensureUserMoviepilotTopic(req.userId, username);
  try {
    const result = await sendUserMessageToMP(username, text.trim());
    if (result.error) {
      return res.status(502).json({ error: result.error });
    }
    res.json({ ok: true, mp_status: result.status, mp_response: result.body });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

module.exports = router;
