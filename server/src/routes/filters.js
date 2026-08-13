const express = require("express");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");

const router = express.Router();

// 获取过滤器列表
router.get("/", authMiddleware, (req, res) => {
  const db = getDB();
  const filters = db.prepare("SELECT * FROM app_filters WHERE user_id = ? ORDER BY app_name").all(req.userId);
  res.json({ filters });
});

// 添加/更新过滤器
router.post("/", authMiddleware, (req, res) => {
  const { package_name, app_name, enabled } = req.body;
  if (!package_name || !app_name) {
    return res.status(400).json({ error: "package_name and app_name are required" });
  }

  const db = getDB();
  const result = db
    .prepare(`INSERT INTO app_filters (user_id, package_name, app_name, enabled) VALUES (?, ?, ?, ?)
              ON CONFLICT(user_id, package_name) DO UPDATE SET app_name = ?, enabled = ?`)
    .run(req.userId, package_name, app_name, enabled === false ? 0 : 1, app_name, enabled === false ? 0 : 1);

  res.status(201).json({
    message: "Filter saved",
    filter: { package_name, app_name, enabled: enabled !== false },
  });
});

// 批量设置过滤器（替换整个列表）
router.post("/batch", authMiddleware, (req, res) => {
  const { filters } = req.body;
  if (!Array.isArray(filters)) {
    return res.status(400).json({ error: "filters must be an array" });
  }

  const db = getDB();
  const insert = db.prepare(`INSERT INTO app_filters (user_id, package_name, app_name, enabled) VALUES (?, ?, ?, ?)
                              ON CONFLICT(user_id, package_name) DO UPDATE SET app_name = ?, enabled = ?`);

  const txn = db.transaction(() => {
    // 先删除不在新列表中的
    const packageNames = filters.map((f) => f.package_name).filter(Boolean);
    if (packageNames.length > 0) {
      const placeholders = packageNames.map(() => "?").join(",");
      db.prepare(`DELETE FROM app_filters WHERE user_id = ? AND package_name NOT IN (${placeholders})`).run(req.userId, ...packageNames);
    } else {
      db.prepare("DELETE FROM app_filters WHERE user_id = ?").run(req.userId);
    }

    // 插入/更新
    for (const f of filters) {
      if (f.package_name && f.app_name) {
        insert.run(req.userId, f.package_name, f.app_name, f.enabled === false ? 0 : 1, f.app_name, f.enabled === false ? 0 : 1);
      }
    }
  });

  txn();
  res.json({ message: "Filters updated", count: filters.length });
});

// 删除过滤器
router.delete("/:package_name", authMiddleware, (req, res) => {
  const db = getDB();
  // URL 中的 package_name 可能包含点号，需要解码
  const packageName = decodeURIComponent(req.params.package_name);
  const result = db.prepare("DELETE FROM app_filters WHERE user_id = ? AND package_name = ?").run(req.userId, packageName);
  if (result.changes === 0) {
    return res.status(404).json({ error: "Filter not found" });
  }
  res.json({ message: "Filter deleted" });
});

module.exports = router;
