const express = require("express");
const { appendMessagewallMessage, DEFAULT_WALL_USER, getMessagewallEnabledUsers } = require("../messagewall");
const { appendMoviepilotMessage, verifyToken, DEFAULT_MP_USER } = require("../moviepilot");

const router = express.Router();

// ===== MessageWall =====

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

// ===== MoviePilot =====

// 健康/说明
router.get("/moviepilot", (req, res) => {
  res.json({
    ok: true,
    endpoint: "moviepilot",
    method: "POST",
    defaultUser: DEFAULT_MP_USER,
    note: "POST /webhook/moviepilot/:username，Header X-API-Token 或 body.token 鉴权。body: { source: 'moviepilot', card: { title, poster, details:[{key,value}], buttons:[{text,callback_data}], text } }",
  });
});

// MP Webhook 接收（指定用户名）
router.post("/moviepilot/:username", (req, res) => {
  handleMoviepilot(req, res, req.params.username);
});

// MP Webhook 接收（兼容旧地址，默认用户）
router.post("/moviepilot", (req, res) => {
  handleMoviepilot(req, res, DEFAULT_MP_USER);
});

function handleMoviepilot(req, res, username) {
  const body = req.body || {};
  if (body.source !== "moviepilot") {
    return res.status(400).json({ error: "unsupported source (expected 'moviepilot')" });
  }

  // 鉴权：Header X-API-Token 优先，其次 body.token
  const token = req.get("X-API-Token") || body.token || "";
  const channel = verifyToken(token);
  if (!channel) {
    return res.status(401).json({ error: "invalid or missing token" });
  }
  // token 对应用户必须和 URL 里的 username 一致
  if (channel.username !== username) {
    return res.status(403).json({ error: "token does not match user" });
  }

  const card = body.card || {};
  const text = String(body.text || card.text || "").trim();
  if (!card.title && !text) {
    return res.status(400).json({ error: "card.title or text is required" });
  }

  try {
    const r = appendMoviepilotMessage(username, card, text);
    if (r.error) {
      return res.status(400).json({ error: r.error });
    }
    return res.status(200).json({ ok: true, delivered: r.delivered, user: username, message_id: r.message.id });
  } catch (e) {
    console.error("[webhook] moviepilot error:", e);
    return res.status(500).json({ error: "internal error" });
  }
}

module.exports = router;
