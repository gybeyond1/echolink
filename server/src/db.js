/**
 * 数据库模块
 * 使用 better-sqlite3 进行数据库操作
 */

const Database = require("better-sqlite3");
const path = require("path");
const fs = require("fs");

let db = null;

function getDataDir() {
  // 优先使用环境变量指定的数据目录
  if (process.env.DATA_DIR) {
    return process.env.DATA_DIR;
  }
  // 默认使用项目根目录下的 data 文件夹
  return path.join(__dirname, "..", "data");
}

function getDB() {
  if (!db) {
    const dataDir = getDataDir();
    if (!fs.existsSync(dataDir)) {
      fs.mkdirSync(dataDir, { recursive: true });
    }
    const dbPath = path.join(dataDir, "echolink.db");
    db = new Database(dbPath);
    db.pragma("journal_mode = WAL");
    db.pragma("foreign_keys = ON");
    initTables();
  }
  return db;
}

function initTables() {
  const d = getDB();

  // 用户表
  d.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      display_name TEXT,
      avatar TEXT,
      role TEXT DEFAULT 'user',
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  // 设备表
  d.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      device_name TEXT NOT NULL,
      platform TEXT,
      device_token TEXT,
      last_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 话题表
  d.exec(`
    CREATE TABLE IF NOT EXISTS topics (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT UNIQUE NOT NULL,
      title TEXT,
      owner_id INTEGER,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE SET NULL
    )
  `);

  // 话题成员表
  d.exec(`
    CREATE TABLE IF NOT EXISTS topic_members (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      topic_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      role TEXT DEFAULT 'member',
      joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      UNIQUE(topic_id, user_id),
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 消息表
  d.exec(`
    CREATE TABLE IF NOT EXISTS messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      topic_id INTEGER NOT NULL,
      sender_id INTEGER,
      sender_name TEXT,
      text TEXT,
      image TEXT,
      voice TEXT,
      file TEXT,
      file_name TEXT,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL
    )
  `);

  // 通知表
  d.exec(`
    CREATE TABLE IF NOT EXISTS notifications (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      app_name TEXT,
      package_name TEXT,
      title TEXT,
      text TEXT,
      device_name TEXT,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 好友关系表
  d.exec(`
    CREATE TABLE IF NOT EXISTS friends (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      friend_id INTEGER NOT NULL,
      status TEXT DEFAULT 'pending',
      message TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      UNIQUE(user_id, friend_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 应用过滤表
  d.exec(`
    CREATE TABLE IF NOT EXISTS filters (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      package_name TEXT NOT NULL,
      app_name TEXT,
      enabled INTEGER DEFAULT 1,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 服务器设置表
  d.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      max_image_size REAL DEFAULT 10,
      max_voice_size REAL DEFAULT 5,
      max_file_size REAL DEFAULT 20,
      max_topic_history INTEGER DEFAULT 200,
      messagewall_sync_url TEXT,
      totp_secret TEXT,
      totp_enabled INTEGER DEFAULT 0
    )
  `);

  // 确保设置表有一条记录
  const settingCount = d.prepare("SELECT COUNT(*) as count FROM settings").get();
  if (settingCount.count === 0) {
    d.prepare("INSERT INTO settings (id) VALUES (1)").run();
  }

  // MoviePilot 通道表
  d.exec(`
    CREATE TABLE IF NOT EXISTS moviepilot_channels (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER UNIQUE NOT NULL,
      username TEXT,
      display_name TEXT,
      enabled INTEGER DEFAULT 1,
      token TEXT,
      public_base_url TEXT,
      mp_api_key TEXT,
      callback_url TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 为 moviepilot_channels 表添加 Telegram 相关字段（兼容旧数据库）
  // 使用 try/catch 是因为如果字段已经存在，ALTER TABLE 会报错
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN channel_mode TEXT DEFAULT 'direct'");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_bot_token TEXT");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_chat_id TEXT");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_enabled INTEGER DEFAULT 0");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_type TEXT DEFAULT 'http'");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_host TEXT");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_port INTEGER");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_username TEXT");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_proxy_password TEXT");
  } catch (e) {}
  try {
    d.exec("ALTER TABLE moviepilot_channels ADD COLUMN telegram_last_update_id INTEGER DEFAULT 0");
  } catch (e) {}

  // 留言板目标用户表
  d.exec(`
    CREATE TABLE IF NOT EXISTS messagewall_targets (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER UNIQUE NOT NULL,
      enabled INTEGER DEFAULT 1,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 创建索引
  d.exec("CREATE INDEX IF NOT EXISTS idx_messages_topic ON messages(topic_id)");
  d.exec("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id)");
  d.exec("CREATE INDEX IF NOT EXISTS idx_topic_members_user ON topic_members(user_id)");
  d.exec("CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id)");
}

// 根据用户名获取用户 ID
function getUserIdByUsername(username) {
  const d = getDB();
  const user = d.prepare("SELECT id FROM users WHERE username = ?").get(username);
  return user ? user.id : null;
}

// 根据用户 ID 获取用户名
function getUsernameById(userId) {
  const d = getDB();
  const user = d.prepare("SELECT username FROM users WHERE id = ?").get(userId);
  return user ? user.username : null;
}

module.exports = {
  getDB,
  getDataDir,
  initTables,
  getUserIdByUsername,
  getUsernameById,
};
