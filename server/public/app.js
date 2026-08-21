/* EchoLink Web —— 对齐手机 App 的 messenger 布局 + 管理后台，纯前端调用同域 REST API */
(function () {
  "use strict";

  const app = document.getElementById("app");
  const toastBox = document.getElementById("toast");

  const state = {
    token: localStorage.getItem("ns_token") || "",
    username: localStorage.getItem("ns_username") || "",
    userId: parseInt(localStorage.getItem("ns_uid")) || 0,
    role: localStorage.getItem("ns_role") || "user",
    display_name: "",
    avatar: "",
    tab: "messages",
    // 当前打开的会话：{ topic, kind, display } 或 { special: "notifications" }
    chat: null,
    reqCount: 0,
    notifCount: 0,
    topics: [],
    ws: null,
    wsWantClose: false,
  };

  // ---------- helpers ----------
  // API 基址：浏览器同源=空字符串（相对路径）；Tauri 桌面端=配置的 server_url
  let API_BASE = "";
  function isTauri() { return !!(window.__TAURI__ && window.__TAURI__.core); }

  // 桌面端（Tauri/Windows）：禁用右键菜单与 F5 刷新，强化原生体验
  if (window.__TAURI__ || window.__TAURI_INTERNALS__) {
    document.addEventListener("contextmenu", function (e) { e.preventDefault(); });
    window.addEventListener("keydown", function (e) {
      if (e.key === "F5" || (e.ctrlKey && (e.key === "r" || e.key === "R"))) e.preventDefault();
    });
  }
  async function initApiBase() {
    if (isTauri()) {
      try {
        const cfg = await window.__TAURI__.core.invoke("get_config");
        if (cfg && cfg.server_url) API_BASE = cfg.server_url.replace(/\/+$/, "");
      } catch (e) { /* 忽略，保持同源 */ }
    }
  }
  // 相对路径（/api/...、/uploads/...）拼接 API_BASE；绝对 URL 原样返回
  function absUrl(u) {
    if (!u) return u;
    if (/^https?:\/\//.test(u) || u.startsWith("p2p:")) return u;
    if (u.startsWith("/")) return API_BASE + u;
    return u;
  }
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
    );
  }
  function fmtTime(ts) {
    if (!ts) return "-";
    const d = new Date(typeof ts === "number" && ts < 1e12 ? ts * 1000 : ts);
    if (isNaN(d)) return String(ts);
    return d.toLocaleString("zh-CN", { hour12: false });
  }
  function fmtShort(ts) {
    if (!ts) return "";
    const d = new Date(typeof ts === "number" && ts < 1e12 ? ts * 1000 : ts);
    if (isNaN(d)) return "";
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) return d.toTimeString().slice(0, 5);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  }
  function fmtUptime(s) {
    s = Math.floor(s);
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (h > 0) return `${h} 小时 ${m} 分`;
    if (m > 0) return `${m} 分 ${sec} 秒`;
    return `${sec} 秒`;
  }
  function fmtSize(bytes) {
    bytes = Number(bytes) || 0;
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / 1024 / 1024).toFixed(1) + " MB";
  }
  function toast(msg, type) {
    const el = document.createElement("div");
    el.className = "toast-item " + (type || "");
    el.textContent = msg;
    toastBox.appendChild(el);
    setTimeout(() => el.remove(), 3200);
  }
  function initials(name) {
    const s = String(name || "?").trim();
    return s ? s[0].toUpperCase() : "?";
  }
  // 用户名 → 稳定色相（类似 Telegram 头像自动配色）
  function hueOf(name) {
    let h = 0;
    const s = String(name || "");
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
    return h;
  }
  function avatarHtml(name, url, size, hue) {
    if (url) {
      return `<img class="avatar" style="width:${size}px;height:${size}px" src="${esc(absUrl(url))}" onerror="this.style.display='none'" />`;
    }
    const h = hue == null ? hueOf(name) : hue;
    return `<div class="avatar avatar-txt" style="width:${size}px;height:${size}px;font-size:${Math.round(size * 0.42)}px;background:linear-gradient(135deg,hsl(${h},72%,58%),hsl(${(h + 40) % 360},72%,48%))">${esc(initials(name))}</div>`;
  }

  async function api(path, opts) {
    opts = opts || {};
    const headers = { "Content-Type": "application/json" };
    if (state.token) headers["Authorization"] = "Bearer " + state.token;
    const res = await fetch(API_BASE + path, {
      method: opts.method || "GET",
      headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    let data = null;
    try { data = await res.json(); } catch (e) { data = {}; }
    if (!res.ok) {
      const err = (data && (data.error || data.message)) || ("HTTP " + res.status);
      throw new Error(err);
    }
    return data;
  }

  function saveAuth(token, username, role, userId) {
    state.token = token; state.username = username; state.role = role || "user";
    if (userId) state.userId = userId;
    localStorage.setItem("ns_token", token);
    localStorage.setItem("ns_username", username);
    localStorage.setItem("ns_role", state.role);
    if (userId) localStorage.setItem("ns_uid", userId);
  }
  function logout() {
    state.token = ""; state.username = ""; state.userId = 0; state.role = "user";
    state.chat = null; state.display_name = ""; state.avatar = "";
    if (state.ws) { state.wsWantClose = true; try { state.ws.close(); } catch (e) {} state.ws = null; }
    ["ns_token", "ns_username", "ns_role", "ns_uid"].forEach(k => localStorage.removeItem(k));
    render();
  }

  // ---------- WebSocket 实时 ----------
  function connectWS() {
    if (!state.token || state.ws) return;
    state.wsWantClose = false;
    // 同源：ws(s)://location.host；Tauri 桌面端：从 API_BASE 推导（https→wss）
    const origin = API_BASE || location.origin;
    const proto = origin.startsWith("https") ? "wss" : "ws";
    let ws;
    try {
      ws = new WebSocket(`${proto}://${origin.replace(/^https?:\/\//, "")}/ws?token=${encodeURIComponent(state.token)}`);
    } catch (e) { return; }
    state.ws = ws;
    ws.onopen = () => {
      // 订阅我的全部会话
      state.topics.forEach(t => ws.send(JSON.stringify({ type: "subscribe", topic: t.name })));
    };
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      handleWS(m);
    };
    ws.onclose = () => {
      state.ws = null;
      if (!state.wsWantClose && state.token) setTimeout(connectWS, 3000); // 断线重连
    };
  }
  function wsSend(obj) {
    if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify(obj));
  }

  let msgIdSet = new Set();
  function handleWS(m) {
    if (m.type === "topic_message" && m.data) {
      const d = m.data;
      // 更新左侧会话预览
      const t = state.topics.find(x => x.name === m.topic);
      if (t) {
        t.last_message = d.text || d.title || mediaLabel(d);
        t.last_message_at = d.timestamp;
        if (state.tab === "messages") renderSessionList();
      }
      // 当前聊天窗口追加（按 id 去重，REST 发送后本机也会收到）
      if (state.tab === "messages" && state.chat && state.chat.topic === m.topic && !msgIdSet.has(d.id)) {
        appendBubble(d);
      }
    } else if (m.type === "friend_request" || m.type === "topic_request") {
      state.reqCount++;
      if (state.tab === "messages") renderSessionList();
      toast("收到新的申请", "ok");
    } else if (m.type === "friend_accepted") {
      toast(`${m.data?.username || "对方"} 通过了你的好友申请`, "ok");
      if (state.tab === "friends") renderTab();
      loadTopics();
    } else if (m.type === "notification") {
      state.notifCount++;
      if (state.tab === "messages") renderSessionList();
    } else if (m.type === "profile_updated") {
      loadProfile();
    }
  }

  function mediaLabel(d) {
    if (!d || !d.media_type || d.media_type === "text") return "";
    if (d.media_type === "image") return "[图片]";
    if (d.media_type === "voice") return "[语音]";
    if (d.media_type === "file") return "[文件]";
    return "";
  }

  // ---------- Auth screen ----------
  function renderAuth() {
    app.innerHTML = `
      <div class="auth-wrap">
        <div class="auth-card">
          <div class="brand">
            <div class="logo">E</div>
            <h1>EchoLink</h1>
            <p>跨设备消息互联 · 通知同步 · 好友</p>
            <p style="font-size:12px;opacity:.7;margin-top:4px"><a href="https://github.com/gybeyond1/echolink" target="_blank" style="color:inherit;text-decoration:underline">github.com/gybeyond1/echolink</a></p>
          </div>
          <div id="auth-form">
            <label>用户名</label>
            <input id="au-user" type="text" placeholder="3-32 个字符" autocomplete="username" />
            <label>密码</label>
            <input id="au-pass" type="password" placeholder="至少 6 位" autocomplete="current-password" />
            <button id="au-submit" class="btn block" style="margin-top:18px">登录</button>
            <p style="text-align:center;margin-top:14px">
              <span class="link" id="au-toggle">还没有账号？注册一个</span>
            </p>
          </div>
        </div>
      </div>`;

    let mode = "login";
    const submit = document.getElementById("au-submit");
    const toggle = document.getElementById("au-toggle");

    toggle.onclick = () => {
      mode = mode === "login" ? "register" : "login";
      submit.textContent = mode === "login" ? "登录" : "注册并进入";
      toggle.textContent = mode === "login" ? "还没有账号？注册一个" : "已有账号？去登录";
    };
    submit.onclick = async () => {
      const username = document.getElementById("au-user").value.trim();
      const password = document.getElementById("au-pass").value;
      if (!username || !password) return toast("请输入用户名和密码", "err");
      submit.disabled = true;
      try {
        const ep = mode === "login" ? "/api/auth/login" : "/api/auth/register";
        const r = await api(ep, { method: "POST", body: { username, password } });
        saveAuth(r.token, r.user.username, r.user.role, r.user.id);
        toast(mode === "login" ? "登录成功" : "注册成功，已自动登录", "ok");
        boot();
      } catch (e) {
        toast(e.message, "err");
      } finally {
        submit.disabled = false;
      }
    };
  }

  // ---------- Dashboard shell ----------
  const BASE_NAV = [
    { id: "messages", ic: "💬", label: "消息" },
    { id: "friends", ic: "👥", label: "好友" },
    { id: "devices", ic: "📱", label: "设备" },
    { id: "filters", ic: "🧩", label: "应用过滤" },
    { id: "overview", ic: "📊", label: "概览" },
    { id: "account", ic: "👤", label: "账号" },
  ];
  const ADMIN_NAV = [
    { id: "admin_users", ic: "🛡️", label: "用户管理" },
    { id: "admin_topics", ic: "📚", label: "全部话题" },
    { id: "admin_notifs", ic: "📥", label: "全部通知" },
    { id: "admin_settings", ic: "⚙️", label: "服务器设置" },
  ];

  function navItems() {
    return state.role === "admin" ? BASE_NAV.concat(ADMIN_NAV) : BASE_NAV;
  }

  function render() {
    if (!state.token) { renderAuth(); return; }
    const unread = state.reqCount + 0;
    app.innerHTML = `
      <div class="layout">
        <aside class="sidebar">
          <div class="brand"><div class="logo">E</div><h1>EchoLink</h1></div>
          ${navItems().map(n => `<button class="nav-item ${state.tab === n.id ? "active" : ""}" data-tab="${n.id}"><span class="ic">${n.ic}</span>${n.label}${n.id === "messages" && unread > 0 ? `<span class="nav-badge">${unread > 99 ? "99+" : unread}</span>` : ""}</button>`).join("")}
          <div class="spacer"></div>
          <div class="user-box">
            <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
              ${avatarHtml(state.display_name || state.username, state.avatar, 34)}
              <div><b>${esc(state.display_name || state.username)}</b>
              <div style="color:var(--muted)">@${esc(state.username)}${state.role === "admin" ? ' <span class="badge admin">管理员</span>' : ""}</div></div>
            </div>
          </div>
        </aside>
        <main class="main" id="main"></main>
      </div>`;
    app.querySelectorAll(".nav-item").forEach(b => {
      b.onclick = () => { state.tab = b.dataset.tab; state.chat = null; render(); };
    });
    renderTab();
  }

  async function renderTab() {
    const main = document.getElementById("main");
    try {
      if (state.tab === "messages") return renderMessages(main);
      if (state.tab === "friends") return renderFriends(main);
      if (state.tab === "overview") return renderOverview(main);
      if (state.tab === "devices") return renderDevices(main);
      if (state.tab === "filters") return renderFilters(main);
      if (state.tab === "account") return renderAccount(main);
      if (state.tab === "admin_users") return renderAdminUsers(main);
      if (state.tab === "admin_topics") return renderAdminTopics(main);
      if (state.tab === "admin_notifs") return renderAdminNotifs(main);
      if (state.tab === "admin_settings") return renderAdminSettings(main);
    } catch (e) {
      main.innerHTML = `<div class="empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  // ---------- 数据加载 ----------
  async function loadTopics() {
    try {
      const r = await api("/api/topics");
      state.topics = r.topics || [];
      if (state.ws && state.ws.readyState === 1) {
        state.topics.forEach(t => wsSend({ type: "subscribe", topic: t.name }));
      }
    } catch (e) { state.topics = []; }
  }
  async function loadRequests() {
    try {
      const r = await api("/api/requests");
      state.reqCount = (r.friend_requests?.length || 0) + (r.topic_requests?.length || 0);
    } catch (e) { state.reqCount = 0; }
  }
  async function loadNotificationsCount() {
    try {
      const r = await api("/api/notifications?limit=50");
      state.notifCount = r.notifications?.length || 0;
    } catch (e) { state.notifCount = 0; }
  }
  async function loadProfile() {
    try {
      const r = await api("/api/user/profile");
      state.display_name = r.display_name || r.username;
      state.avatar = r.avatar || "";
      if (state.tab !== "messages") render();
    } catch (e) { /* ignore */ }
  }

  // ================= 消息页（双栏 messenger） =================
  async function renderMessages(main) {
    main.innerHTML = `
      <div class="msg-layout">
        <div class="sess-col" id="sessCol">
          <div class="sess-header"><b>消息</b></div>
          <div class="sess-list" id="sessList"><div class="empty" style="margin:12px">加载中…</div></div>
        </div>
        <div class="chat-col" id="chatCol">
          <div class="chat-welcome">
            <div class="chat-welcome-ic">💬</div>
            <p>选择一个会话开始聊天</p>
          </div>
        </div>
      </div>`;
    await loadTopics();
    await loadRequests();
    await loadNotificationsCount();
    renderSessionList();
    connectWS();
  }

  function sessionEntryHtml(active, inner) {
    return `<div class="sess-item ${active ? "active" : ""}" ${inner.attrs}>${inner.html}</div>`;
  }

  function renderSessionList() {
    const list = document.getElementById("sessList");
    if (!list) return;
    const parts = [];
    // 置顶：通知
    const notifActive = state.chat && state.chat.special === "notifications";
    parts.push(sessionEntryHtml(notifActive, {
      attrs: `data-special="notifications"`,
      html: `
        <div class="avatar avatar-txt notif-ic">🔔</div>
        <div class="sess-body">
          <div class="sess-row"><b>通知</b>${state.notifCount ? `<span class="sess-badge">${state.notifCount}</span>` : ""}</div>
          <div class="sess-preview">所有设备的同步通知</div>
        </div>`,
    }));
    // 置顶：新的申请
    if (state.reqCount > 0) {
      const reqActive = state.chat && state.chat.special === "requests";
      parts.push(sessionEntryHtml(reqActive, {
        attrs: `data-special="requests"`,
        html: `
          <div class="avatar avatar-txt req-ic">✋</div>
          <div class="sess-body">
            <div class="sess-row"><b>新的申请</b><span class="sess-badge">${state.reqCount}</span></div>
            <div class="sess-preview">好友申请 / 加群申请</div>
          </div>`,
      }));
    }
    // 会话列表
    state.topics.forEach(t => {
      const kind = t.kind || "normal";
      const name = t.display_name || t.name;
      const active = state.chat && state.chat.topic === t.name;
      let av;
      if (kind === "devices") av = `<img class="avatar" style="width:46px;height:46px" src="devices_avatar.png" onerror="this.style.display='none'" />`;
      else if (kind === "dm") av = avatarHtml(name, null, 46);
      else av = avatarHtml("#" + t.name, null, 46, 205);
      const preview = t.last_message || mediaLabel(t) || (kind === "devices" ? "我的设备同步会话" : "暂无消息");
      parts.push(sessionEntryHtml(active, {
        attrs: `data-topic="${esc(t.name)}"`,
        html: `${av}
          <div class="sess-body">
            <div class="sess-row"><b>${kind === "normal" ? "#" : ""}${esc(name)}</b><span class="sess-time">${fmtShort(t.last_message_at)}</span></div>
            <div class="sess-preview">${esc(String(preview).slice(0, 40))}</div>
          </div>`,
      }));
    });
    list.innerHTML = parts.join("") || `<div class="empty" style="margin:12px">暂无会话</div>`;
    list.querySelectorAll("[data-topic]").forEach(el => {
      el.onclick = () => {
        const t = state.topics.find(x => x.name === el.dataset.topic);
        if (t) openChat(t);
      };
    });
    list.querySelectorAll("[data-special]").forEach(el => {
      el.onclick = () => {
        if (el.dataset.special === "notifications") openNotifications();
        else openRequests();
      };
    });
  }

  function setChatOpen(open) {
    const el = document.querySelector(".msg-layout");
    if (el) el.classList.toggle("chat-open", !!open);
  }

  function chatHeader(title, sub, actionsHtml) {
    return `<div class="chat-header">
      <button class="btn ghost sm chat-back" id="chatBack">‹</button>
      <div class="chat-title"><b>${title}</b>${sub ? `<span>${sub}</span>` : ""}</div>
      <div class="chat-actions">${actionsHtml || ""}</div>
    </div>`;
  }

  function openNotifications() {
    state.chat = { special: "notifications" };
    renderSessionList();
    const col = document.getElementById("chatCol");
    col.innerHTML = chatHeader("通知", "所有设备的同步通知",
      `<button class="btn ghost sm" id="nt-clear">清空全部</button>`) + `
      <div class="chat-body" id="ntList" style="padding:14px"><div class="empty">加载中…</div></div>`;
    document.getElementById("chatBack").onclick = () => { state.chat = null; setChatOpen(false); renderSessionList(); col.innerHTML = `<div class="chat-welcome"><div class="chat-welcome-ic">💬</div><p>选择一个会话开始聊天</p></div>`; };
    document.getElementById("nt-clear").onclick = async () => {
      if (!confirm("确定清空所有通知记录？此操作不可恢复。")) return;
      try { await api("/api/notifications", { method: "DELETE" }); toast("已清空", "ok"); openNotifications(); }
      catch (e) { toast(e.message, "err"); }
    };
    (async () => {
      const r = await api("/api/notifications?limit=100").catch(() => ({ notifications: [] }));
      state.notifCount = r.notifications.length;
      const box = document.getElementById("ntList");
      if (!box) return;
      if (!r.notifications.length) { box.innerHTML = `<div class="empty">暂无通知记录</div>`; return; }
      box.innerHTML = r.notifications.map(n => `<div class="notif-card">
        <div class="notif-app">${esc(n.app_name || "-")}<span class="notif-time">${fmtTime(n.timestamp)}</span></div>
        ${n.title ? `<div class="notif-title">${esc(n.title)}</div>` : ""}
        ${n.text ? `<div class="notif-text">${esc(n.text)}</div>` : ""}
        <div class="notif-meta">${esc(n.device_name || "")} <button class="btn danger sm" data-del="${n.id}" style="float:right">删除</button></div>
      </div>`).join("");
      box.querySelectorAll("[data-del]").forEach(b => b.onclick = async () => {
        try { await api("/api/notifications/" + b.dataset.del, { method: "DELETE" }); b.closest(".notif-card").remove(); state.notifCount--; toast("已删除", "ok"); }
        catch (e) { toast(e.message, "err"); }
      });
      renderSessionList();
    })();
  }

  // ---- 聊天 ----
  let chatTopicInfo = null;
  function openChat(t) {
    state.chat = { topic: t.name, kind: t.kind || "normal", display: t.display_name || t.name, my_role: t.my_role };
    chatTopicInfo = t;
    renderSessionList();
    setChatOpen(true);
    wsSend({ type: "subscribe", topic: t.name });

    const col = document.getElementById("chatCol");
    const isSpecial = t.kind === "devices" || t.kind === "dm";
    const actions = [
      t.my_role === "owner" && t.kind !== "dm" && t.kind !== "devices" ? `<button class="btn ghost sm" id="c-req">待审批${t.pending_requests > 0 ? `(${t.pending_requests})` : ""}</button>` : "",
      t.kind !== "dm" ? `<button class="btn ghost sm" id="c-members">成员</button>` : "",
      !isSpecial ? `<button class="btn ghost sm" id="c-leave">退出</button>` : "",
      t.my_role === "owner" && !isSpecial ? `<button class="btn danger sm" id="c-del">删除</button>` : "",
    ].join("");
    const sub = t.kind === "devices" ? "设备同步会话 · 本账号互通"
      : t.kind === "dm" ? "好友私聊"
      : `${t.my_role === "owner" ? "创建者" : "成员"} · #${esc(t.name)}`;
    col.innerHTML = chatHeader(
      (t.kind === "normal" ? "#" : "") + esc(t.display || t.name), sub, actions
    ) + `
      <div class="chat-body" id="chatBody"><div class="chat-loading">加载中…</div></div>
      <div class="chat-input">
        <button class="ci-btn" id="ci-attach" title="发送图片/文件">📎</button>
        <input type="file" id="ci-file" style="display:none" />
        <button class="ci-btn" id="ci-voice" title="录制语音">🎤</button>
        <input id="ci-text" type="text" placeholder="输入消息…" autocomplete="off" />
        <button class="btn" id="ci-send">发送</button>
      </div>`;
    document.getElementById("chatBack").onclick = () => {
      state.chat = null;
      setChatOpen(false);
      document.getElementById("chatCol").innerHTML = `<div class="chat-welcome"><div class="chat-welcome-ic">💬</div><p>选择一个会话开始聊天</p></div>`;
      renderSessionList();
    };
    const membersBtn = document.getElementById("c-members");
    if (membersBtn) membersBtn.onclick = showMembers;
    const reqBtn = document.getElementById("c-req");
    if (reqBtn) reqBtn.onclick = () => showTopicRequests(t.name);
    const leaveBtn = document.getElementById("c-leave");
    if (leaveBtn) leaveBtn.onclick = async () => {
      if (!confirm("确定退出该话题？")) return;
      try { await api("/api/topics/" + t.name + "/leave", { method: "POST" }); toast("已退出", "ok"); state.chat = null; renderMessages(document.getElementById("main")); }
      catch (e) { toast(e.message, "err"); }
    };
    const delBtn = document.getElementById("c-del");
    if (delBtn) delBtn.onclick = async () => {
      if (!confirm("确定删除该话题？所有消息将被清除，且不可恢复。")) return;
      try { await api("/api/topics/" + t.name, { method: "DELETE" }); toast("已删除", "ok"); state.chat = null; renderMessages(document.getElementById("main")); }
      catch (e) { toast(e.message, "err"); }
    };

    const input = document.getElementById("ci-text");
    input.onkeydown = (e) => { if (e.key === "Enter") sendText(); };
    document.getElementById("ci-send").onclick = sendText;
    document.getElementById("ci-attach").onclick = () => document.getElementById("ci-file").click();
    document.getElementById("ci-file").onchange = sendFile;
    setupVoice();

    loadMessages(t.name);
    input.focus();
  }

  async function loadMessages(topic) {
    const r = await api(`/api/topics/${encodeURIComponent(topic)}/messages?limit=100`).catch(() => ({ messages: [] }));
    const body = document.getElementById("chatBody");
    if (!body || !state.chat || state.chat.topic !== topic) return;
    msgIdSet = new Set();
    if (!r.messages.length) {
      body.innerHTML = `<div class="chat-loading">还没有消息，发一条试试</div>`;
      return;
    }
    body.innerHTML = r.messages.map(bubbleHtml).join("");
    msgIdSet = new Set(r.messages.map(m => m.id));
    body.scrollTop = body.scrollHeight;
  }

  function bubbleHtml(m) {
    // REST 返回的消息带 user_id；WS 广播的旧字段没有 user_id，回退用 sender_name 判断
    const mine = (state.userId && m.user_id === state.userId) ||
      (!m.user_id && m.sender_name === state.username);
    const senderName = m.sender_display_name || m.sender_name || "未知";
    const time = fmtShort(m.timestamp);
    let media = "";
    if (m.media_type && m.media_type !== "text" && m.media_url) {
      const raw = absUrl(m.media_url);
      const url = esc(raw);
      if (String(m.media_url).startsWith("p2p:")) {
        media = `<div class="bubble-media p2p-note">📎 P2P 直传文件 · 请在手机 App 查看</div>`;
      } else if (m.media_type === "image") {
        media = `<div class="bubble-media"><a href="${url}" target="_blank"><img src="${url}" loading="lazy" /></a></div>`;
      } else if (m.media_type === "voice") {
        media = `<div class="bubble-media"><audio controls preload="none" src="${url}"></audio></div>`;
      } else if (m.media_type === "file") {
        media = `<div class="bubble-media"><a class="btn ghost sm" href="${url}" target="_blank" download>📄 ${esc(m.media_name || "文件")}（${fmtSize(m.media_size)}）</a></div>`;
      }
    }
    return `<div class="msg-row ${mine ? "mine" : ""}">
      ${avatarHtml(senderName, m.sender_avatar, 34)}
      <div class="bubble ${mine ? "bubble-own" : "bubble-other"}">
        <div class="bubble-sender">${esc(senderName)}${m.device_name ? `<span class="bubble-dev"> · ${esc(m.device_name)}</span>` : ""}<span class="bubble-time">${time}</span></div>
        ${m.title ? `<div class="bubble-title">${esc(m.title)}</div>` : ""}
        ${m.text ? `<div class="bubble-text">${esc(m.text)}</div>` : ""}
        ${media}
      </div>
    </div>`;
  }

  function appendBubble(m) {
    const body = document.getElementById("chatBody");
    if (!body) return;
    const empty = body.querySelector(".chat-loading");
    if (empty) empty.remove();
    if (msgIdSet.has(m.id)) return;
    msgIdSet.add(m.id);
    body.insertAdjacentHTML("beforeend", bubbleHtml(m));
    body.scrollTop = body.scrollHeight;
  }

  async function sendText() {
    const input = document.getElementById("ci-text");
    if (!input) return;
    const text = input.value.trim();
    if (!text || !state.chat) return;
    input.value = "";
    try {
      const r = await api(`/api/topics/${encodeURIComponent(state.chat.topic)}/publish`, {
        method: "POST", body: { text, sender_name: state.username },
      });
      if (r.topic_message) appendBubble(r.topic_message);
    } catch (e) { toast(e.message, "err"); }
  }

  async function sendFile() {
    const fi = document.getElementById("ci-file");
    const f = fi.files[0];
    if (!f || !state.chat) return;
    const kind = f.type.startsWith("image/") ? "image" : "file";
    fi.value = "";
    await uploadAndPublish(f, kind);
  }

  async function uploadAndPublish(blob, kind, name) {
    try {
      const fd = new FormData();
      fd.append("file", blob, name || blob.name || "upload");
      const res = await fetch(`${API_BASE}/api/topics/${encodeURIComponent(state.chat.topic)}/media?kind=${kind}`, {
        method: "POST",
        headers: { Authorization: "Bearer " + state.token },
        body: fd,
      });
      const j = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(j.error || "上传失败");
      const r = await api(`/api/topics/${encodeURIComponent(state.chat.topic)}/publish`, {
        method: "POST",
        body: { media_type: kind, media_url: j.url, media_name: j.name, media_size: j.size, sender_name: state.username },
      });
      if (r.topic_message) appendBubble(r.topic_message);
    } catch (e) { toast(e.message, "err"); }
  }

  // 语音录制（浏览器 MediaRecorder）
  let recorder = null, recChunks = [], recTimer = null;
  function setupVoice() {
    const btn = document.getElementById("ci-voice");
    if (!btn) return;
    btn.onclick = async () => {
      if (recorder && recorder.state === "recording") { recorder.stop(); return; }
      if (!navigator.mediaDevices?.getUserMedia) return toast("当前浏览器不支持录音", "err");
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        recChunks = [];
        recorder = new MediaRecorder(stream);
        recorder.ondataavailable = (e) => { if (e.data.size) recChunks.push(e.data); };
        recorder.onstop = async () => {
          stream.getTracks().forEach(t => t.stop());
          btn.classList.remove("rec");
          btn.textContent = "🎤";
          clearInterval(recTimer);
          const blob = new Blob(recChunks, { type: recorder.mimeType || "audio/webm" });
          if (blob.size > 1024) await uploadAndPublish(blob, "voice", `voice_${Date.now()}.webm`);
        };
        recorder.start();
        btn.classList.add("rec");
        btn.textContent = "⏹";
        let sec = 60;
        recTimer = setInterval(() => {
          sec--;
          if (sec <= 0 && recorder.state === "recording") recorder.stop();
        }, 1000);
      } catch (e) { toast("无法访问麦克风：" + e.message, "err"); }
    };
  }

  async function showMembers() {
    if (!state.chat) return;
    try {
      const r = await api("/api/topics/" + state.chat.topic + "/members");
      const html = (r.members || []).map(m => `<div class="req-card"><b>${esc(m.display_name || m.username || "?")}</b> <span class="badge ${m.role === "owner" ? "owner" : "member"}">${m.role === "owner" ? "创建者" : "成员"}</span></div>`).join("");
      openModal("成员（" + (r.members || []).length + "）", html || `<div class="empty">暂无成员</div>`);
    } catch (e) { toast(e.message, "err"); }
  }

  // ---- 统一申请（好友 + 加群） ----
  function openRequests() {
    state.chat = { special: "requests" };
    renderSessionList();
    setChatOpen(true);
    const col = document.getElementById("chatCol");
    col.innerHTML = chatHeader("新的申请", "好友申请与加群申请", "") + `
      <div class="chat-body" id="reqList" style="padding:14px"><div class="chat-loading">加载中…</div></div>`;
    document.getElementById("chatBack").onclick = () => { state.chat = null; setChatOpen(false); renderSessionList(); col.innerHTML = `<div class="chat-welcome"><div class="chat-welcome-ic">💬</div><p>选择一个会话开始聊天</p></div>`; };
    loadRequestsView();
  }

  async function loadRequestsView() {
    const box = document.getElementById("reqList");
    if (!box) return;
    try {
      const r = await api("/api/requests");
      const fr = r.friend_requests || [];
      const tr = r.topic_requests || [];
      if (!fr.length && !tr.length) {
        box.innerHTML = `<div class="empty">暂无待处理申请</div>`;
        return;
      }
      let html = "";
      if (fr.length) {
        html += `<div class="req-section">好友申请</div>` + fr.map(x => reqCard({
          id: x.id, title: x.username, sub: x.message || "（无验证消息）", time: x.requested_at,
          actions: `
            <button class="btn sm" data-fa="${x.id}">同意</button>
            <button class="btn danger sm" data-fr="${x.id}">拒绝</button>
            <button class="btn ghost sm" data-fi="${x.id}">忽略</button>`,
        })).join("");
      }
      if (tr.length) {
        html += `<div class="req-section">加群申请</div>` + tr.map(x => reqCard({
          id: x.id, title: `${x.username} 申请加入 #${x.topic}`, sub: x.message || "（无验证消息）", time: x.requested_at,
          actions: `
            <button class="btn sm" data-ta="${x.id}" data-topic="${esc(x.topic)}">同意</button>
            <button class="btn danger sm" data-tr="${x.id}" data-topic="${esc(x.topic)}">拒绝</button>`,
        })).join("");
      }
      box.innerHTML = html;

      box.querySelectorAll("[data-fa]").forEach(b => b.onclick = () => handleFriendReq(b.dataset.fa, "accept"));
      box.querySelectorAll("[data-fr]").forEach(b => b.onclick = () => handleFriendReq(b.dataset.fr, "reject"));
      box.querySelectorAll("[data-fi]").forEach(b => b.onclick = () => handleFriendReq(b.dataset.fi, "ignore"));
      box.querySelectorAll("[data-ta]").forEach(b => b.onclick = () => handleTopicReq(b.dataset.topic, b.dataset.ta, "approve"));
      box.querySelectorAll("[data-tr]").forEach(b => b.onclick = () => handleTopicReq(b.dataset.topic, b.dataset.tr, "reject"));
    } catch (e) { box.innerHTML = `<div class="empty">加载失败：${esc(e.message)}</div>`; }
  }

  function reqCard(o) {
    return `<div class="req-card" data-req="${o.id}">
      <div class="req-head"><b>${esc(o.title)}</b><span class="req-time">${fmtTime(o.time)}</span></div>
      <div class="req-msg">${esc(o.sub)}</div>
      <div class="req-actions">${o.actions}</div>
    </div>`;
  }

  async function handleFriendReq(id, act) {
    try {
      await api(`/api/friends/requests/${id}/${act}`, { method: "POST" });
      toast(act === "accept" ? "已同意，你们现在是好友了" : act === "reject" ? "已拒绝" : "已忽略", "ok");
      await loadRequests();
      if (state.chat && state.chat.special === "requests") loadRequestsView(); else renderSessionList();
    } catch (e) { toast(e.message, "err"); }
  }
  async function handleTopicReq(topic, id, act) {
    try {
      await api(`/api/topics/${topic}/requests/${id}/${act}`, { method: "POST" });
      toast(act === "approve" ? "已通过" : "已拒绝", "ok");
      await loadRequests();
      if (state.chat && state.chat.special === "requests") loadRequestsView(); else renderSessionList();
    } catch (e) { toast(e.message, "err"); }
  }

  async function showTopicRequests(topic) {
    try {
      const r = await api("/api/topics/" + topic + "/requests");
      const pending = (r.requests || []).filter(x => x.status === "pending");
      if (!pending.length) return toast("暂无待审批申请", "ok");
      openModal(`#${esc(topic)} 待审批`, pending.map(x => reqCard({
        id: x.id, title: `${x.username} 申请加入`, sub: x.message || "（无验证消息）", time: x.requested_at,
        actions: `
          <button class="btn sm" data-ta2="${x.id}">通过</button>
          <button class="btn danger sm" data-tr2="${x.id}">拒绝</button>`,
      })).join(""));
      // 绑定在 modal 内
      document.querySelectorAll("[data-ta2]").forEach(b => b.onclick = async () => {
        await handleTopicReq(topic, b.dataset.ta2, "approve");
        b.closest(".req-card").remove();
      });
      document.querySelectorAll("[data-tr2]").forEach(b => b.onclick = async () => {
        await handleTopicReq(topic, b.dataset.tr2, "reject");
        b.closest(".req-card").remove();
      });
    } catch (e) { toast(e.message, "err"); }
  }

  function openModal(title, html) {
    closeModal();
    const overlay = document.createElement("div");
    overlay.className = "modal-mask";
    overlay.id = "autoModal";
    overlay.innerHTML = `<div class="modal"><div class="modal-title">${title}</div>${html}<div style="text-align:right;margin-top:14px"><button class="btn ghost" onclick="document.getElementById('autoModal').remove()">关闭</button></div></div>`;
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
    document.body.appendChild(overlay);
    return overlay;
  }
  function closeModal() {
    const m = document.getElementById("autoModal");
    if (m) m.remove();
  }

  // ================= 好友页 =================
  async function renderFriends(main) {
    main.innerHTML = `
      <div class="page-head-row">
        <div>
          <h2 class="page-title">好友</h2>
          <p class="page-sub">好友之间可以点对点私聊（dm 会话，支持图片 / 语音 / 文件）</p>
        </div>
        <div class="toolbar" style="margin:0">
          <button class="btn" id="fr-add">＋ 加好友</button>
          <button class="btn ghost" id="fr-discover">🔍 发现话题</button>
          <button class="btn ghost" id="fr-newtopic">＃ 新建话题</button>
        </div>
      </div>
      <div id="frList" class="friend-grid"><div class="empty" style="grid-column:1/-1">加载中…</div></div>
      <h3 class="section-title">我收到的申请</h3>
      <div id="frIn"></div>
      <h3 class="section-title">我发出的申请</h3>
      <div id="frOut"></div>`;

    document.getElementById("fr-add").onclick = showAddFriend;
    document.getElementById("fr-discover").onclick = showDiscover;
    document.getElementById("fr-newtopic").onclick = showNewTopic;

    const [fr, reqs] = await Promise.all([
      api("/api/friends").catch(() => ({ friends: [] })),
      api("/api/friends/requests").catch(() => ({ incoming: [], outgoing: [] })),
    ]);

    const box = document.getElementById("frList");
    if (!fr.friends.length) {
      box.innerHTML = `<div class="empty" style="grid-column:1/-1">还没有好友。点「＋ 加好友」搜索用户名发送申请。</div>`;
    } else {
      box.innerHTML = fr.friends.map(f => `
        <div class="card friend-card">
          <div style="display:flex;align-items:center;gap:12px">
            ${avatarHtml(f.display_name || f.username, f.avatar, 44)}
            <div style="flex:1;min-width:0">
              <b>${esc(f.display_name || f.username)}</b>
              <div style="color:var(--muted);font-size:12px">@${esc(f.username)}</div>
            </div>
          </div>
          <div style="display:flex;gap:8px;margin-top:12px">
            <button class="btn sm" data-chat="${esc(f.username)}" style="flex:1">💬 私聊</button>
            <button class="btn danger sm" data-unfriend="${esc(f.username)}">删除</button>
          </div>
        </div>`).join("");
      box.querySelectorAll("[data-chat]").forEach(b => b.onclick = async () => {
        try {
          const r = await api("/api/friends/chat/" + b.dataset.chat, { method: "POST" });
          state.tab = "messages";
          state.chat = null;
          await loadTopics();
          render();
          const t = state.topics.find(x => x.name === r.topic);
          if (t) openChat(t); else toast("会话已创建，请刷新", "ok");
        } catch (e) { toast(e.message, "err"); }
      });
      box.querySelectorAll("[data-unfriend]").forEach(b => b.onclick = async () => {
        if (!confirm(`确定删除好友 ${b.dataset.unfriend}？私聊记录保留但无法继续发送。`)) return;
        try { await api("/api/friends/" + b.dataset.unfriend, { method: "DELETE" }); toast("已删除好友", "ok"); renderFriends(main); }
        catch (e) { toast(e.message, "err"); }
      });
    }

    const inBox = document.getElementById("frIn");
    const inc = (reqs.incoming || []).filter(x => x.status === "pending");
    inBox.innerHTML = inc.length ? inc.map(x => reqCard({
      id: x.id, title: `${x.username} 请求加你为好友`, sub: x.message || "（无验证消息）", time: x.requested_at,
      actions: `<button class="btn sm" data-fa="${x.id}">同意</button>
                <button class="btn danger sm" data-fr="${x.id}">拒绝</button>
                <button class="btn ghost sm" data-fi="${x.id}">忽略</button>`,
    })).join("") : `<div class="empty">暂无</div>`;
    bindFriendReqButtons(inBox);

    const outBox = document.getElementById("frOut");
    const out = (reqs.outgoing || []).filter(x => x.status === "pending");
    outBox.innerHTML = out.length ? out.map(x => reqCard({
      id: x.id, title: `等待 ${x.username} 处理`, sub: x.message || "（无验证消息）", time: x.requested_at,
      actions: "",
    })).join("") : `<div class="empty">暂无</div>`;
  }

  function bindFriendReqButtons(scope) {
    scope.querySelectorAll("[data-fa]").forEach(b => b.onclick = async () => {
      await handleFriendReq(b.dataset.fa, "accept"); renderFriends(document.getElementById("main"));
    });
    scope.querySelectorAll("[data-fr]").forEach(b => b.onclick = async () => {
      await handleFriendReq(b.dataset.fr, "reject"); renderFriends(document.getElementById("main"));
    });
    scope.querySelectorAll("[data-fi]").forEach(b => b.onclick = async () => {
      await handleFriendReq(b.dataset.fi, "ignore"); renderFriends(document.getElementById("main"));
    });
  }

  function showAddFriend() {
    const ov = openModal("加好友", `
      <label>搜索用户</label>
      <div class="row"><input id="af-q" type="text" placeholder="输入对方用户名" /><button class="btn ghost" id="af-go" style="flex:0 0 auto">搜索</button></div>
      <div id="af-result" style="margin-top:10px"></div>
      <label style="margin-top:16px">或直接发送申请</label>
      <input id="af-name" type="text" placeholder="对方用户名" />
      <label>验证消息</label>
      <input id="af-msg" type="text" placeholder="我是…（可选，最多 300 字）" />
      <button class="btn block" id="af-send" style="margin-top:16px">发送好友申请</button>`);
    const result = ov.querySelector("#af-result");
    ov.querySelector("#af-go").onclick = async () => {
      const q = ov.querySelector("#af-q").value.trim();
      if (!q) return;
      try {
        const r = await api("/api/friends/search?q=" + encodeURIComponent(q));
        result.innerHTML = (r.users || []).length ? r.users.map(u => `
          <div class="req-card">
            <div style="display:flex;align-items:center;gap:10px">
              ${avatarHtml(u.display_name || u.username, u.avatar, 36)}
              <div style="flex:1"><b>${esc(u.display_name || u.username)}</b><div style="color:var(--muted);font-size:12px">@${esc(u.username)}</div></div>
              ${u.is_friend ? `<span class="badge member">已是好友</span>` : u.requested ? `<span class="badge warn">已申请</span>` : `<button class="btn sm" data-add="${esc(u.username)}">加好友</button>`}
            </div>
          </div>`).join("") : `<div class="empty">未找到用户</div>`;
        result.querySelectorAll("[data-add]").forEach(b => b.onclick = () => {
          ov.querySelector("#af-name").value = b.dataset.add;
          ov.querySelector("#af-msg").focus();
          toast("已填入用户名，可补充验证消息后发送", "ok");
        });
      } catch (e) { toast(e.message, "err"); }
    };
    ov.querySelector("#af-send").onclick = async () => {
      const username = ov.querySelector("#af-name").value.trim();
      const message = ov.querySelector("#af-msg").value.trim();
      if (!username) return toast("请输入用户名", "err");
      try {
        await api("/api/friends/requests", { method: "POST", body: { username, message } });
        toast("申请已发送，等待对方处理", "ok");
        ov.remove();
      } catch (e) { toast(e.message, "err"); }
    };
  }

  function showDiscover() {
    const ov = openModal("发现话题", `<div id="ds-list"><div class="chat-loading">加载中…</div></div>
      <label style="margin-top:14px">凭话题名直接申请</label>
      <div class="row"><input id="ds-name" type="text" placeholder="话题名" /><button class="btn ghost" id="ds-go" style="flex:0 0 auto">申请加入</button></div>`);
    (async () => {
      const r = await api("/api/topics/discover").catch(() => ({ topics: [] }));
      const box = ov.querySelector("#ds-list");
      box.innerHTML = (r.topics || []).length ? r.topics.map(t => `
        <div class="req-card">
          <div class="req-head"><b>#${esc(t.name)}</b><span class="badge member">${t.member_count} 人</span></div>
          <div class="req-msg">创建者 ${esc(t.owner_name || "-")} · ${t.message_count} 条消息</div>
          <div class="req-actions"><button class="btn sm" data-join="${esc(t.name)}">申请加入</button></div>
        </div>`).join("") : `<div class="empty">暂无可发现的话题</div>`;
      box.querySelectorAll("[data-join]").forEach(b => b.onclick = () => joinTopic(b.dataset.join, ov));
    })();
    ov.querySelector("#ds-go").onclick = () => {
      const n = ov.querySelector("#ds-name").value.trim().toLowerCase();
      if (!n) return toast("请输入话题名", "err");
      joinTopic(n, ov);
    };
  }

  async function joinTopic(name, ov) {
    const msg = prompt(`申请加入 #${name}\n验证消息（可选）：`, "") || "";
    try {
      await api("/api/topics/" + name + "/join", { method: "POST", body: { message: msg.trim() } });
      toast("已发送加入申请，等待创建者审批", "ok");
      if (ov) ov.remove();
    } catch (e) { toast(e.message, "err"); }
  }

  function showNewTopic() {
    const ov = openModal("新建话题", `
      <label>话题名（字母/数字/_/-）</label>
      <input id="nt-name" type="text" placeholder="例如 dev-talk" />
      <label>标题（可选）</label>
      <input id="nt-title" type="text" placeholder="显示标题" />
      <button class="btn block" id="nt-go" style="margin-top:16px">创建</button>`);
    ov.querySelector("#nt-go").onclick = async () => {
      const name = ov.querySelector("#nt-name").value.trim().toLowerCase();
      const title = ov.querySelector("#nt-title").value.trim();
      if (!/^[a-z0-9_-]{1,64}$/.test(name)) return toast("话题名不合法（1-64位字母/数字/_/-）", "err");
      try {
        await api("/api/topics", { method: "POST", body: { name, title } });
        toast("话题已创建", "ok");
        ov.remove();
        if (state.tab === "messages") renderMessages(document.getElementById("main"));
      } catch (e) { toast(e.message, "err"); }
    };
  }

  // ---------- Overview ----------
  async function renderOverview(main) {
    main.innerHTML = `<h2 class="page-title">概览</h2><p class="page-sub">服务器状态与快速信息</p><div id="ov">加载中…</div>`;
    const [info, dev, topics, stats] = await Promise.all([
      api("/api/info"),
      api("/api/devices").catch(() => ({ devices: [] })),
      api("/api/topics").catch(() => ({ topics: [] })),
      api("/api/admin/stats").catch(() => null),
    ]);
    const notif = await api("/api/notifications?limit=50").catch(() => ({ notifications: [] }));
    const s = stats || {};
    document.getElementById("ov").innerHTML = `
      <div class="grid" style="margin-bottom:16px">
        <div class="card stat"><div class="label">已注册设备</div><div class="value">${dev.devices.length}</div></div>
        <div class="card stat"><div class="label">我的会话数</div><div class="value">${topics.topics.length}</div></div>
        <div class="card stat"><div class="label">最近通知(50条内)</div><div class="value">${notif.notifications.length}</div></div>
        ${state.role === "admin" ? `<div class="card stat"><div class="label">平台用户总数</div><div class="value">${s.users ?? "-"}</div></div>` : ""}
      </div>
      <div class="card">
        <div class="kv"><span class="k">服务名称</span><span>${esc(info.name)}</span></div>
        <div class="kv"><span class="k">版本</span><span>${esc(info.version)}</span></div>
        <div class="kv"><span class="k">已运行时长</span><span>${fmtUptime(info.uptime)}</span></div>
        <div class="kv"><span class="k">数据目录</span><span class="mono">${esc(info.dataDir)}</span></div>
      </div>
      <div class="hint" style="margin-top:16px">
        <b>快速上手：</b>① 在「消息」里和好友/群聊对话（支持图片、语音、文件）；② 在「好友」页搜索用户名加好友；
        ③ 手机 App 登录同一账号即可互通；④ 在「应用过滤」里决定同步哪些 App 的通知。
        ${state.role === "admin" ? "⑤ 你拥有「用户管理 / 全部话题 / 全部通知」管理权限。" : ""}
      </div>`;
  }

  // ---------- Devices ----------
  async function renderDevices(main) {
    main.innerHTML = `<h2 class="page-title">设备</h2>
      <p class="page-sub">已连接到你账号的设备（由手机 App 自动注册）</p>
      <div id="dv">加载中…</div>`;
    const r = await api("/api/devices");
    const box = document.getElementById("dv");
    if (!r.devices.length) {
      box.innerHTML = `<div class="empty">还没有设备。在手机 App 登录同一账号后会自动出现。</div>`;
      return;
    }
    box.innerHTML = `<table><thead><tr><th>设备名</th><th>平台</th><th>最近在线</th><th></th></tr></thead><tbody>
      ${r.devices.map(d => `<tr>
        <td>${esc(d.device_name)}</td>
        <td>${esc(d.platform || "-")}</td>
        <td>${fmtTime(d.last_seen)}</td>
        <td style="text-align:right;white-space:nowrap">
          <button class="btn sm" data-rename="${d.id}">重命名</button>
          <button class="btn danger sm" data-del="${d.id}">移除</button>
        </td>
      </tr>`).join("")}
    </tbody></table>`;
    box.querySelectorAll("[data-rename]").forEach(b => {
      b.onclick = async () => {
        const old = r.devices.find(d => d.id == b.dataset.rename)?.device_name || "";
        const name = prompt("输入新的设备名（通知里的设备名会同步更新）:", old);
        if (!name || !name.trim()) return;
        try {
          await api("/api/devices/" + b.dataset.rename + "/name", { method: "PUT", body: { device_name: name.trim() } });
          toast("已重命名", "ok");
          renderDevices(main);
        } catch (e) { toast(e.message, "err"); }
      };
    });
    box.querySelectorAll("[data-del]").forEach(b => {
      b.onclick = async () => {
        if (!confirm("确定移除该设备？")) return;
        try { await api("/api/devices/" + b.dataset.del, { method: "DELETE" }); toast("已移除", "ok"); renderDevices(main); }
        catch (e) { toast(e.message, "err"); }
      };
    });
  }

  // ---------- Filters ----------
  async function renderFilters(main) {
    main.innerHTML = `<h2 class="page-title">应用过滤</h2>
      <p class="page-sub">决定哪些 App 的通知会被同步。启用且列表非空时，仅列表内的 App 会同步。</p>
      <div class="card" style="margin-bottom:16px">
        <div class="row">
          <div><label>应用包名 (package_name)</label><input id="f-pkg" placeholder="例如 com.whatsapp" /></div>
          <div><label>应用名称 (app_name)</label><input id="f-name" placeholder="例如 WhatsApp" /></div>
        </div>
        <label class="check"><input id="f-en" type="checkbox" checked /> 启用同步</label>
        <button class="btn" id="f-add" style="margin-top:14px">添加过滤项</button>
      </div>
      <div id="fl">加载中…</div>`;
    document.getElementById("f-add").onclick = async () => {
      const package_name = document.getElementById("f-pkg").value.trim();
      const app_name = document.getElementById("f-name").value.trim();
      if (!package_name || !app_name) return toast("包名和应用名都要填", "err");
      try {
        await api("/api/filters", { method: "POST", body: { package_name, app_name, enabled: document.getElementById("f-en").checked } });
        toast("已添加", "ok");
        document.getElementById("f-pkg").value = ""; document.getElementById("f-name").value = "";
        renderFilters(main);
      } catch (e) { toast(e.message, "err"); }
    };
    const r = await api("/api/filters");
    const box = document.getElementById("fl");
    if (!r.filters.length) { box.innerHTML = `<div class="empty">还没有过滤项。添加后，只有列表内的 App 通知会被同步。</div>`; return; }
    box.innerHTML = `<table><thead><tr><th>应用名称</th><th>包名</th><th>状态</th><th></th></tr></thead><tbody>
      ${r.filters.map(f => `<tr>
        <td>${esc(f.app_name)}</td>
        <td class="mono">${esc(f.package_name)}</td>
        <td><span class="badge ${f.enabled ? "on" : "off"}">${f.enabled ? "同步中" : "已停用"}</span></td>
        <td style="text-align:right"><button class="btn danger sm" data-del="${encodeURIComponent(f.package_name)}">删除</button></td>
      </tr>`).join("")}
    </tbody></table>`;
    box.querySelectorAll("[data-del]").forEach(b => {
      b.onclick = async () => {
        if (!confirm("删除该过滤项？")) return;
        try { await api("/api/filters/" + b.dataset.del, { method: "DELETE" }); toast("已删除", "ok"); renderFilters(main); }
        catch (e) { toast(e.message, "err"); }
      };
    });
  }

  // ---------- Account ----------
  async function renderAccount(main) {
    main.innerHTML = `<h2 class="page-title">账号</h2>
      <p class="page-sub">当前登录：<b>@${esc(state.username)}</b>${state.role === "admin" ? "（管理员）" : ""}</p>
      <div class="card" style="max-width:460px;margin-bottom:16px">
        <div style="display:flex;align-items:center;gap:16px;margin-bottom:6px">
          ${avatarHtml(state.display_name || state.username, state.avatar, 64)}
          <div>
            <button class="btn ghost sm" id="a-av-pick">更换头像</button>
            <input type="file" id="a-av" accept="image/*" style="display:none" />
            <div style="color:var(--muted);font-size:12px;margin-top:6px">支持 JPG/PNG，最大 5MB</div>
          </div>
        </div>
        <label>昵称</label>
        <div class="row"><input id="a-nick" type="text" placeholder="昵称（好友和聊天里显示）" value="${esc(state.display_name || "")}" /><button class="btn ghost" id="a-nick-go" style="flex:0 0 auto">保存</button></div>
      </div>
      <div class="card" style="max-width:460px;margin-bottom:16px">
        <label>当前密码</label>
        <input id="a-old" type="password" />
        <label>新密码（至少 6 位）</label>
        <input id="a-new" type="password" />
        <button class="btn" id="a-chg" style="margin-top:14px">修改密码</button>
      </div>
      <div class="card danger-zone" style="max-width:460px">
        <label style="color:var(--danger)">退出登录</label>
        <button class="btn danger" id="a-out">退出当前账号</button>
      </div>`;

    const avInput = document.getElementById("a-av");
    document.getElementById("a-av-pick").onclick = () => avInput.click();
    avInput.onchange = async () => {
      const f = avInput.files[0];
      if (!f) return;
      try {
        const fd = new FormData();
        fd.append("file", f);
        const res = await fetch(`${API_BASE}/api/user/avatar`, {
          method: "POST",
          headers: { Authorization: "Bearer " + state.token },
          body: fd,
        });
        const j = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(j.error || "上传失败");
        state.avatar = j.avatar + "?t=" + Date.now();
        toast("头像已更新", "ok");
        renderAccount(main);
        render();
      } catch (e) { toast(e.message, "err"); }
      finally { avInput.value = ""; }
    };
    document.getElementById("a-nick-go").onclick = async () => {
      const name = document.getElementById("a-nick").value.trim();
      if (!name) return toast("昵称不能为空", "err");
      try {
        await api("/api/user/nickname", { method: "PUT", body: { display_name: name } });
        state.display_name = name;
        toast("昵称已保存", "ok");
        render();
      } catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("a-chg").onclick = async () => {
      const oldPassword = document.getElementById("a-old").value;
      const newPassword = document.getElementById("a-new").value;
      if (!oldPassword || !newPassword) return toast("请填写两项密码", "err");
      try { await api("/api/auth/change-password", { method: "POST", body: { oldPassword, newPassword } }); toast("密码已修改", "ok"); document.getElementById("a-old").value = ""; document.getElementById("a-new").value = ""; }
      catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("a-out").onclick = () => { logout(); };
  }

  // ---------- Admin: Users ----------
  async function renderAdminUsers(main) {
    main.innerHTML = `<h2 class="page-title">用户管理</h2>
      <p class="page-sub">管理平台所有用户：新增、查看、删除。</p>
      <div class="card" style="margin-bottom:16px">
        <div class="row">
          <div><label>用户名</label><input id="u-name" placeholder="3-32 位" /></div>
          <div><label>密码</label><input id="u-pass" type="password" placeholder="至少 6 位" /></div>
          <div style="flex:0 0 140px"><label>角色</label>
            <select id="u-role"><option value="user">普通用户</option><option value="admin">管理员</option></select>
          </div>
        </div>
        <button class="btn" id="u-add" style="margin-top:14px">新增用户</button>
      </div>
      <div id="ul">加载中…</div>`;
    document.getElementById("u-add").onclick = async () => {
      const username = document.getElementById("u-name").value.trim();
      const password = document.getElementById("u-pass").value;
      const role = document.getElementById("u-role").value;
      if (!username || !password) return toast("用户名和密码都要填", "err");
      try { await api("/api/admin/users", { method: "POST", body: { username, password, role } }); toast("用户已创建", "ok"); document.getElementById("u-name").value = ""; document.getElementById("u-pass").value = ""; renderAdminUsers(main); }
      catch (e) { toast(e.message, "err"); }
    };
    const r = await api("/api/admin/users");
    const box = document.getElementById("ul");
    if (!r.users.length) { box.innerHTML = `<div class="empty">暂无用户。</div>`; return; }
    box.innerHTML = `<table><thead><tr><th>用户名</th><th>角色</th><th>通知</th><th>话题</th><th>设备</th><th>创建时间</th><th></th></tr></thead><tbody>
      ${r.users.map(u => `<tr>
        <td>${esc(u.username)}</td>
        <td><span class="badge ${u.role === "admin" ? "admin" : "member"}">${u.role === "admin" ? "管理员" : "用户"}</span></td>
        <td>${u.notification_count}</td>
        <td>${u.topic_count}</td>
        <td>${u.device_count}</td>
        <td>${fmtTime(u.created_at)}</td>
        <td style="text-align:right"><button class="btn danger sm" data-del="${u.id}" ${u.username === state.username ? "disabled" : ""}>删除</button></td>
      </tr>`).join("")}
    </tbody></table>`;
    box.querySelectorAll("[data-del]").forEach(b => {
      b.onclick = async () => {
        if (!confirm("删除该用户及其所有数据？此操作不可恢复。")) return;
        try { await api("/api/admin/users/" + b.dataset.del, { method: "DELETE" }); toast("已删除", "ok"); renderAdminUsers(main); }
        catch (e) { toast(e.message, "err"); }
      };
    });
  }

  // ---------- Admin: All Topics ----------
  async function renderAdminTopics(main) {
    main.innerHTML = `<h2 class="page-title">全部话题</h2>
      <p class="page-sub">查看平台上所有话题及其消息（管理员可见）。</p>
      <div id="at">加载中…</div>`;
    const r = await api("/api/admin/topics");
    const box = document.getElementById("at");
    if (!r.topics.length) { box.innerHTML = `<div class="empty">暂无话题。</div>`; return; }
    box.innerHTML = `<table><thead><tr><th>话题</th><th>创建者</th><th>成员</th><th>消息</th><th>创建时间</th><th></th></tr></thead><tbody>
      ${r.topics.map(t => `<tr>
        <td><b>#${esc(t.name)}</b>${t.title ? `<div style="color:var(--muted);font-size:12px">${esc(t.title)}</div>` : ""}</td>
        <td>${esc(t.owner_name || "-")}</td>
        <td>${t.member_count}</td>
        <td>${t.message_count}</td>
        <td>${fmtTime(t.created_at)}</td>
        <td style="text-align:right">
          <button class="btn ghost sm" data-view="${esc(t.name)}">查看消息</button>
          <button class="btn danger sm" data-del="${esc(t.name)}">删除</button>
        </td>
      </tr>`).join("")}
    </tbody></table>`;
    box.querySelectorAll("[data-view]").forEach(b => b.onclick = async () => {
      try {
        const r2 = await api("/api/admin/topics/" + b.dataset.view + "/messages?limit=100");
        const txt = r2.messages.map(m => `[${fmtTime(m.timestamp)}] ${m.sender_name || "?"}: ${m.text || ""}`).join("\n");
        openModal(`#${esc(b.dataset.view)} 的消息（${r2.messages.length}）`, `<pre class="msgpre">${esc(txt || "（无消息）")}</pre>`);
      } catch (e) { toast(e.message, "err"); }
    });
    box.querySelectorAll("[data-del]").forEach(b => b.onclick = async () => {
      if (!confirm("删除该话题及其全部消息？")) return;
      try { await api("/api/admin/topics/" + b.dataset.del, { method: "DELETE" }); toast("已删除", "ok"); renderAdminTopics(main); }
      catch (e) { toast(e.message, "err"); }
    });
  }

  // ---------- Admin: All Notifications ----------
  async function renderAdminNotifs(main) {
    main.innerHTML = `<h2 class="page-title">全部通知</h2>
      <p class="page-sub">平台上所有用户的通知记录（按用户筛选）。</p>
      <div class="toolbar">
        <input id="an-user" type="text" placeholder="按用户名筛选（可选）" style="max-width:200px" />
        <button class="btn" id="an-go">筛选</button>
        <div class="grow"></div>
      </div>
      <div id="an">加载中…</div>`;
    const load = async () => {
      const user = document.getElementById("an-user").value.trim();
      let url = "/api/admin/notifications?limit=200";
      if (user) {
        const ur = await api("/api/admin/users").catch(() => ({ users: [] }));
        const found = ur.users.find(u => u.username === user);
        if (!found) { document.getElementById("an").innerHTML = `<div class="empty">未找到用户 ${esc(user)}</div>`; return; }
        url += "&userId=" + found.id;
      }
      const r = await api(url);
      const box = document.getElementById("an");
      if (!r.notifications.length) { box.innerHTML = `<div class="empty">暂无通知。</div>`; return; }
      box.innerHTML = `<table><thead><tr><th>用户</th><th>应用</th><th>标题</th><th>内容</th><th>来源设备</th><th>时间</th><th></th></tr></thead><tbody>
        ${r.notifications.map(n => `<tr>
          <td>${esc(n.username || "-")}</td>
          <td>${esc(n.app_name)}</td>
          <td>${esc(n.title)}</td>
          <td>${esc(n.text)}</td>
          <td>${esc(n.device_name || "-")}</td>
          <td>${fmtTime(n.timestamp)}</td>
          <td style="text-align:right"><button class="btn danger sm" data-del="${n.id}">删除</button></td>
        </tr>`).join("")}
      </tbody></table>`;
      box.querySelectorAll("[data-del]").forEach(b => b.onclick = async () => {
        try { await api("/api/admin/notifications/" + b.dataset.del, { method: "DELETE" }); b.closest("tr").remove(); toast("已删除", "ok"); }
        catch (e) { toast(e.message, "err"); }
      });
    };
    document.getElementById("an-go").onclick = load;
    await load();
  }

  // ---------- Admin: Server Settings ----------
  async function renderAdminSettings(main) {
    main.innerHTML = `<h2 class="page-title">服务器设置</h2>
      <p class="page-sub">配置话题中可发送的 图片 / 语音 / 文件 大小上限，以及每个话题保留的消息历史条数。设置即时生效。</p>
      <div class="card" style="max-width:560px">
        <div class="row">
          <div><label>图片大小上限（MB）</label><input id="s-img" type="number" min="0" step="0.5" /></div>
          <div><label>语音大小上限（MB）</label><input id="s-voice" type="number" min="0" step="0.5" /></div>
        </div>
        <div class="row">
          <div><label>文件大小上限（MB）</label><input id="s-file" type="number" min="0" step="0.5" /></div>
          <div><label>话题历史保留（条）</label><input id="s-hist" type="number" min="0" step="10" /></div>
        </div>
        <div class="hint" style="margin-top:8px">数值 0 表示不限制（但服务端硬性上限为 100MB/单个文件）。超出上限的文件会被拒绝上传。</div>
        <button class="btn" id="s-save" style="margin-top:14px">保存设置</button>
      </div>
      <div id="s-status" style="margin-top:12px"></div>`;
    const status = document.getElementById("s-status");
    try {
      const r = await api("/api/admin/settings");
      const s = r.settings || {};
      document.getElementById("s-img").value = s.max_image_size ?? 10;
      document.getElementById("s-voice").value = s.max_voice_size ?? 5;
      document.getElementById("s-file").value = s.max_file_size ?? 20;
      document.getElementById("s-hist").value = s.max_topic_history ?? 200;
    } catch (e) { status.innerHTML = `<div class="empty">读取设置失败：${esc(e.message)}</div>`; }
    document.getElementById("s-save").onclick = async () => {
      const patch = {
        max_image_size: document.getElementById("s-img").value,
        max_voice_size: document.getElementById("s-voice").value,
        max_file_size: document.getElementById("s-file").value,
        max_topic_history: document.getElementById("s-hist").value,
      };
      const btn = document.getElementById("s-save");
      btn.disabled = true;
      try {
        const r = await api("/api/admin/settings", { method: "PUT", body: { settings: patch } });
        const s = r.settings || patch;
        status.innerHTML = `<div class="label" style="color:#1a7f37;font-weight:600">已保存。当前上限：图片 ${esc(s.max_image_size)}MB · 语音 ${esc(s.max_voice_size)}MB · 文件 ${esc(s.max_file_size)}MB · 历史 ${esc(s.max_topic_history)} 条。</div>`;
        toast("设置已保存", "ok");
      } catch (e) { status.innerHTML = `<div class="empty">保存失败：${esc(e.message)}</div>`; }
      finally { btn.disabled = false; }
    };
  }

  // ---------- 主题切换（浅色 / 深色 / 跟随系统 三态循环） ----------
  const THEME_ICON = { light: "🌙", dark: "☀️", system: "🖥️" };
  const THEME_LABEL = { light: "已切换到浅色主题", dark: "已切换到深色主题", system: "已跟随系统外观" };
  function applyTheme(mode) {
    const dark = mode === "dark" || (mode === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    document.documentElement.setAttribute("data-theme-mode", mode);
    const btn = document.getElementById("themeToggle");
    if (btn) btn.textContent = THEME_ICON[mode] || THEME_ICON.system;
  }
  function setupThemeToggle() {
    const btn = document.getElementById("themeToggle");
    if (!btn) return;
    const saved = localStorage.getItem("ns_theme") || "system";
    applyTheme(saved);
    btn.textContent = THEME_ICON[saved] || THEME_ICON.system;
    btn.onclick = () => {
      const cur = localStorage.getItem("ns_theme") || "system";
      const next = cur === "light" ? "dark" : cur === "dark" ? "system" : "light";
      localStorage.setItem("ns_theme", next);
      applyTheme(next);
      toast(THEME_LABEL[next], "ok");
    };
    if (saved === "system") {
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
        if ((localStorage.getItem("ns_theme") || "system") === "system") applyTheme("system");
      });
    }
  }

  // ---------- boot ----------
  function boot() {
    render();
    if (state.token) {
      api("/api/auth/me")
        .then(r => {
          state.username = r.user.username;
          state.role = r.user.role || "user";
          state.userId = r.user.id || state.userId;
          localStorage.setItem("ns_username", state.username);
          localStorage.setItem("ns_role", state.role);
          if (r.user.id) localStorage.setItem("ns_uid", r.user.id);
          return loadProfile();
        })
        .then(() => render())
        .catch(() => { logout(); });
    }
  }

  setupThemeToggle();
  // Tauri 桌面端：先异步取 server_url 作为 API 基址再启动；浏览器同源直接启动
  initApiBase().then(boot);
})();
