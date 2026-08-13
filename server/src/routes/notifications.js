const express = require("express");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");

const router = express.Router();

// 接收通知（由 Android 设备发送）
router.post("/", authMiddleware, (req, res) => {
  const { package_name, app_name, title, text, timestamp, device_id } = req.body;

  if (!package_name || !app_name) {
    return res.status(400).json({ error: "package_name and app_name are required" });
  }

  const db = getDB();

  // 检查该应用是否在用户的过滤器中且启用
  const filter = db
    .prepare("SELECT enabled FROM app_filters WHERE user_id = ? AND package_name = ?")
    .get(req.userId, package_name);

  // 如果过滤器存在且被禁用，则跳过
  if (filter && filter.enabled === 0) {
    return res.json({ message: "Notification filtered out", synced: false });
  }

  // 如果用户设置了过滤器但此应用不在列表中，也跳过
  // （只有当过滤器列表非空时才进行过滤）
  const hasFilters = db.prepare("SELECT COUNT(*) as count FROM app_filters WHERE user_id = ? AND enabled = 1").get(req.userId);
  if (hasFilters.count > 0 && !filter) {
    return res.json({ message: "Notification filtered out (not in allowed list)", synced: false });
  }

  const ts = timestamp || Date.now();
  const fromDeviceId = device_id || null;
  const result = db
    .prepare(`INSERT INTO notifications (user_id, device_id, package_name, app_name, title, text, timestamp)
              VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .run(req.userId, fromDeviceId, package_name, app_name, title || "", text || "", ts);

  const notification = {
    id: result.lastInsertRowid,
    package_name,
    app_name,
    title: title || "",
    text: text || "",
    timestamp: ts,
    device_id: fromDeviceId,
  };

  // 通过 WebSocket 推送给该用户的其他设备（排除发送者本机，避免自己收到自己的通知）
  const { broadcastToUser } = require("../websocket");
  broadcastToUser(
    req.userId,
    {
      type: "notification",
      data: notification,
    },
    fromDeviceId
  );

  // 清理旧通知
  const maxHistory = parseInt(process.env.MAX_NOTIFICATION_HISTORY || "500");
  db.prepare(`
    DELETE FROM notifications
    WHERE user_id = ? AND id NOT IN (
      SELECT id FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT ?
    )
  `).run(req.userId, req.userId, maxHistory);

  res.status(201).json({ message: "Notification synced", notification });
});

// 获取通知列表
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  const limit = Math.min(parseInt(req.query.limit) || 50, 200);
  const offset = parseInt(req.query.offset) || 0;

  const notifications = db
    .prepare(`SELECT n.*, d.device_name FROM notifications n
              LEFT JOIN devices d ON n.device_id = d.id
              WHERE n.user_id = ?
              ORDER BY n.timestamp DESC LIMIT ? OFFSET ?`)
    .all(req.userId, limit, offset);

  res.json({ notifications });
});

// 获取指定时间之后的通知
router.get("/since/:timestamp", authMiddleware, (req, res) => {
  const db = getDB();
  const since = parseInt(req.params.timestamp) || 0;

  const notifications = db
    .prepare(`SELECT n.*, d.device_name FROM notifications n
              LEFT JOIN devices d ON n.device_id = d.id
              WHERE n.user_id = ? AND n.timestamp > ?
              ORDER BY n.timestamp ASC`)
    .all(req.userId, since);

  res.json({ notifications });
});

// 删除通知
router.delete("/:id", authMiddleware, (req, res) => {
  const db = getDB();
  const result = db.prepare("DELETE FROM notifications WHERE id = ? AND user_id = ?").run(req.params.id, req.userId);
  if (result.changes === 0) {
    return res.status(404).json({ error: "Notification not found" });
  }
  res.json({ message: "Notification deleted" });
});

// 清空所有通知
router.delete("/", authMiddleware, (req, res) => {
  const db = getDB();
  db.prepare("DELETE FROM notifications WHERE user_id = ?").run(req.userId);
  res.json({ message: "All notifications cleared" });
});

module.exports = router;
