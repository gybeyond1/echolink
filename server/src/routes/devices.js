const express = require("express");
const crypto = require("crypto");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");

const router = express.Router();

// 注册设备
// 支持 client_id（每个物理设备安装时生成的稳定标识）：同一设备重登录时复用同一 device_id，
// 保证"按设备"的清空/删除状态在不同会话间持久。无 client_id 时仍按旧逻辑新建。
router.post("/register", authMiddleware, (req, res) => {
  const { device_name, platform, client_id } = req.body;
  if (!device_name) {
    return res.status(400).json({ error: "device_name is required" });
  }

  const db = getDB();
  const deviceToken = crypto.randomBytes(32).toString("hex");

  // 若提供了稳定的 client_id，则尝试复用该用户下已有设备
  if (client_id) {
    const existing = db
      .prepare("SELECT id, device_name FROM devices WHERE user_id = ? AND client_id = ?")
      .get(req.userId, client_id);
    if (existing) {
      db.prepare("UPDATE devices SET device_name = ?, platform = ?, last_seen = datetime('now') WHERE id = ?")
        .run(device_name, platform || "android", existing.id);
      return res.status(200).json({
        device_id: existing.id,
        device_token: null,
        device_name,
        reused: true,
      });
    }
  }

  const result = db
    .prepare("INSERT INTO devices (user_id, device_name, device_token, platform, client_id, last_seen) VALUES (?, ?, ?, ?, ?, datetime('now'))")
    .run(req.userId, device_name, deviceToken, platform || "android", client_id || null);

  res.status(201).json({
    device_id: result.lastInsertRowid,
    device_token: deviceToken,
    device_name,
    reused: false,
  });
});

// 列出设备
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  const devices = db.prepare("SELECT id, device_name, platform, created_at, last_seen FROM devices WHERE user_id = ?").all(req.userId);
  res.json({ devices });
});

// 删除设备
router.delete("/:id", authMiddleware, (req, res) => {
  const db = getDB();
  const result = db.prepare("DELETE FROM devices WHERE id = ? AND user_id = ?").run(req.params.id, req.userId);
  if (result.changes === 0) {
    return res.status(404).json({ error: "Device not found" });
  }
  res.json({ message: "Device deleted" });
});

// 更新设备最后在线时间
router.post("/heartbeat", authMiddleware, (req, res) => {
  const db = getDB();
  const { device_id } = req.body;
  if (device_id) {
    db.prepare("UPDATE devices SET last_seen = datetime('now') WHERE id = ? AND user_id = ?").run(device_id, req.userId);
  }
  res.json({ message: "ok" });
});

module.exports = router;
