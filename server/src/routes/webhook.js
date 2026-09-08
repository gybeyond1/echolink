const express = require("express");
const { appendMessagewallMessage, DEFAULT_WALL_USER } = require("../messagewall");

const router = express.Router();

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

// 留言板 Webhook 接收（兼容旧地址，不带用户名）→ 默认 gybeyond 用户
router.post("/messagewall", (req, res) => {
  handleMessagewall(req, res, DEFAULT_WALL_USER);
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

module.exports = router;
