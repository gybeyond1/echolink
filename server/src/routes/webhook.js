const express = require("express");
const { appendMessagewallMessage } = require("../messagewall");

const router = express.Router();

// 健康/说明：GET 用于确认端点可达（留言板应用调试用）
router.get("/messagewall", (req, res) => {
  res.json({
    ok: true,
    endpoint: "messagewall",
    method: "POST",
    note: "发送 JSON: { source: 'messagewall', title: '<留言人>', content: '<留言正文>' }",
  });
});

// 留言板 Webhook 接收端点（无鉴权，纯局域网内调用）
// 调用方：门边留言板应用后端（在局域网内 POST 到本服务端）。
router.post("/messagewall", (req, res) => {
  const body = req.body || {};
  if (body.source !== "messagewall") {
    return res.status(400).json({ error: "unsupported source (expected 'messagewall')" });
  }
  const title = String(body.title || "").trim();
  const content = String(body.content || "").trim();
  if (!title || !content) {
    return res.status(400).json({ error: "title and content are required" });
  }
  const sourceName = String(body.sourceName || "留言板");
  const sourceDesc = String(body.sourceDesc || `来自「${sourceName}」的留言`);

  try {
    const r = appendMessagewallMessage(title, content, sourceDesc);
    return res.status(200).json({ ok: true, delivered: r.delivered });
  } catch (e) {
    console.error("[webhook] messagewall error:", e);
    return res.status(500).json({ error: "internal error" });
  }
});

module.exports = router;
