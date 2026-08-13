const Database = require("better-sqlite3");
const path = require("path");
const fs = require("fs");
const bcrypt = require("bcryptjs");

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

  // 通知按设备软删除表（同一账号多设备：某设备"清空/删除"只对该设备隐藏，不影响其他设备）
  db.exec(`
    CREATE TABLE IF NOT EXISTS notification_deletes (
      user_id INTEGER NOT NULL,
      device_id INTEGER NOT NULL,
      notification_id INTEGER NOT NULL,
      PRIMARY KEY (user_id, device_id, notification_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // ===== 话题（群聊）模型 =====
  // 话题是"群聊"：由创建者(owner)拥有，其他人需经 owner 审批后才能加入(成员)
  db.exec(`
    CREATE TABLE IF NOT EXISTS topics (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT UNIQUE NOT NULL,
      owner_id INTEGER NOT NULL,
      title TEXT,
      description TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  db.exec(`
    CREATE TABLE IF NOT EXISTS topic_members (
      topic_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      role TEXT NOT NULL DEFAULT 'member', -- 'owner' | 'member'
      joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (topic_id, user_id),
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  db.exec(`
    CREATE TABLE IF NOT EXISTS topic_join_requests (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      topic_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', -- 'pending' | 'approved' | 'rejected'
      message TEXT,
      requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      handled_at DATETIME,
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(topic_id, user_id)
    )
  `);

  // 索引
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id);
    CREATE INDEX IF NOT EXISTS idx_notifications_timestamp ON notifications(timestamp);
    CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id);
    CREATE INDEX IF NOT EXISTS idx_filters_user_id ON app_filters(user_id);
    CREATE INDEX IF NOT EXISTS idx_topic_messages_topic ON topic_messages(topic, timestamp);
    CREATE INDEX IF NOT EXISTS idx_topics_name ON topics(name);
    CREATE INDEX IF NOT EXISTS idx_topic_members_user ON topic_members(user_id);
    CREATE INDEX IF NOT EXISTS idx_topic_requests_topic ON topic_join_requests(topic_id, status);
  `);

  // 每个物理设备安装时生成稳定 client_id，用于"按设备"持久化（同一设备重登录保持同一 device_id）
  const devCols = db.pragma("table_info(devices)").map((c) => c.name);
  if (!devCols.includes("client_id")) {
    db.exec("ALTER TABLE devices ADD COLUMN client_id TEXT");
  }
  db.exec(
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_user_client ON devices(user_id, client_id)"
  );

  // 用户角色列（普通用户 / 管理员）。兼容旧库：列可能不存在
  const userCols = db.pragma("table_info(users)").map((c) => c.name);
  if (!userCols.includes("role")) {
    db.exec("ALTER TABLE users ADD COLUMN role TEXT DEFAULT 'user'");
  }

  console.log("[DB] SQLite initialized at", dbPath);
  return db;
}

// 由环境变量 ADMIN_USERNAME / ADMIN_PASSWORD 初始化/维护管理员账号
function seedAdmin() {
  const username = process.env.ADMIN_USERNAME;
  const password = process.env.ADMIN_PASSWORD;
  if (!username || !password) {
    return; // 未提供管理员环境变量时跳过（不强制）
  }
  const database = getDB();
  const existing = database
    .prepare("SELECT id FROM users WHERE username = ?")
    .get(username);
  const hash = bcrypt.hashSync(password, 10);
  if (!existing) {
    database
      .prepare("INSERT INTO users (username, password_hash, role) VALUES (?, ?, 'admin')")
      .run(username, hash);
    console.log(`[DB] Seeded admin user "${username}"`);
  } else {
    // 确保角色为 admin，并同步密码（方便通过 compose 修改管理员密码）
    database
      .prepare("UPDATE users SET password_hash = ?, role = 'admin' WHERE id = ?")
      .run(hash, existing.id);
    console.log(`[DB] Admin user "${username}" present — ensured admin role & password`);
  }
}

function getDB() {
  if (!db) initDB();
  return db;
}

module.exports = { initDB, getDB, seedAdmin };
