const express = require("express");
const { authMiddleware } = require("../middleware/auth");
const { callbackButton, sendUserMessageToMP, ensureUserMoviepilotTopic, getOrCreateChannel, verifyToken, appendMoviepilotMessage, editMoviepilotMessage, deleteMoviepilotMessage, answerCallbackQuery, getUpdates } = require("../moviepilot");

const router = express.Router();

// MP 消息接收端点（MP 通知渠道调用此接口发送消息到 EchoLink）
// 不需要登录，通过 token 鉴权
router.post("/receive", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;

  // 兼容两种格式：MP 插件发送 {card: {...}}，旧格式直接平铺
  const card = (body.card && typeof body.card === "object") ? body.card : body;
  const cardData = {
    title: card.title || body.title || "MoviePilot",
    text: card.text || body.text || "",
    poster: card.poster || body.poster || "",
    details: Array.isArray(card.details) ? card.details : (Array.isArray(body.details) ? body.details : []),
    buttons: Array.isArray(card.buttons) ? card.buttons : (Array.isArray(body.buttons) ? body.buttons : []),
  };
  const text = card.text || body.text || body.content || "";

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
