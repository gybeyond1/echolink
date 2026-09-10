/**
 * WebHook 路由
 * 
 * 支持：
 * 1. 留言板 WebHook（/messagewall 和 /messagewall/:username）- 保留
 * 2. MoviePilot WebHook 已移除，改为 Telegram 桥接模式
 */

const express = require("express");
const router = express.Router();

const { getDB, getUserIdByUsername } = require("../db");
const messagewall = require("../messagewall");

const DEFAULT_WALL_USER = "gybeyond";

/**
 * 留言板 WebHook - 默认地址（推送给所有开启了留言功能的用户）
 */
router.post("/messagewall", (req, res) => {
  try {
    const { source, sourceName, sourceDesc, title, content, image, voice } = req.body || {};
    const db = getDB();

    // 获取所有开启了留言功能的用户
    const targets = db.prepare(`
      SELECT mwt.user_id, u.username 
      FROM messagewall_targets mwt 
      JOIN users u ON mwt.user_id = u.id 
      WHERE mwt.enabled = 1
    `).all();

    if (targets.length === 0) {
      return res.json({ ok: true, delivered: 0, message: "没有开启留言功能的用户" });
    }

    let delivered = 0;
    for (const target of targets) {
      try {
        messagewall.appendMessagewallMessage(
          sourceName || "留言板",
          title || "匿名访客",
          content || "",
          sourceDesc || "",
          image || null,
          voice || null,
          target.username
        );
        delivered++;
      } catch (e) {
        console.error(`推送给用户 ${target.username} 失败:`, e.message);
      }
    }

    res.json({ ok: true, delivered: delivered });
  } catch (e) {
    console.error("留言板 WebHook 错误:", e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

/**
 * 留言板 WebHook - 用户独立地址
 * 格式：/messagewall/:username
 */
router.post("/messagewall/:username", (req, res) => {
  try {
    const { username } = req.params;
    const { source, sourceName, sourceDesc, title, content, image, voice } = req.body || {};

    const userId = getUserIdByUsername(username);
    if (!userId) {
      return res.status(404).json({ ok: false, error: "用户不存在" });
    }

    // 检查用户是否开启了留言功能
    const db = getDB();
    const target = db.prepare("SELECT * FROM messagewall_targets WHERE user_id = ? AND enabled = 1").get(userId);
    if (!target) {
      return res.status(403).json({ ok: false, error: "该用户未开启留言功能" });
    }

    messagewall.appendMessagewallMessage(
      sourceName || "留言板",
      title || "匿名访客",
      content || "",
      sourceDesc || "",
      image || null,
      voice || null,
      username
    );

    res.json({ ok: true, delivered: 1 });
  } catch (e) {
    console.error("留言板用户 WebHook 错误:", e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

/**
 * 留言板 WebHook - GET 方法（用于测试连接）
 */
router.get("/messagewall", (req, res) => {
  res.json({
    ok: true,
    message: "留言板 WebHook 已就绪",
    endpoints: [
      "POST /api/webhook/messagewall - 推送给所有开启留言的用户",
      "POST /api/webhook/messagewall/:username - 推送给指定用户",
    ],
  });
});

// ============================================
// MoviePilot WebHook 已移除
// 改为 Telegram 桥接模式，通过 Telegram Bot API 与 MP 通信
// 相关代码请查看 telegram_bridge.js 和 moviepilot.js
// ============================================

module.exports = router;
