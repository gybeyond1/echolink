// MP 官方 API 客户端（方案3：零侵入）
// ============================================================
// 目标：彻底脱离"焊线"（宿主模块 + 4 个官方文件补丁），
// 直接以官方公开 API 客户端身份接入 MoviePilot：
//   - 登录拿 JWT      POST /api/v1/login/access-token  (form: username/password)
//   - Agent 对话      POST /api/v1/message/agent/stream (JSON → SSE 流式)
//   - 按钮回调        POST /api/v1/message/agent/callback
//   - 通知轮询        GET  /api/v1/message/notification?page=1&count=20
//   - 会话列表/停止   GET  /api/v1/message/agent/sessions
//                     POST /api/v1/message/agent/sessions/{id}/stop
// 接入方式：moviepilot_channels 表配置 mp_server_url/mp_username/mp_password，
// 配置后该用户的"文字发 MP"与"按钮回调"自动走本模块，通知由轮询推送。
// 未配置时保持原有长轮询模式（兼容回退，即备份）。
// ============================================================

const { getDB, getSetting } = require("./db");
const crypto = require("crypto");

// ---------- 会话状态（内存） ----------
// username -> { token, fetchedAt }
const tokenCache = new Map();
// username -> NodeJS.Timeout（通知轮询定时器）
const pollTimers = new Map();
// username -> { serverSessionId, streaming: {messageId, active} }
const sessionState = new Map();

const DEFAULT_POLL_INTERVAL = 30; // 秒，可配 5-60
const MIN_POLL_INTERVAL = 5;
const MAX_POLL_INTERVAL = 60;

// ---------- 工具 ----------

function isMpApiChannel(channel) {
  if (!channel) return false;
  const server = (channel.mp_server_url || "").trim();
  const user = (channel.mp_username || "").trim();
  return channel.enabled === 1 && server.length > 0 && user.length > 0;
}

function normalizeServerUrl(url) {
  return String(url || "").trim().replace(/\/+$/, "");
}

// token 缓存 key：服务器 + MP 用户名组合，保证多 MP 实例（不同服务器、相同用户名）不串 token
function tokenCacheKey(channel) {
  return `${normalizeServerUrl(channel.mp_server_url)}|${(channel.mp_username || "").trim()}`;
}

function getUserByUsername(username) {
  const db = getDB();
  return db.prepare("SELECT id, username FROM users WHERE username = ?").get(username);
}

function getChannelByUsername(username) {
  const user = getUserByUsername(username);
  if (!user) return null;
  const db = getDB();
  const ch = db.prepare("SELECT * FROM moviepilot_channels WHERE user_id = ?").get(user.id);
  if (ch) ch.username = user.username; // EchoLink 用户名（与 MP 登录名 mp_username 区分）
  return ch;
}

// ---------- HTTP 封装（Node 22 内置 fetch） ----------

async function mpFetch(serverUrl, path, opts = {}) {
  const { method = "GET", headers = {}, body, token, timeoutMs = 30000 } = opts;
  const url = `${normalizeServerUrl(serverUrl)}${path}`;
  const ctrl = new AbortController();
  let timer = null;
  if (timeoutMs > 0) timer = setTimeout(() => ctrl.abort(), timeoutMs);
  const finalHeaders = { ...headers };
  if (token) finalHeaders["Authorization"] = `Bearer ${token}`;
  try {
    const res = await fetch(url, { method, headers: finalHeaders, body, signal: ctrl.signal });
    return res;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function mpFetchJson(serverUrl, path, opts = {}) {
  const res = await mpFetch(serverUrl, path, opts);
  let data = null;
  try { data = await res.json(); } catch (_) { /* 非 JSON 响应 */ }
  return { status: res.status, ok: res.ok, data };
}

// ---------- 登录与 Token 管理 ----------

async function mpLogin(channel) {
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  const form = new URLSearchParams();
  form.set("username", channel.mp_username.trim());
  form.set("password", (channel.mp_password || "").trim());
  const { status, data } = await mpFetchJson(serverUrl, "/api/v1/login/access-token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form.toString(),
    timeoutMs: 15000,
  });
  if (status === 401) {
    throw new Error("MP 登录失败（401）：用户名或密码错误");
  }
  if (!data || !data.access_token) {
    throw new Error(`MP 登录失败（HTTP ${status}）：${JSON.stringify(data || {}).slice(0, 200)}`);
  }
  tokenCache.set(tokenCacheKey(channel), { token: data.access_token, fetchedAt: Date.now() });
  return data.access_token;
}

async function ensureToken(channel) {
  const key = tokenCacheKey(channel);
  const cached = tokenCache.get(key);
  if (cached && cached.token) return cached.token;
  return mpLogin(channel);
}

// 401 时强制重登一次
async function ensureTokenFresh(channel, token) {
  const key = tokenCacheKey(channel);
  const cached = tokenCache.get(key);
  if (cached && cached.token === token && Date.now() - cached.fetchedAt > 60000) {
    tokenCache.delete(key);
    return mpLogin(channel);
  }
  return token;
}

// ---------- Agent 对话（SSE 流式） ----------

function parseSseEvent(line) {
  if (!line.startsWith("data:")) return null;
  const payload = line.slice(5).trim();
  if (!payload || payload === "[DONE]") return null;
  try { return JSON.parse(payload); } catch (_) { return null; }
}

// 处理一条 Agent SSE 事件，把文本增量写入 EchoLink 话题（打字机效果）
// 复用 moviepilot.js 的 startStreamMessage/appendStreamMessage/endStreamMessage
function handleAgentEvent(username, evt, stream) {
  // 延迟 require，避免 moviepilot <-> mpapi 循环依赖
  const { startStreamMessage, appendStreamMessage, endStreamMessage } = require("./moviepilot");
  const type = evt.type || "message";
  console.log(`[mpapi] ${username} SSE事件 type=${type}, content长度=${(evt.content||evt.message||'').length}, session_id=${evt.session_id||''}`);
  switch (type) {
    case "start": {
      // MP 会话开始帧（可能带 session_id，已在循环外层同步），重置本地流
      if (stream.messageId != null) {
        endStreamMessage(stream.messageId);
        stream.messageId = null;
      }
      break;
    }
    case "thinking": {
      // 思考中帧：无正文，忽略（未来可做"正在思考…"占位）
      break;
    }
    case "delta":
    case "message": {
      // MP 流式正文帧：内容在 delta.content；兼容旧版 message 帧
      const content = evt.content || evt.message || "";
      if (!content) { console.log(`[mpapi] ${username} SSE ${type} 帧无内容, 忽略`); return; }
      if (stream.messageId == null) {
        const r = startStreamMessage(username, content);
        if (r.error || !r.message_id) { console.log(`[mpapi] ${username} startStreamMessage 失败: ${r.error}`); return; }
        stream.messageId = r.message_id;
      } else {
        appendStreamMessage(stream.messageId, content);
      }
      break;
    }
    case "error": {
      const msg = evt.message || evt.content || "Agent 执行出错";
      console.error(`[mpapi] ${username} Agent error:`, msg);
      if (stream.messageId != null) {
        appendStreamMessage(stream.messageId, `\n\n[错误] ${msg}`);
        endStreamMessage(stream.messageId);
        stream.messageId = null;
      }
      break;
    }
    case "done": {
      console.log(`[mpapi] ${username} SSE done 帧, messageId=${stream.messageId}`);
      if (stream.messageId != null) {
        endStreamMessage(stream.messageId);
        stream.messageId = null;
      }
      break;
    }
    default:
      break; // tool/heartbeat 等忽略（工具进度由 message 帧承载）
  }
}

// 发送 Agent 消息（SSE 流式），文字增量实时写入话题
// sessionId 为空时用 clientSessionId（EchoLink 生成的 UUID），MP 会在流事件里返回真实 session_id
async function sendAgentMessage(username, channel, text, sessionId) {
  console.log(`[mpapi] ${username} sendAgentMessage 开始, server=${channel.mp_server_url}, text=${(text||'').slice(0,20)}`);
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  const token = await ensureToken(channel);
  const state = sessionState.get(username) || { serverSessionId: null, streaming: { messageId: null, active: false } };
  sessionState.set(username, state);

  let clientSessionId = state.serverSessionId;
  if (!clientSessionId) {
    clientSessionId = "el-" + crypto.randomBytes(8).toString("hex");
  }
  const outboundSessionId = state.serverSessionId || clientSessionId;

  const payload = {
    text,
    session_id: outboundSessionId,
    images: [],
    files: [],
    audio_refs: [],
    echo_user: true,
  };
  if (!state.serverSessionId) payload["client_session_id"] = clientSessionId;

  const res = await mpFetch(serverUrl, "/api/v1/message/agent/stream", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream",
    },
    body: JSON.stringify(payload),
    token,
    timeoutMs: 0, // 流式不设超时
  });

  if (res.status === 401) {
    const fresh = await ensureTokenFresh(channel, token);
    return sendAgentMessage(username, channel, text, sessionId); // 重登后重试一次
  }
  if (!res.ok || !res.body) {
    throw new Error(`MP Agent stream HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  const stream = { messageId: null, active: true };
  state.streaming = stream;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).trimEnd();
        buf = buf.slice(nl + 1);
        const evt = parseSseEvent(line);
        if (!evt) continue;
        // 从事件里同步服务端 session_id（首次对话时 MP 返回）
        if (evt.session_id && evt.session_id !== state.serverSessionId) {
          state.serverSessionId = evt.session_id;
        }
        handleAgentEvent(username, evt, stream);
      }
    }
  } catch (e) {
    console.error(`[mpapi] ${username} SSE 读取异常:`, e.message);
    if (stream.messageId != null) {
      appendStreamMessage(stream.messageId, "\n\n[连接中断]");
      endStreamMessage(stream.messageId);
    }
  } finally {
    stream.active = false;
    // 兜底：若流结束但没有 done 帧，确保消息结束
    if (stream.messageId != null) {
      endStreamMessage(stream.messageId);
      stream.messageId = null;
    }
  }
  console.log(`[mpapi] ${username} sendAgentMessage 完成, 流结束, sessionId=${state.serverSessionId}`);
  return { ok: true, mode: "mpapi", sessionId: state.serverSessionId };
}

// ---------- 按钮回调 ----------

async function sendCallback(username, channel, callbackData, sessionId) {
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  const token = await ensureToken(channel);
  const state = sessionState.get(username) || { serverSessionId: null, streaming: { messageId: null, active: false } };
  sessionState.set(username, state);
  const payload = { callback_data: callbackData };
  if (state.serverSessionId) payload.session_id = state.serverSessionId;
  const { status, data } = await mpFetchJson(serverUrl, "/api/v1/message/agent/callback", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    token,
  });
  if (status === 401) {
    const fresh = await ensureTokenFresh(channel, token);
    return sendCallback(username, channel, callbackData, sessionId);
  }
  if (!data || data.success === false) {
    throw new Error(`MP callback 失败（HTTP ${status}）：${JSON.stringify(data || {}).slice(0, 200)}`);
  }
  return { ok: true, mode: "mpapi", status, data };
}

// ---------- 通知轮询 ----------

// 把一条官方通知转成 EchoLink 卡片消息
function notificationToCard(n) {
  const card = { title: n.title || "MoviePilot", text: n.text || "" };
  if (n.image) card.poster = n.image;
  if (n.link) card.link = n.link;
  if (n.mtype) card.mtype = n.mtype;
  if (n.source) card.source = n.source;
  return card;
}

async function pollNotifications(channel) {
  console.log(`[mpapi] ${channel.username || '?'} 通知轮询触发, last_id=${channel.mp_last_notification_id}`);
  const username = (channel.username || "").trim(); // EchoLink 用户名（入库/广播）
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  let token;
  try {
    token = await ensureToken(channel);
  } catch (e) {
    console.error(`[mpapi] ${username} 通知轮询登录失败:`, e.message);
    return;
  }
  const interval = Math.min(Math.max(parseInt(channel.mp_poll_interval) || DEFAULT_POLL_INTERVAL, MIN_POLL_INTERVAL), MAX_POLL_INTERVAL);
  try {
    const res = await mpFetch(serverUrl, "/api/v1/message/notification?page=1&count=20", {
      method: "GET",
      token,
      timeoutMs: 20000,
    });
    if (res.status === 401) {
      token = await ensureTokenFresh(channel, token);
      const retry = await mpFetch(serverUrl, "/api/v1/message/notification?page=1&count=20", {
        method: "GET", token, timeoutMs: 20000,
      });
      return processNotificationResponse(username, channel, retry);
    }
    if (!res.ok) {
      console.error(`[mpapi] ${username} 通知轮询 HTTP ${res.status}`);
      return;
    }
    return processNotificationResponse(username, channel, res);
  } catch (e) {
    console.error(`[mpapi] ${username} 通知轮询异常:`, e.message);
  }
}

async function processNotificationResponse(username, channel, res) {
  let list;
  try { list = await res.json(); } catch (_) { return; }
  if (!Array.isArray(list) || list.length === 0) return;

  const db = getDB();
  const lastId = parseInt(channel.mp_last_notification_id) || 0;
  let maxId = lastId;
  const fresh = list
    .filter((n) => n && n.id && parseInt(n.id) > lastId)
    .sort((a, b) => parseInt(a.id) - parseInt(b.id));
  for (const n of fresh) {
    const id = parseInt(n.id);
    if (id > maxId) maxId = id;
    try {
      // 入库 + WebSocket 广播（复用现有卡片消息通路）
      const { appendMoviepilotMessage } = require("./moviepilot");
      const r = appendMoviepilotMessage(username, notificationToCard(n), n.text || "");
      if (r.error) console.error(`[mpapi] ${username} 通知入库失败:`, r.error);
    } catch (e) {
      console.error(`[mpapi] ${username} 通知处理异常:`, e.message);
    }
  }
  if (maxId > lastId) {
    db.prepare("UPDATE moviepilot_channels SET mp_last_notification_id = ? WHERE user_id = ?")
      .run(maxId, channel.user_id);
    channel.mp_last_notification_id = maxId;
  }
}

// ---------- 轮询生命周期 ----------

function startPolling(channel) {
  const username = (channel.username || "").trim();
  if (!isMpApiChannel(channel) || !username) return;
  stopPolling(username);
  const interval = Math.min(Math.max(parseInt(channel.mp_poll_interval) || DEFAULT_POLL_INTERVAL, MIN_POLL_INTERVAL), MAX_POLL_INTERVAL);
  // 启动后立即执行一次，之后按间隔轮询
  setTimeout(() => pollNotifications(channel), 1500);
  const timer = setInterval(() => pollNotifications(channel), interval * 1000);
  pollTimers.set(username, timer);
  console.log(`[mpapi] ${username} 通知轮询已启动（间隔 ${interval}s）`);
}

function stopPolling(username) {
  const timer = pollTimers.get(username);
  if (timer) {
    clearInterval(timer);
    pollTimers.delete(username);
  }
}

// 服务启动时：为所有已配置 MP API 的通道启动轮询
function startAllPolling() {
  const db = getDB();
  const channels = db.prepare("SELECT * FROM moviepilot_channels WHERE enabled = 1").all();
  for (const ch of channels) {
    if (ch.mp_server_url && ch.mp_username) {
      const row = db.prepare("SELECT username FROM users WHERE id = ?").get(ch.user_id);
      if (row) {
        ch.username = row.username;
        startPolling(ch);
      }
    }
  }
}

function stopAllPolling() {
  for (const username of pollTimers.keys()) stopPolling(username);
}

// ---------- 会话管理（辅助） ----------

async function listSessions(channel) {
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  const token = await ensureToken(channel);
  const { status, data } = await mpFetchJson(serverUrl, "/api/v1/message/agent/sessions?page=1&count=30", {
    token, timeoutMs: 15000,
  });
  if (status === 401) {
    const fresh = await ensureTokenFresh(channel, token);
    const retry = await mpFetchJson(serverUrl, "/api/v1/message/agent/sessions?page=1&count=30", { token: fresh, timeoutMs: 15000 });
    return retry;
  }
  return { status, data };
}

async function stopSession(channel, sessionId) {
  const serverUrl = normalizeServerUrl(channel.mp_server_url);
  const token = await ensureToken(channel);
  const { status, data } = await mpFetchJson(serverUrl, `/api/v1/message/agent/sessions/${encodeURIComponent(sessionId)}/stop`, {
    method: "POST", token, timeoutMs: 15000,
  });
  if (status === 401) {
    const fresh = await ensureTokenFresh(channel, token);
    return mpFetchJson(serverUrl, `/api/v1/message/agent/sessions/${encodeURIComponent(sessionId)}/stop`, {
      method: "POST", token: fresh, timeoutMs: 15000,
    });
  }
  return { status, data };
}

module.exports = {
  isMpApiChannel,
  getChannelByUsername,
  sendAgentMessage,
  sendCallback,
  pollNotifications,
  startPolling,
  stopPolling,
  startAllPolling,
  stopAllPolling,
  listSessions,
  stopSession,
};
