const express = require("express");
const { appendMessagewallMessage, DEFAULT_WALL_USER, getMessagewallEnabledUsers } = require("../messagewall");
const { appendMoviepilotMessage, verifyToken, DEFAULT_MP_USER } = require("../moviepilot");

const router = express.Router();

// ===== MessageWall（留言板 WebHook，保留不动）=====

// 健康/说明：GET 用于确认端点可达
router.get("/messagewall", (req, res) => {
  res.json({
    ok: true,
    endpoint: "messagewall",
    method: "POST",
    defaultUser: DEFAULT_WALL_USER,
    note: "POST /webhook/messagewall 或 /webhook/messagewall/:username，发送 JSON: { source: 'messagewall', title: '<名字>', content: '<正文>', image: '<可选base64>', voice: '<可选base64>' }",
  });
});

// 留言板 Webhook 接收（指定用户名）→ 消息进入该用户的独立留言板话题
router.post("/messagewall/:username", (req, res) => {
  handleMessagewall(req, res, req.params.username);
});

// 留言板 Webhook 接收（兼容旧地址，不带用户名）→ 推送给所有开启了留言功能的用户
router.post("/messagewall", (req, res) => {
  const body = req.body || {};
  if (body.source !== "messagewall") {
    return res.status(400).json({ error: "unsupported source (expected 'messagewall')" });
  }
  const title = String(body.title || "").trim();
  const content = String(body.content || "").trim();
  const image = body.image ? String(body.image) : "";
  const voice = body.voice ? String(body.voice) : "";
  if (!title) return res.status(400).json({ error: "title is required" });
  if (!content && !image && !voice) return res.status(400).json({ error: "content or image or voice is required" });
  const sourceName = String(body.sourceName || "留言板");
  const sourceDesc = String(body.sourceDesc || `来自「${sourceName}」的留言`);

  const enabledUsers = getMessagewallEnabledUsers();
  let delivered = 0;
  const errors = [];
  for (const username of enabledUsers) {
    try {
      const r = appendMessagewallMessage(title, content, sourceDesc, image || null, voice || null, username);
      if (r.error) errors.push(username + ": " + r.error);
      else delivered++;
    } catch (e) {
      errors.push(username + ": " + e.message);
    }
  }
  return res.status(200).json({ ok: true, delivered, users: enabledUsers, errors: errors.length ? errors : undefined });
});

function handleMessagewall(req, res, username) {
  const body = req.body || {};
  if (body.source !== "messagewall") {
    return res.status(400).json({ error: "unsupported source (expected 'messagewall')" });
  }
  const title = String(body.title || "").trim();
  const content = String(body.content || "").trim();
  const image = body.image ? String(body.image) : "";
  const voice = body.voice ? String(body.voice) : "";
  if (!title) {
    return res.status(400).json({ error: "title is required" });
  }
  if (!content && !image && !voice) {
    return res.status(400).json({ error: "title and (content or image or voice) are required" });
  }
  const sourceName = String(body.sourceName || "留言板");
  const sourceDesc = String(body.sourceDesc || `来自「${sourceName}」的留言`);

  try {
    const r = appendMessagewallMessage(title, content, sourceDesc, image || null, voice || null, username);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, delivered: r.delivered, user: username });
  } catch (e) {
    console.error("[webhook] messagewall error:", e);
    return res.status(500).json({ error: "internal error" });
  }
}

// ===== MoviePilot（直接模式 WebHook，MP 插件推送消息到这里）=====

// 健康/说明
router.get("/moviepilot", (req, res) => {
  res.json({
    ok: true,
    endpoint: "moviepilot",
    method: "POST",
    defaultUser: DEFAULT_MP_USER,
    note: "POST /webhook/moviepilot 或 /webhook/moviepilot/:username，发送 JSON: { token, title, text, poster, details: [{key,value}], buttons: [{text,callback_data}] }",
  });
});

// MP 推送（指定用户名）
router.post("/moviepilot/:username", (req, res) => {
  handleMoviepilot(req, res, req.params.username);
});

// MP 推送（兼容旧地址，不带用户名 → 默认用户）
router.post("/moviepilot", (req, res) => {
  handleMoviepilot(req, res, DEFAULT_MP_USER);
});

function handleMoviepilot(req, res, username) {
  const body = req.body || {};
  // 验证 token
  const token = body.token || req.query.token || req.headers["x-mp-token"];
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "无效或缺失的通道 token" });
  }
  // 用户名以 URL 路径为准，但 token 必须对应用户
  if (channel.username !== username) {
    return res.status(403).json({ error: "token 与用户不匹配" });
  }

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
    console.error("[webhook] moviepilot error:", e);
    return res.status(500).json({ error: "internal error" });
  }
}

module.exports = router;
