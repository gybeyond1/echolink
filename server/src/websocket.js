const { WebSocketServer } = require("ws");
const jwt = require("jsonwebtoken");
const { getDB } = require("./db");

// userId -> Set<WebSocket>
const userConnections = new Map();
// topic -> Set<WebSocket>
const topicSubscriptions = new Map();

// 话题名校验：小写字母、数字、下划线、连字符，1-64 位
function normalizeTopic(topic) {
  if (typeof topic !== "string") return null;
  const name = topic.trim().toLowerCase();
  if (!/^[a-z0-9_-]{1,64}$/.test(name)) return null;
  return name;
}

function setupWebSocket(server) {
  const wss = new WebSocketServer({ server, path: "/ws" });

  wss.on("connection", (ws, req) => {
    // 从 URL 查询参数中获取 token 和 device_id
    const url = new URL(req.url, "http://localhost");
    const token = url.searchParams.get("token");
    const deviceIdRaw = url.searchParams.get("device_id");

    if (!token) {
      ws.close(4001, "Missing token");
      return;
    }

    let userId;
    try {
      const decoded = jwt.verify(token, process.env.JWT_SECRET || "default-secret");
      userId = decoded.userId;
    } catch (err) {
      ws.close(4003, "Invalid token");
      return;
    }

    ws.userId = userId;
    ws.deviceId = deviceIdRaw ? parseInt(deviceIdRaw, 10) || null : null;
    ws.isAlive = true;
    ws.subscribedTopics = new Set();

    // 加入用户连接池
    if (!userConnections.has(userId)) {
      userConnections.set(userId, new Set());
    }
    userConnections.get(userId).add(ws);

    console.log(`[WS] Device connected for user ${userId} (device ${ws.deviceId})`);

    // 发送连接确认
    ws.send(
      JSON.stringify({
        type: "connected",
        message: "WebSocket connected",
        device_id: ws.deviceId,
      })
    );

    // 心跳
    ws.on("pong", () => {
      ws.isAlive = true;
    });

    ws.on("message", (message) => {
      try {
        const data = JSON.parse(message.toString());
        if (data.type === "ping") {
          ws.send(JSON.stringify({ type: "pong" }));
          return;
        }
        if (data.type === "subscribe" && data.topic) {
          subscribeToTopic(ws, data.topic);
          return;
        }
        if (data.type === "unsubscribe" && data.topic) {
          unsubscribeFromTopic(ws, data.topic);
          return;
        }
        if (data.type === "publish" && data.topic) {
          handleTopicPublish(ws, data);
          return;
        }
      } catch (e) {
        // 忽略无法解析的消息
      }
    });

    ws.on("close", () => {
      // 移出用户连接池
      const conns = userConnections.get(userId);
      if (conns) {
        conns.delete(ws);
        if (conns.size === 0) {
          userConnections.delete(userId);
        }
      }
      // 移出所有话题订阅
      ws.subscribedTopics.forEach((t) => {
        const subs = topicSubscriptions.get(t);
        if (subs) {
          subs.delete(ws);
          if (subs.size === 0) topicSubscriptions.delete(t);
        }
      });
      ws.subscribedTopics.clear();
      console.log(`[WS] Device disconnected for user ${userId}`);
    });

    ws.on("error", (err) => {
      console.error(`[WS] Error for user ${userId}:`, err.message);
    });
  });

  // 定期检查死连接
  const interval = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (!ws.isAlive) {
        ws.terminate();
        return;
      }
      ws.isAlive = false;
      ws.ping();
    });
  }, 30000);

  wss.on("close", () => {
    clearInterval(interval);
  });

  console.log("[WS] WebSocket server ready at /ws");
  return wss;
}

// ===== 话题订阅/发布 =====

function subscribeToTopic(ws, topic) {
  const name = normalizeTopic(topic);
  if (!name) {
    ws.send(JSON.stringify({ type: "error", message: "Invalid topic name" }));
    return;
  }
  if (!topicSubscriptions.has(name)) topicSubscriptions.set(name, new Set());
  topicSubscriptions.get(name).add(ws);
  ws.subscribedTopics.add(name);
  console.log(`[WS] User ${ws.userId} subscribed to topic "${name}"`);
  ws.send(JSON.stringify({ type: "subscribed", topic: name }));
}

function unsubscribeFromTopic(ws, topic) {
  const name = normalizeTopic(topic);
  if (!name) return;
  const subs = topicSubscriptions.get(name);
  if (subs) {
    subs.delete(ws);
    if (subs.size === 0) topicSubscriptions.delete(name);
  }
  ws.subscribedTopics.delete(name);
  console.log(`[WS] User ${ws.userId} unsubscribed from topic "${name}"`);
}

function handleTopicPublish(ws, data) {
  const name = normalizeTopic(data.topic);
  if (!name) {
    ws.send(JSON.stringify({ type: "error", message: "Invalid topic name" }));
    return;
  }

  const db = getDB();
  const ts = Date.now();
  const title = String(data.title || "").slice(0, 500);
  const text = String(data.text || "").slice(0, 2000);
  const sender = String(data.sender_name || `user-${ws.userId}`).slice(0, 64);

  const result = db
    .prepare(`INSERT INTO topic_messages (topic, user_id, device_id, sender_name, title, text, timestamp)
              VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .run(name, ws.userId, ws.deviceId, sender, title, text, ts);

  const message = {
    id: result.lastInsertRowid,
    topic: name,
    title,
    text,
    sender_name: sender,
    timestamp: ts,
    device_id: ws.deviceId,
  };

  // 推送给订阅者，排除发送者本人连接（本机去重）
  publishToTopic(name, message, { excludeWs: ws });

  // 清理旧消息
  cleanupTopicHistory(name);
}

// 向话题订阅者广播消息（可选排除某个连接或某个设备）
function publishToTopic(topic, message, opts) {
  const subs = topicSubscriptions.get(topic);
  if (!subs || subs.size === 0) return 0;

  const { excludeWs, excludeDeviceId } = opts || {};
  const data = JSON.stringify({ type: "topic_message", topic, data: message });
  let sent = 0;
  subs.forEach((ws) => {
    if (ws.readyState !== 1) return;
    if (excludeWs && ws === excludeWs) return;
    if (excludeDeviceId && ws.deviceId === excludeDeviceId) return;
    ws.send(data);
    sent++;
  });
  console.log(`[WS] Topic "${topic}" broadcast: ${sent} subscriber(s)`);
  return sent;
}

function cleanupTopicHistory(topic) {
  const db = getDB();
  const maxHistory = parseInt(process.env.MAX_TOPIC_HISTORY || "200");
  db.prepare(`DELETE FROM topic_messages WHERE topic = ? AND id NOT IN (
      SELECT id FROM topic_messages WHERE topic = ? ORDER BY id DESC LIMIT ?)
    `).run(topic, topic, maxHistory);
}

// ===== 用户通知推送 =====

// 向指定用户的所有连接推送消息（可选排除某个设备，用于过滤本机自己发的通知）
function broadcastToUser(userId, message, excludeDeviceId) {
  const conns = userConnections.get(userId);
  if (!conns || conns.size === 0) {
    return false;
  }

  const data = JSON.stringify(message);
  let sent = 0;
  conns.forEach((ws) => {
    if (ws.readyState !== 1) return;
    if (excludeDeviceId && ws.deviceId === excludeDeviceId) return;
    ws.send(data);
    sent++;
  });

  console.log(`[WS] Broadcast to user ${userId}: ${sent} device(s)`);
  return sent > 0;
}

module.exports = { setupWebSocket, broadcastToUser, publishToTopic, normalizeTopic };
