const express = require("express");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");
const { broadcastToUser } = require("../websocket");

const router = express.Router();

// 头像上传目录：与数据库同级的 uploads/avatars/
const UPLOAD_ROOT = path.join(
  process.env.DB_PATH ? path.dirname(process.env.DB_PATH) : "./data",
  "uploads", "avatars"
);
if (!fs.existsSync(UPLOAD_ROOT)) {
  fs.mkdirSync(UPLOAD_ROOT, { recursive: true });
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_ROOT),
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname || "").toLowerCase() || ".png";
    cb(null, `avatar_${req.userId}_${Date.now()}${ext}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: 5 * 1024 * 1024 }, // 5MB 上限
  fileFilter: (req, file, cb) => {
    if (file.mimetype.startsWith("image/")) cb(null, true);
    else cb(new Error("Only image files are allowed"));
  },
});

// 获取当前用户资料
// GET /api/user/profile
router.get("/profile", authMiddleware, (req, res) => {
  const db = getDB();
  const user = db
    .prepare("SELECT id, username, display_name, avatar FROM users WHERE id = ?")
    .get(req.userId);
  if (!user) return res.status(404).json({ error: "User not found" });
  res.json({
    id: user.id,
    username: user.username,
    display_name: user.display_name || user.username,
    avatar: user.avatar || null,
  });
});

// 修改昵称
// PUT /api/user/nickname  body: { display_name }
router.put("/nickname", authMiddleware, (req, res) => {
  const name = String(req.body.display_name || "").trim();
  if (!name) return res.status(400).json({ error: "昵称不能为空" });
  if (name.length > 32) return res.status(400).json({ error: "昵称最多 32 个字符" });

  const db = getDB();
  db.prepare("UPDATE users SET display_name = ? WHERE id = ?").run(name, req.userId);

  // WS 推送：通知该用户所有设备刷新昵称
  try {
    broadcastToUser(req.userId, {
      type: "profile_updated",
      data: { display_name: name },
    });
  } catch (e) { /* ignore */ }

  res.json({ display_name: name });
});

// 上传头像
// POST /api/user/avatar  (multipart: file=...)
router.post("/avatar", authMiddleware, (req, res) => {
  upload.single("file")(req, res, (err) => {
    if (err) return res.status(400).json({ error: err.message });
    if (!req.file) return res.status(400).json({ error: "请选择图片" });

    const db = getDB();
    // 删除旧头像文件
    const old = db.prepare("SELECT avatar FROM users WHERE id = ?").get(req.userId);
    if (old && old.avatar) {
      const oldPath = path.join(UPLOAD_ROOT, path.basename(old.avatar));
      try { fs.unlinkSync(oldPath); } catch (e) { /* ignore */ }
    }

    const avatarPath = `/uploads/avatars/${encodeURIComponent(req.file.filename)}`;
    db.prepare("UPDATE users SET avatar = ? WHERE id = ?").run(avatarPath, req.userId);

    // WS 推送
    try {
      broadcastToUser(req.userId, {
        type: "profile_updated",
        data: { avatar: avatarPath },
      });
    } catch (e) { /* ignore */ }

    res.json({ avatar: avatarPath });
  });
});

// 获取指定用户的头像（公开，用于好友列表等）
// GET /api/user/avatar/:userId
router.get("/avatar/:userId", (req, res) => {
  const db = getDB();
  const user = db.prepare("SELECT avatar FROM users WHERE id = ?").get(parseInt(req.params.userId));
  if (!user || !user.avatar) return res.status(404).json({ error: "No avatar" });
  res.json({ avatar: user.avatar });
});

module.exports = router;
