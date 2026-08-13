const Database = require("better-sqlite3");
const path = require("path");
const fs = require("fs");

let db;

function initDB() {
  const dbPath = process.env.DB_PATH || "./data/notifysync.db";
  const dir = path.dirname(dbPath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  db = new Database(dbPath);
  db.pragma("journal_mode = WAL");

  // 用户表
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  // 设备表
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      device_name TEXT NOT NULL,
      device_token TEXT UNIQUE NOT NULL,
      platform TEXT DEFAULT 'android',
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      last_seen DATETIME,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // 通知表
  db.exec(`
    CREATE TABLE IF NOT EXISTS notifications (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      device_id INTEGER,
      package_name TEXT NOT NULL,
      app_name TEXT NOT NULL,
      title TEXT,
      text TEXT,
      timestamp INTEGER NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    )
  `);

  // 应用过滤器表（用户选择同步哪些应用的通知）
  db.exec(`
    CREATE TABLE IF NOT EXISTS app_filters (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      package_name TEXT NOT NULL,
      app_name TEXT NOT NULL,
      enabled INTEGER DEFAULT 1,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(user_id, package_name)
    )
  `);

  // 公共话题消息表（类似 ntfy 的 topic）
  db.exec(`
    CREATE TABLE IF NOT EXISTS topic_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      topic TEXT NOT NULL,
      user_id INTEGER,
      device_id INTEGER,
      sender_name TEXT,
      title TEXT,
      text TEXT,
      timestamp INTEGER NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  // 索引
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id);
    CREATE INDEX IF NOT EXISTS idx_notifications_timestamp ON notifications(timestamp);
    CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id);
    CREATE INDEX IF NOT EXISTS idx_filters_user_id ON app_filters(user_id);
    CREATE INDEX IF NOT EXISTS idx_topic_messages_topic ON topic_messages(topic, timestamp);
  `);

  console.log("[DB] SQLite initialized at", dbPath);
  return db;
}

function getDB() {
  if (!db) initDB();
  return db;
}

module.exports = { initDB, getDB };
