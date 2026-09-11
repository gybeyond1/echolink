const express = require("express");
const { authMiddleware } = require("../middleware/auth");
const { callbackButton, sendUserMessageToMP, ensureUserMoviepilotTopic } = require("../moviepilot");

const router = express.Router();

// 所有接口需要登录
router.use(authMiddleware);

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
