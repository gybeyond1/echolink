const express = require("express");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const crypto = require("crypto");
const { getDB } = require("../db");
const { authMiddleware } = require("../middleware/auth");

const router = express.Router();

// 注册
router.post("/register", (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: "Username and password are required" });
  }
  if (username.length < 3 || username.length > 32) {
    return res.status(400).json({ error: "Username must be 3-32 characters" });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: "Password must be at least 6 characters" });
  }

  const db = getDB();

  // 检查用户名是否已存在
  const existing = db.prepare("SELECT id FROM users WHERE username = ?").get(username);
  if (existing) {
    return res.status(409).json({ error: "Username already exists" });
  }

  const passwordHash = bcrypt.hashSync(password, 10);
  const result = db.prepare("INSERT INTO users (username, password_hash) VALUES (?, ?)").run(username, passwordHash);

  // 新用户自动拥有「我的设备」默认会话（置顶、不可删）
  try { require("./topics").ensureDeviceTopic(result.lastInsertRowid); } catch (e) {
    console.error("[auth] ensureDeviceTopic failed:", e);
  }

  const token = jwt.sign(
    { userId: result.lastInsertRowid, username, role: "user" },
    process.env.JWT_SECRET || "default-secret",
    { expiresIn: "365d" }
  );

  res.status(201).json({
    token,
    user: { id: result.lastInsertRowid, username, role: "user" },
  });
});

// 登录
router.post("/login", (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: "Username and password are required" });
  }

  const db = getDB();
  const user = db.prepare("SELECT * FROM users WHERE username = ?").get(username);
  if (!user) {
    return res.status(401).json({ error: "Invalid username or password" });
  }

  if (!bcrypt.compareSync(password, user.password_hash)) {
    return res.status(401).json({ error: "Invalid username or password" });
  }

  // 登录即确保「我的设备」默认会话存在（老账号兜底）
  try { require("./topics").ensureDeviceTopic(user.id); } catch (e) {
    console.error("[auth] ensureDeviceTopic failed:", e);
  }

  const token = jwt.sign(
    { userId: user.id, username: user.username, role: user.role || "user" },
    process.env.JWT_SECRET || "default-secret",
    { expiresIn: "365d" }
  );

  res.json({
    token,
    user: { id: user.id, username: user.username, role: user.role || "user" },
  });
});

// 验证 token 有效性
router.get("/me", authMiddleware, (req, res) => {
  res.json({ user: { id: req.userId, username: req.username, role: req.role } });
});

// 修改密码
router.post("/change-password", authMiddleware, (req, res) => {
  const { oldPassword, newPassword } = req.body;
  if (!oldPassword || !newPassword) {
    return res.status(400).json({ error: "Old password and new password are required" });
  }
  if (newPassword.length < 6) {
    return res.status(400).json({ error: "New password must be at least 6 characters" });
  }

  const db = getDB();
  const user = db.prepare("SELECT * FROM users WHERE id = ?").get(req.userId);
  if (!bcrypt.compareSync(oldPassword, user.password_hash)) {
    return res.status(401).json({ error: "Old password is incorrect" });
  }

  const newHash = bcrypt.hashSync(newPassword, 10);
  db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(newHash, req.userId);

  res.json({ message: "Password changed successfully" });
});

module.exports = router;
