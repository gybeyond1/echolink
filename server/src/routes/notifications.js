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

  // 短信验证码不受应用过滤器限制，保证验证码一定送达其他设备
  const isSmsCode = package_name === "com.android.sms";
  if (!isSmsCode) {
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
  }

  const ts = timestamp || Date.now();
  const fromDeviceId = device_id || null;
  const result = db
    .prepare(`INSERT INTO notifications (user_id, device_id, package_name, app_name, title, text, timestamp)
              VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .run(req.userId, fromDeviceId, package_name, app_name, title || "", text || "", ts);

  // 实时推送也要带设备名（通知显示处会 JOIN 设备表，这里一并查出，接收端无需再查一次）
  const senderDevice = fromDeviceId
    ? db.prepare("SELECT device_name FROM devices WHERE id = ?").get(fromDeviceId)
    : null;

  const notification = {
    id: result.lastInsertRowid,
    package_name,
    app_name,
    title: title || "",
    text: text || "",
    timestamp: ts,
    device_id: fromDeviceId,
    device_name: senderDevice?.device_name || null,
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
// 支持 ?device_id= 实现「按设备」视图：该设备已清空/删除的通知对其隐藏，但不影响其他设备
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  const limit = Math.min(parseInt(req.query.limit) || 50, 200);
  const offset = parseInt(req.query.offset) || 0;
  const deviceId = req.query.device_id ? parseInt(req.query.device_id) : null;

  let notifications;
  if (deviceId) {
    notifications = db
      .prepare(`SELECT n.*, d.device_name FROM notifications n
                LEFT JOIN devices d ON n.device_id = d.id
                WHERE n.user_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM notification_deletes nd
                    WHERE nd.user_id = n.user_id AND nd.device_id = ? AND nd.notification_id = n.id
                  )
                ORDER BY n.timestamp DESC LIMIT ? OFFSET ?`)
      .all(req.userId, deviceId, limit, offset);
  } else {
    // WebUI 管理后台（不带 device_id）查看该账号全部通知
    notifications = db
      .prepare(`SELECT n.*, d.device_name FROM notifications n
                LEFT JOIN devices d ON n.device_id = d.id
                WHERE n.user_id = ?
                ORDER BY n.timestamp DESC LIMIT ? OFFSET ?`)
      .all(req.userId, limit, offset);
  }

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
// 带 ?device_id= 时：仅对该设备软删除（插入隐藏表），其他设备仍可见
// 不带时（管理后台）：直接硬删除该记录
router.delete("/:id", authMiddleware, (req, res) => {
  const db = getDB();
  const deviceId = req.query.device_id ? parseInt(req.query.device_id) : null;

  if (deviceId) {
    db.prepare(
      `INSERT OR IGNORE INTO notification_deletes (user_id, device_id, notification_id)
       VALUES (?, ?, ?)`
    ).run(req.userId, deviceId, parseInt(req.params.id));
    return res.json({ message: "Notification hidden for this device" });
  }

  const result = db.prepare("DELETE FROM notifications WHERE id = ? AND user_id = ?").run(req.params.id, req.userId);
  if (result.changes === 0) {
    return res.status(404).json({ error: "Notification not found" });
  }
  res.json({ message: "Notification deleted" });
});

// 清空所有通知
// 带 ?device_id= 时：仅对该设备软清空（该设备视图为空，其他设备不变）
// 不带时（管理后台）：直接清空该账号全部
router.delete("/", authMiddleware, (req, res) => {
  const db = getDB();
  const deviceId = req.query.device_id ? parseInt(req.query.device_id) : null;

  if (deviceId) {
    db.prepare(
      `INSERT OR IGNORE INTO notification_deletes (user_id, device_id, notification_id)
       SELECT user_id, ?, id FROM notifications WHERE user_id = ?`
    ).run(deviceId, req.userId);
    return res.json({ message: "All notifications cleared for this device" });
  }

  db.prepare("DELETE FROM notifications WHERE user_id = ?").run(req.userId);
  res.json({ message: "All notifications cleared" });
});

module.exports = router;
