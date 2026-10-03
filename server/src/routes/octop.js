const express = require("express");
const { authMiddleware } = require("../middleware/auth");
const {
  callbackButton, sendUserMessageToOctop, ensureUserOctopTopic,
  getOrCreateChannel, verifyToken, appendOctopMessage, editOctopMessage,
  deleteOctopMessage, answerCallbackQuery, getUpdates,
  startStreamMessage, appendStreamMessage, endStreamMessage,
} = require("../octop");

const router = express.Router();

// 消息接收端点（Octop 推送通知到 EchoLink）
// 不需要登录，通过 token 鉴权
router.post(["/receive", "/send_message"], (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  const username = channel.username;

  const card = (body.card && typeof body.card === "object") ? body.card : body;
  let richData = null;
  if (body.rich_message) {
    try {
      if (typeof body.rich_message === "string") richData = JSON.parse(body.rich_message);
      else richData = body.rich_message;
    } catch (e) {
      console.log("[octop] 解析 rich_message 失败:", e.message);
    }
  }

  const cardData = {
    title: (richData && richData.title) || card.title || body.title || "Octop",
    text: (richData && richData.text) || card.text || body.text || "",
    poster: (richData && richData.poster) || (richData && richData.image) || card.poster || body.poster || body.image || "",
    details: (richData && Array.isArray(richData.details)) ? richData.details
      : (Array.isArray(card.details) ? card.details : (Array.isArray(body.details) ? body.details : [])),
    buttons: Array.isArray(card.buttons) ? card.buttons : (Array.isArray(body.buttons) ? body.buttons : []),
  };
  const text = cardData.text || body.text || body.content || "";

  try {
    const r = appendOctopMessage(username, cardData, text);
    if (r.error) return res.status(400).json({ error: r.error });
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[octop] receive error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 编辑已发送消息
router.post("/edit_message", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });

  const messageId = parseInt(body.message_id) || 0;
  const text = body.text || "";
  const buttons = Array.isArray(body.buttons) ? body.buttons : [];
  const details = Array.isArray(body.details) ? body.details : [];

  if (!messageId) return res.status(400).json({ error: "message_id 是必填的" });

  try {
    const r = editOctopMessage(messageId, text, buttons, details);
    if (r.error) return res.status(400).json({ error: r.error });
    return res.status(200).json({ ok: true, message_id: messageId });
  } catch (e) {
    console.error("[octop] edit_message error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 删除已发送消息
router.post("/delete_message", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });

  const messageId = parseInt(body.message_id) || 0;
  if (!messageId) return res.status(400).json({ error: "message_id 是必填的" });

  try {
    const r = deleteOctopMessage(messageId);
    if (r.error) return res.status(400).json({ error: r.error });
    return res.status(200).json({ ok: true, message_id: messageId });
  } catch (e) {
    console.error("[octop] delete_message error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 回答按钮回调（Octop 给用户一个反馈提示）
router.post("/answer_callback", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });

  const callbackQueryId = body.callback_query_id || "";
  const text = body.text || "";
  const showAlert = !!body.show_alert;

  try {
    const r = answerCallbackQuery(callbackQueryId, text, showAlert);
    return res.status(200).json({ ok: true, ...r });
  } catch (e) {
    console.error("[octop] answer_callback error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 发送语音
router.post("/send_voice", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const username = channel.username;

  const voice = body.voice || "";
  const voiceName = body.voice_name || "voice.ogg";
  const caption = body.caption || "";

  try {
    const cardData = {
      title: "🎤 语音消息",
      text: caption || `[语音消息] ${voiceName}`,
      poster: "", details: [], buttons: [],
    };
    const r = appendOctopMessage(username, cardData, caption || `[语音消息] ${voiceName}`);
    if (r.error) return res.status(400).json({ error: r.error });
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[octop] send_voice error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// 发送文件
router.post("/send_file", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const username = channel.username;

  const file = body.file || "";
  const fileName = body.file_name || "file.bin";
  const caption = body.caption || "";

  try {
    const cardData = {
      title: "📎 文件",
      text: caption || `[文件] ${fileName}`,
      poster: "", details: [], buttons: [],
    };
    const r = appendOctopMessage(username, cardData, caption || `[文件] ${fileName}`);
    if (r.error) return res.status(400).json({ error: r.error });
    return res.status(200).json({ ok: true, delivered: r.delivered, message_id: r.message?.id });
  } catch (e) {
    console.error("[octop] send_file error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

// typing 状态（暂不实际推送，直接返回成功）
router.post("/send_typing", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  return res.status(200).json({ ok: true });
});

// 下载文件
router.get("/download_file", (req, res) => {
  const token = req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const fileId = req.query.file_id || "";
  if (!fileId) return res.status(400).json({ error: "file_id 是必填的" });
  return res.status(200).json({ ok: true, file_id: fileId, data: "" });
});

// 注册命令菜单
router.post("/register_commands", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const commands = body.commands || {};
  const username = channel.username;
  if (!global.octopCommandMenus) global.octopCommandMenus = new Map();
  global.octopCommandMenus.set(username, commands);
  console.log(`[octop] 命令菜单已注册: ${username}, ${Object.keys(commands).length} 个命令`);
  return res.status(200).json({ ok: true, count: Object.keys(commands).length });
});

// 删除命令菜单
router.post("/delete_commands", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const username = channel.username;
  if (global.octopCommandMenus) global.octopCommandMenus.delete(username);
  return res.status(200).json({ ok: true });
});

// 长轮询拉取用户消息/按钮点击
router.get("/updates/:username", async (req, res) => {
  const { username } = req.params;
  const token = req.query.token || req.headers["x-octop-token"];
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
    console.error("[octop] getUpdates error:", e);
    res.status(500).json({ error: "internal error" });
  }
});

// ===== 需要登录的端点 =====
router.use(authMiddleware);

// 查询当前用户的 Octop 通道状态
router.get("/status", (req, res) => {
  try {
    const channel = getOrCreateChannel(req.userId);
    res.json({
      enabled: channel.enabled === 1,
      hasChannel: true,
      topic: "octop_" + req.username,
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 用户在 EchoLink 点卡片按钮 → 转发给 Octop
router.post("/callback", async (req, res) => {
  const { callback_data, message_id } = req.body || {};
  if (!callback_data) return res.status(400).json({ error: "callback_data is required" });
  const username = req.username;
  try {
    const result = await callbackButton(username, callback_data, message_id);
    if (result.error) return res.status(502).json({ error: result.error });
    res.json({ ok: true, octop_status: result.status, octop_response: result.body });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 确保 Octop 话题存在
router.get("/ensure", (req, res) => {
  try {
    const topic = ensureUserOctopTopic(req.userId, req.username);
    res.json({ ok: true, topic: { name: topic.name, title: topic.title, kind: topic.kind } });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 用户在 Octop 话题发文字 → 转发给 Octop
router.post("/send", async (req, res) => {
  const { text } = req.body || {};
  if (!text || !text.trim()) return res.status(400).json({ error: "text is required" });
  const username = req.username;
  ensureUserOctopTopic(req.userId, username);
  try {
    const result = await sendUserMessageToOctop(username, text.trim());
    if (result.error) return res.status(502).json({ error: result.error });
    res.json({ ok: true, octop_response: result });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// 流式消息 API
router.post("/stream_start", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  // 只允许推到通道所属用户，忽略客户端传入的 username
  const username = channel.username;
  const initialText = body.text || "";
  try {
    const r = startStreamMessage(username, initialText);
    if (r.error) return res.status(400).json({ error: r.error });
    res.json({ ok: true, message_id: r.message_id, delivered: r.delivered });
  } catch (e) {
    console.error("[octop] stream_start error:", e);
    res.status(500).json({ error: "internal error" });
  }
});

router.post("/stream_append", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const messageId = parseInt(body.message_id) || 0;
  const text = body.text || "";
  if (!messageId) return res.status(400).json({ error: "message_id 是必填的" });
  try {
    const r = appendStreamMessage(messageId, text);
    if (r.error) return res.status(400).json({ error: r.error });
    res.json({ ok: true, message_id: messageId, full_text_length: r.full_text ? r.full_text.length : 0 });
  } catch (e) {
    console.error("[octop] stream_append error:", e);
    res.status(500).json({ error: "internal error" });
  }
});

router.post("/stream_end", (req, res) => {
  const body = req.body || {};
  const token = body.token || req.query.token || req.headers["x-octop-token"];
  const channel = verifyToken(token);
  if (!channel) return res.status(401).json({ error: "无效或缺失的通道 token" });
  const messageId = parseInt(body.message_id) || 0;
  if (!messageId) return res.status(400).json({ error: "message_id 是必填的" });
  try {
    const r = endStreamMessage(messageId);
    if (r.error) return res.status(400).json({ error: r.error });
    res.json({ ok: true, message_id: messageId });
  } catch (e) {
    console.error("[octop] stream_end error:", e);
    res.status(500).json({ error: "internal error" });
  }
});

module.exports = router;
