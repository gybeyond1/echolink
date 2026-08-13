require("dotenv").config();

const express = require("express");
const cors = require("cors");
const http = require("http");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { initDB, seedAdmin, getSettings } = require("./db");
const { setupWebSocket } = require("./websocket");

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

// ---- JWT_SECRET 自动生成并持久化（无需在终端设置）----
function getDataDir() {
  const dbPath = process.env.DB_PATH || "./data/notifysync.db";
  return path.dirname(dbPath);
}
function loadJwtSecret() {
  const PLACEHOLDER = "change-this-to-a-random-secret-key";
  const envSecret = process.env.JWT_SECRET;
  const envOk = envSecret && envSecret.length >= 16 && envSecret !== PLACEHOLDER;
  if (envOk) return envSecret; // 显式提供的强密钥优先

  const dir = getDataDir();
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  const secretFile = path.join(dir, ".jwt_secret");
  if (fs.existsSync(secretFile)) {
    try {
      const loaded = fs.readFileSync(secretFile, "utf8").trim();
      if (loaded) return loaded;
    } catch (e) { /* ignore */ }
  }
  // 首次运行：生成并持久化，重启后保持稳定
  const generated = crypto.randomBytes(48).toString("hex");
  try { fs.writeFileSync(secretFile, generated, { mode: 0o600 }); } catch (e) { /* ignore */ }
  console.log("[JWT] auto-generated secret persisted to", secretFile);
  return generated;
}
process.env.JWT_SECRET=***REDACTED***

// 健康检查
app.get("/health", (req, res) => {
  res.json({ status: "ok", timestamp: Date.now() });
});

// 公开信息（WebUI 概览用）
app.get("/api/info", (req, res) => {
  let version = "1.0.0";
  try { version = require("../package.json").version; } catch (e) { /* ignore */ }
  res.json({
    name: "NotifySync",
    version,
    uptime: Math.floor(process.uptime()),
    dataDir: getDataDir(),
  });
});

// API 路由
app.use("/api/auth", require("./routes/auth"));
app.use("/api/devices", require("./routes/devices"));
app.use("/api/notifications", require("./routes/notifications"));
app.use("/api/filters", require("./routes/filters"));
app.use("/api/topics", require("./routes/topics"));
app.use("/api/admin", require("./routes/admin"));

// 静态管理界面（WebUI）
const publicDir = path.join(__dirname, "..", "public");
if (fs.existsSync(publicDir)) {
  app.use(express.static(publicDir));
  // 上传的媒体文件（图片/语音/附件）静态可访问
  const uploadsDir = path.join(getDataDir(), "uploads");
  if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
  app.use("/uploads", express.static(uploadsDir));
  // 未匹配的非 API 路径都回退到 index.html（单页应用）
  app.get(/^(?!\/api\/).*/, (req, res) => {
    res.sendFile(path.join(publicDir, "index.html"));
  });
}

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
// 加载服务器设置缓存（大小上限等）
try {
  getSettings();
  console.log("[Settings] loaded");
} catch (e) {
  console.error("[Settings] load error:", e);
}
// 根据环境变量初始化/维护管理员账号
try {
  seedAdmin();
} catch (e) {
  console.error("[DB] seedAdmin error:", e);
}

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
  console.log(`  WebUI: http://${HOST}:${PORT}/`);
  console.log(`========================================\n`);
});
