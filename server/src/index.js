require("dotenv").config();

const express = require("express");
const cors = require("cors");
const http = require("http");
const { initDB } = require("./db");
const { setupWebSocket } = require("./websocket");

const authRoutes = require("./routes/auth");
const deviceRoutes = require("./routes/devices");
const notificationRoutes = require("./routes/notifications");
const filterRoutes = require("./routes/filters");
const topicRoutes = require("./routes/topics");

const app = express();
const server = http.createServer(app);

// 中间件
app.use(cors());
app.use(express.json({ limit: "1mb" }));

// 请求日志
app.use((req, res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);
  next();
});

// 健康检查
app.get("/health", (req, res) => {
  res.json({ status: "ok", timestamp: Date.now() });
});

// API 路由
app.use("/api/auth", authRoutes);
app.use("/api/devices", deviceRoutes);
app.use("/api/notifications", notificationRoutes);
app.use("/api/filters", filterRoutes);
app.use("/api/topics", topicRoutes);

// 错误处理
app.use((err, req, res, next) => {
  console.error("[ERROR]", err);
  res.status(500).json({ error: "Internal server error" });
});

// 404
app.use((req, res) => {
  res.status(404).json({ error: "Not found" });
});

// 初始化数据库
initDB();

// 启动 WebSocket
setupWebSocket(server);

// 启动 HTTP 服务
const PORT = process.env.PORT || 3000;
const HOST = "0.0.0.0";

server.listen(PORT, HOST, () => {
  console.log(`\n========================================`);
  console.log(`  NotifySync Server`);
  console.log(`  HTTP:  http://${HOST}:${PORT}`);
  console.log(`  WS:    ws://${HOST}:${PORT}/ws`);
  console.log(`========================================\n`);
});
