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
      media_type TEXT,       -- 'text' | 'voice' | 'image' | 'file'
      media_url TEXT,
      media_name TEXT,
      media_size INTEGER,
      timestamp INTEGER NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  // 服务器设置（文件/图片/语音大小上限、话题历史上限等），key/value 存储
  db.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT
    )
  `);

  // 话题消息「软删除」标记表（per-user）：某用户删除某条消息只在本侧隐藏，不影响他人。
  // 当该话题全部成员都软删除同一条消息时，由删除路由物理清除该消息。
  db.exec(`
    CREATE TABLE IF NOT EXISTS topic_message_deletes (
      user_id INTEGER NOT NULL,
      message_id INTEGER NOT NULL,
      PRIMARY KEY (user_id, message_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      FOREIGN KEY (message_id) REFERENCES topic_messages(id) ON DELETE CASCADE
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
      role TEXT NOT NULL DEFAULT 'member', -- 'owner' | 'member' | 'admin'
      joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      last_read_id INTEGER DEFAULT 0,      -- 每个用户在该话题读到的最后一条消息 id
      PRIMARY KEY (topic_id, user_id),
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);
  // 兼容旧库：已有 topic_members 表但无 last_read_id 列时补上
  const tmMemberCols = db.pragma("table_info(topic_members)").map((c) => c.name);
  if (!tmMemberCols.includes("last_read_id")) {
    db.exec("ALTER TABLE topic_members ADD COLUMN last_read_id INTEGER DEFAULT 0");
  }

  db.exec(`
    CREATE TABLE IF NOT EXISTS topic_join_requests (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      topic_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', -- 'pending' | 'approved' | 'rejected' | 'ignored'
      message TEXT,
      requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      handled_at DATETIME,
      FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(topic_id, user_id)
    )
  `);

  // ===== 好友系统 =====
  db.exec(`
    CREATE TABLE IF NOT EXISTS friends (
      user_id INTEGER NOT NULL,
      friend_id INTEGER NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (user_id, friend_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
      FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  db.exec(`
    CREATE TABLE IF NOT EXISTS friend_requests (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      from_user INTEGER NOT NULL,
      to_user INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', -- 'pending' | 'accepted' | 'rejected' | 'ignored'
      message TEXT,
      requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      handled_at DATETIME,
      FOREIGN KEY (from_user) REFERENCES users(id) ON DELETE CASCADE,
      FOREIGN KEY (to_user) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(from_user, to_user)
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

  // 话题消息媒体列 + 设备名列（兼容旧库）
  const tmCols = db.pragma("table_info(topic_messages)").map((c) => c.name);
  ["media_type", "media_url", "media_name", "media_size", "device_name"].forEach((c) => {
    if (!tmCols.includes(c)) {
      db.exec(`ALTER TABLE topic_messages ADD COLUMN ${c} TEXT`);
    }
  });
  // 已读回执列：dm 私聊里对方是否已读（仅 dm 使用）
  if (!tmCols.includes("read")) {
    db.exec("ALTER TABLE topic_messages ADD COLUMN read INTEGER DEFAULT 0");
  }

  // 话题类型列：normal=普通群聊 | devices=同账号设备默认会话（置顶不可删）| dm=好友两人私聊
  const topicCols = db.pragma("table_info(topics)").map((c) => c.name);
  if (!topicCols.includes("kind")) {
    db.exec("ALTER TABLE topics ADD COLUMN kind TEXT NOT NULL DEFAULT 'normal'");
  }

  // 用户资料列：display_name(昵称) + avatar(头像文件路径)
  // 昵称跟用户名走（全账号同步），设备名各自独立
  if (!userCols.includes("display_name")) {
    db.exec("ALTER TABLE users ADD COLUMN display_name TEXT");
  }
  if (!userCols.includes("avatar")) {
    db.exec("ALTER TABLE users ADD COLUMN avatar TEXT");
  }

  // 一次性迁移：默认放开媒体大小限制（0 = 不限制）。
  // 用户之后仍可在 Web 管理后台自行设置具体上限。
  const marker = db.prepare("SELECT value FROM settings WHERE key = 'migrated_unlimited_media'").get();
  if (!marker) {
    ["max_image_size", "max_voice_size", "max_file_size"].forEach((k) => {
      db.prepare("INSERT INTO settings (key, value) VALUES (?, '0') ON CONFLICT(key) DO UPDATE SET value = '0'").run(k);
    });
    db.prepare("INSERT INTO settings (key, value) VALUES ('migrated_unlimited_media', '1') ON CONFLICT(key) DO UPDATE SET value = '1'").run();
    console.log("[DB] Media size limits reset to 0 (unlimited); adjust in admin WebUI if needed");
  }

  console.log("[DB] SQLite initialized at", dbPath);
  return db;
}

// ===== 服务器设置（带内存缓存）=====
const SETTINGS_DEFAULTS = {
  max_image_size: "0", // MB，0 = 不限制
  max_voice_size: "0", // MB，0 = 不限制
  max_file_size: "0",  // MB，0 = 不限制
  max_topic_history: "200",
};

let settingsCache = null;

function loadSettingsCache() {
  const database = getDB();
  const rows = database.prepare("SELECT key, value FROM settings").all();
  const map = {};
  for (const r of rows) map[r.key] = r.value;
  for (const k of Object.keys(SETTINGS_DEFAULTS)) {
    if (!(k in map)) {
      database.prepare("INSERT INTO settings (key, value) VALUES (?, ?)").run(k, SETTINGS_DEFAULTS[k]);
      map[k] = SETTINGS_DEFAULTS[k];
    }
  }
  settingsCache = map;
  return map;
}

function getSettings() {
  if (!settingsCache) loadSettingsCache();
  return settingsCache;
}

function getSetting(key) {
  const s = getSettings();
  return key in s ? s[key] : SETTINGS_DEFAULTS[key];
}

// 返回某类媒体的大小上限（字节）；kind: image|voice|file
// 返回 0 表示不限制
function getMediaLimitBytes(kind) {
  const raw = getSetting(`max_${kind}_size`);
  const mb = parseFloat(raw) || 0;
  if (mb <= 0) return 0;
  return Math.round(mb * 1024 * 1024);
}

function setSettings(patch) {
  const database = getDB();
  const map = {};
  for (const k of Object.keys(SETTINGS_DEFAULTS)) {
    if (k in patch && patch[k] != null) {
      const v = String(patch[k]);
      database.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(k, v);
      map[k] = v;
    }
  }
  if (!settingsCache) loadSettingsCache();
  Object.assign(settingsCache, map);
  return getSettings();
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

module.exports = { initDB, getDB, seedAdmin, getSettings, getSetting, getMediaLimitBytes, setSettings };
