/**
 * EchoLink 服务器入口
 * 跨设备消息互联 · 通知同步 · 好友
 */

const express = require("express");
const cors = require("cors");
const path = require("path");
const fs = require("fs");

const { getDB, initTables } = require("./db");
const telegramBridge = require("./telegram_bridge");

const app = express();
const PORT = process.env.PORT || 3000;

// 中间件
app.use(cors());
app.use(express.json({ limit: "100mb" }));
app.use(express.urlencoded({ extended: true, limit: "100mb" }));

// 静态文件服务
const publicDir = path.join(__dirname, "public");
if (fs.existsSync(publicDir)) {
  app.use(express.static(publicDir));
}

// 上传目录
const uploadDir = path.join(__dirname, "..", "data", "uploads");
if (!fs.existsSync(uploadDir)) {
  fs.mkdirSync(uploadDir, { recursive: true });
}
app.use("/uploads", express.static(uploadDir));

// 初始化数据库
const db = getDB();
initTables();

// API 路由
app.use("/api/auth", require("./routes/auth"));
app.use("/api/users", require("./routes/users"));
app.use("/api/devices", require("./routes/devices"));
app.use("/api/topics", require("./routes/topics"));
app.use("/api/messages", require("./routes/messages"));
app.use("/api/notifications", require("./routes/notifications"));
app.use("/api/friends", require("./routes/friends"));
app.use("/api/filters", require("./routes/filters"));
app.use("/api/admin", require("./routes/admin"));
app.use("/api/webhook", require("./routes/webhook"));
app.use("/api/moviepilot", require("./routes/moviepilot"));

// 根路径重定向到前端
app.get("/", (req, res) => {
  res.sendFile(path.join(publicDir, "index.html"));
});

// 健康检查
app.get("/api/health", (req, res) => {
  res.json({ ok: true, status: "running", timestamp: new Date().toISOString() });
});

// 服务器信息
app.get("/api/info", (req, res) => {
  const uptime = process.uptime();
  res.json({
    name: "EchoLink",
    version: "1.0.0",
    uptime: uptime,
    dataDir: path.join(__dirname, "..", "data"),
  });
});

// 启动服务器
const server = app.listen(PORT, () => {
  console.log(`\n========================================`);
  console.log(`  EchoLink 服务器已启动`);
  console.log(`  端口: ${PORT}`);
  console.log(`  访问: http://localhost:${PORT}`);
  console.log(`========================================\n`);

  // 启动所有 Telegram 模式用户的长轮询监控
  // 异步启动，不阻塞服务器启动
  setTimeout(() => {
    try {
      telegramBridge.startAllPolling();
    } catch (e) {
      console.error("启动 Telegram 监控失败:", e.message);
    }
  }, 1000);
});

// 优雅关闭
process.on("SIGTERM", () => {
  console.log("收到 SIGTERM，正在关闭服务器...");
  server.close(() => {
    console.log("服务器已关闭");
    process.exit(0);
  });
});

process.on("SIGINT", () => {
  console.log("\n收到 SIGINT，正在关闭服务器...");
  server.close(() => {
    console.log("服务器已关闭");
    process.exit(0);
  });
});

module.exports = { app, server };
