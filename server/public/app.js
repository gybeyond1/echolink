/* NotifySync Web 管理后台 —— 纯前端，调用同域 REST API */
(function () {
  "use strict";

  const app = document.getElementById("app");
  const toastBox = document.getElementById("toast");

  const state = {
    token: localStorage.getItem("ns_token") || "",
    username: localStorage.getItem("ns_username") || "",
    role: localStorage.getItem("ns_role") || "user",
    tab: "overview",
    topic: null,
  };

  // ---------- helpers ----------
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
  function fmtUptime(s) {
    s = Math.floor(s);
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (h > 0) return `${h} 小时 ${m} 分`;
    if (m > 0) return `${m} 分 ${sec} 秒`;
    return `${sec} 秒`;
  }
  function toast(msg, type) {
    const el = document.createElement("div");
    el.className = "toast-item " + (type || "");
    el.textContent = msg;
    toastBox.appendChild(el);
    setTimeout(() => el.remove(), 3200);
  }

  function fmtSize(bytes) {
    bytes = Number(bytes) || 0;
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / 1024 / 1024).toFixed(1) + " MB";
  }
  function mediaHtml(m) {
    if (!m.media_type || m.media_type === "text" || !m.media_url) return "";
    const url = m.media_url;
    if (m.media_type === "image") return `<div style="margin-top:4px"><a href="${esc(url)}" target="_blank"><img src="${esc(url)}" style="max-width:240px;max-height:240px;border-radius:8px" /></a></div>`;
    if (m.media_type === "voice") return `<div style="margin-top:4px"><audio controls src="${esc(url)}"></audio></div>`;
    if (m.media_type === "file") return `<div style="margin-top:4px"><a class="btn ghost sm" href="${esc(url)}" target="_blank" download>${esc(m.media_name || "文件")}（${fmtSize(m.media_size)}）</a></div>`;
    return "";
  }

  async function api(path, opts) {
    opts = opts || {};
    const headers = { "Content-Type": "application/json" };
    if (state.token) headers["Authorization"] = "Bearer " + state.token;
    const res = await fetch(path, {
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

  function saveAuth(token, username, role) {
    state.token = token; state.username = username; state.role = role || "user";
    localStorage.setItem("ns_token", token);
    localStorage.setItem("ns_username", username);
    localStorage.setItem("ns_role", state.role);
  }
  function logout() {
    state.token = ""; state.username = ""; state.role = "user"; state.topic = null;
    localStorage.removeItem("ns_token");
    localStorage.removeItem("ns_username");
    localStorage.removeItem("ns_role");
    render();
  }

  // ---------- Auth screen ----------
  function renderAuth() {
    app.innerHTML = `
      <div class="auth-wrap">
        <div class="auth-card">
          <div class="brand">
            <div class="logo">N</div>
            <h1>NotifySync</h1>
            <p>跨设备通知同步 · 管理后台</p>
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
        saveAuth(r.token, r.user.username, r.user.role);
        toast(mode === "login" ? "登录成功" : "注册成功，已自动登录", "ok");
        render();
      } catch (e) {
        toast(e.message, "err");
      } finally {
        submit.disabled = false;
      }
    };
  }

  // ---------- Dashboard shell ----------
  const BASE_NAV = [
    { id: "overview", ic: "📊", label: "概览" },
    { id: "devices", ic: "📱", label: "设备" },
    { id: "filters", ic: "🧩", label: "应用过滤" },
    { id: "notifications", ic: "🔔", label: "通知" },
    { id: "topics", ic: "💬", label: "话题" },
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
    app.innerHTML = `
      <div class="layout">
        <aside class="sidebar">
          <div class="brand"><div class="logo">N</div><h1>NotifySync</h1></div>
          ${navItems().map(n => `<button class="nav-item ${state.tab === n.id ? "active" : ""}" data-tab="${n.id}"><span class="ic">${n.ic}</span>${n.label}</button>`).join("")}
          <div class="spacer"></div>
          <div class="user-box">已登录 <b>${esc(state.username)}</b>${state.role === "admin" ? '<span class="badge admin">管理员</span>' : ""}</div>
        </aside>
        <main class="main" id="main"></main>
      </div>`;
    app.querySelectorAll(".nav-item").forEach(b => {
      b.onclick = () => { state.tab = b.dataset.tab; state.topic = null; render(); };
    });
    renderTab();
  }

  async function renderTab() {
    const main = document.getElementById("main");
    try {
      if (state.tab === "overview") return renderOverview(main);
      if (state.tab === "devices") return renderDevices(main);
      if (state.tab === "filters") return renderFilters(main);
      if (state.tab === "notifications") return renderNotifications(main);
      if (state.tab === "topics") return renderTopics(main);
      if (state.tab === "account") return renderAccount(main);
      if (state.tab === "admin_users") return renderAdminUsers(main);
      if (state.tab === "admin_topics") return renderAdminTopics(main);
      if (state.tab === "admin_notifs") return renderAdminNotifs(main);
      if (state.tab === "admin_settings") return renderAdminSettings(main);
    } catch (e) {
      main.innerHTML = `<div class="empty">加载失败：${esc(e.message)}</div>`;
    }
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
        <div class="card stat"><div class="label">我的话题数</div><div class="value">${topics.topics.length}</div></div>
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
        <b>快速上手：</b>① 在「账号」里可修改密码；② 用本后台注册的账号登录手机 App；
        ③ 在「应用过滤」里决定同步哪些 App；④ 在「话题」里创建群聊，其他人需经你审批才能加入。
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

  // ---------- Notifications (my own) ----------
  async function renderNotifications(main) {
    main.innerHTML = `<h2 class="page-title">通知</h2>
      <p class="page-sub">从各设备同步过来的通知记录（仅你账号可见）</p>
      <div class="toolbar">
        <div class="grow"></div>
        <button class="btn danger" id="n-clear">清空全部</button>
      </div>
      <div id="nt">加载中…</div>`;
    document.getElementById("n-clear").onclick = async () => {
      if (!confirm("确定清空所有通知记录？此操作不可恢复。")) return;
      try { await api("/api/notifications", { method: "DELETE" }); toast("已清空", "ok"); renderNotifications(main); }
      catch (e) { toast(e.message, "err"); }
    };
    const r = await api("/api/notifications?limit=50");
    const box = document.getElementById("nt");
    if (!r.notifications.length) { box.innerHTML = `<div class="empty">暂无通知记录。</div>`; return; }
    box.innerHTML = `<table><thead><tr><th>应用</th><th>标题</th><th>内容</th><th>来源设备</th><th>时间</th><th></th></tr></thead><tbody>
      ${r.notifications.map(n => `<tr>
        <td>${esc(n.app_name)}</td>
        <td>${esc(n.title)}</td>
        <td>${esc(n.text)}</td>
        <td>${esc(n.device_name || "-")}</td>
        <td>${fmtTime(n.timestamp)}</td>
        <td style="text-align:right"><button class="btn danger sm" data-del="${n.id}">删除</button></td>
      </tr>`).join("")}
    </tbody></table>`;
    box.querySelectorAll("[data-del]").forEach(b => {
      b.onclick = async () => {
        try { await api("/api/notifications/" + b.dataset.del, { method: "DELETE" }); b.closest("tr").remove(); toast("已删除", "ok"); }
        catch (e) { toast(e.message, "err"); }
      };
    });
  }

  // ---------- Topics (group chat) ----------
  async function renderTopics(main) {
    main.innerHTML = `<h2 class="page-title">话题（群聊）</h2>
      <p class="page-sub">话题类似于群聊：你创建的话题，他人需经你审批才能加入；不同账号之间默认相互隔离。</p>
      <div class="toolbar">
        <input id="t-new" type="text" placeholder="新话题名（字母/数字/_/-）" style="max-width:200px" />
        <input id="t-title" type="text" placeholder="标题（可选）" style="max-width:160px" />
        <button class="btn" id="t-add">新建话题</button>
        <div class="grow"></div>
      </div>
      <div class="subtabs">
        <button class="stab active" data-st="mine">我的话题</button>
        <button class="stab" data-st="discover">发现 / 加入</button>
      </div>
      <div id="tlist">加载中…</div>
      <div id="tview" style="margin-top:18px"></div>`;

    document.getElementById("t-add").onclick = async () => {
      const name = document.getElementById("t-new").value.trim().toLowerCase();
      const title = document.getElementById("t-title").value.trim();
      if (!/^[a-z0-9_-]{1,64}$/.test(name)) return toast("话题名不合法（1-64位字母/数字/_/-）", "err");
      try { await api("/api/topics", { method: "POST", body: { name, title } }); toast("话题已创建", "ok"); document.getElementById("t-new").value = ""; document.getElementById("t-title").value = ""; renderTopics(main); }
      catch (e) { toast(e.message, "err"); }
    };

    let subtab = "mine";
    const list = document.getElementById("tlist");
    const view = document.getElementById("tview");
    const stabs = main.querySelectorAll(".stab");
    stabs.forEach(b => b.onclick = () => {
      subtab = b.dataset.st;
      stabs.forEach(x => x.classList.toggle("active", x === b));
      loadList();
    });

    async function loadList() {
      view.innerHTML = ""; state.topic = null;
      if (subtab === "mine") {
        const r = await api("/api/topics").catch(() => ({ topics: [] }));
        if (!r.topics.length) { list.innerHTML = `<div class="empty">你还没有加入任何话题。去「发现 / 加入」找找，或新建一个。</div>`; return; }
        list.innerHTML = `<div class="grid">` + r.topics.map(t => `
          <div class="card topic-card" data-topic="${esc(t.name)}" style="cursor:pointer">
            <div style="display:flex;justify-content:space-between;align-items:center">
              <b>#${esc(t.name)}</b>
              <span class="badge ${t.my_role === "owner" ? "owner" : "member"}">${t.my_role === "owner" ? "创建者" : "成员"}</span>
            </div>
            <div class="label" style="color:var(--muted);font-size:12px;margin-top:4px">${t.message_count} 条消息${t.owner_name && t.my_role !== "owner" ? " · 创建者 " + esc(t.owner_name) : ""}</div>
            ${t.pending_requests > 0 ? `<div class="badge warn" style="margin-top:6px">${t.pending_requests} 个待审批</div>` : ""}
          </div>`).join("") + `</div>`;
        list.querySelectorAll(".topic-card").forEach(c => c.onclick = () => { state.topic = c.dataset.topic; renderTopicView(main, state.topic); });
      } else {
        const r = await api("/api/topics/discover").catch(() => ({ topics: [] }));
        if (!r.topics.length) { list.innerHTML = `<div class="empty">没有可发现的话题。你也可以凭话题名直接申请加入：</div>`; }
        else {
          list.innerHTML = `<div class="grid">` + r.topics.map(t => `
            <div class="card topic-card" data-topic="${esc(t.name)}">
              <div style="display:flex;justify-content:space-between;align-items:center">
                <b>#${esc(t.name)}</b>
                <span class="badge member">${t.member_count} 人</span>
              </div>
              <div class="label" style="color:var(--muted);font-size:12px;margin-top:4px">创建者 ${esc(t.owner_name || "-")} · ${t.message_count} 条消息</div>
              <button class="btn sm" data-join="${esc(t.name)}" style="margin-top:8px">申请加入</button>
            </div>`).join("") + `</div>`;
          list.querySelectorAll("[data-join]").forEach(b => b.onclick = () => joinTopic(b.dataset.join, main));
        }
        list.innerHTML += `
          <div class="card" style="margin-top:14px">
            <label>凭话题名申请加入（需创建者审批）</label>
            <div class="row">
              <input id="t-join-name" type="text" placeholder="话题名" />
              <button class="btn" id="t-join-go" style="flex:0 0 auto">申请加入</button>
            </div>
          </div>`;
        const go = document.getElementById("t-join-go");
        if (go) go.onclick = () => {
          const n = document.getElementById("t-join-name").value.trim().toLowerCase();
          if (!n) return toast("请输入话题名", "err");
          joinTopic(n, main);
        };
      }
    }
    await loadList();
  }

  async function joinTopic(name, main) {
    try {
      await api("/api/topics/" + name + "/join", { method: "POST", body: { message: "" } });
      toast("已发送加入申请，等待创建者审批", "ok");
    } catch (e) { toast(e.message, "err"); }
  }

  async function renderTopicView(main, topic) {
    const box = document.getElementById("tview");
    box.innerHTML = `<div class="card">
      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;flex-wrap:wrap;gap:8px">
        <b>话题 #${esc(topic)}</b>
        <div>
          <button class="btn ghost sm" id="t-req">待审批</button>
          <button class="btn ghost sm" id="t-members">成员</button>
          <button class="btn ghost sm" id="t-back">返回列表</button>
        </div>
      </div>
      <div id="tmsg" style="max-height:300px;overflow:auto">加载中…</div>
      <div class="row" style="margin-top:12px">
        <input id="t-title2" type="text" placeholder="标题（可选）" />
        <input id="t-text" type="text" placeholder="输入消息内容…" />
        <button class="btn" id="t-send" style="flex:0 0 auto">发送</button>
      </div>
      <div class="row" style="margin-top:10px">
        <input id="t-file" type="file" style="flex:1;min-width:0" />
        <button class="btn ghost sm" id="t-upimg">发送图片/文件</button>
      </div>
      <div class="row" style="margin-top:10px">
        <button class="btn ghost sm" id="t-leave">退出话题</button>
        <button class="btn danger sm" id="t-del">删除话题</button>
      </div>
    </div>`;
    document.getElementById("t-back").onclick = () => { state.topic = null; renderTopics(main); };
    document.getElementById("t-send").onclick = async () => {
      const title = document.getElementById("t-title2").value.trim();
      const text = document.getElementById("t-text").value.trim();
      if (!title && !text) return toast("消息不能为空", "err");
      try {
        await api("/api/topics/" + topic + "/publish", { method: "POST", body: { title, text, sender_name: state.username } });
        document.getElementById("t-title2").value = ""; document.getElementById("t-text").value = "";
        renderTopicView(main, topic);
      } catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("t-leave").onclick = async () => {
      if (!confirm("确定退出该话题？")) return;
      try { await api("/api/topics/" + topic + "/leave", { method: "POST" }); toast("已退出", "ok"); state.topic = null; renderTopics(main); }
      catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("t-del").onclick = async () => {
      if (!confirm("确定删除该话题？所有消息将被清除，且不可恢复。")) return;
      try { await api("/api/topics/" + topic, { method: "DELETE" }); toast("已删除", "ok"); state.topic = null; renderTopics(main); }
      catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("t-upimg").onclick = async () => {
      const f = document.getElementById("t-file").files[0];
      if (!f) return toast("请先选择一个文件（图片或任意文件）", "err");
      const kind = f.type.startsWith("image/") ? "image" : "file";
      const fd = new FormData();
      fd.append("file", f);
      const btn = document.getElementById("t-upimg");
      btn.disabled = true;
      try {
        const res = await fetch(`/api/topics/${encodeURIComponent(topic)}/media?kind=${kind}`, {
          method: "POST",
          headers: { Authorization: "Bearer " + state.token },
          body: fd,
        });
        const j = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(j.error || "上传失败");
        await api("/api/topics/" + topic + "/publish", {
          method: "POST",
          body: { media_type: kind, media_url: j.url, media_name: j.name, media_size: j.size, sender_name: state.username },
        });
        document.getElementById("t-file").value = "";
        toast("已发送", "ok");
        renderTopicView(main, topic);
      } catch (e) { toast(e.message, "err"); }
      finally { btn.disabled = false; }
    };
    document.getElementById("t-members").onclick = async () => {
      try {
        const r = await api("/api/topics/" + topic + "/members");
        alert("成员：\n" + r.members.map(m => (m.username || "?") + " (" + m.role + ")").join("\n"));
      } catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("t-req").onclick = async () => {
      try {
        const r = await api("/api/topics/" + topic + "/requests");
        const pending = r.requests.filter(x => x.status === "pending");
        if (!pending.length) return toast("暂无待审批申请", "ok");
        const html = pending.map(x => `<div class="card" style="margin-bottom:8px"><div><b>${esc(x.username)}</b> 申请加入</div>${x.message ? `<div style="color:var(--muted);font-size:12px">${esc(x.message)}</div>` : ""}<div style="margin-top:8px"><button class="btn sm" data-ap="${x.id}">通过</button> <button class="btn danger sm" data-rj="${x.id}">拒绝</button></div></div>`).join("");
        const overlay = document.createElement("div");
        overlay.className = "modal-mask";
        overlay.innerHTML = `<div class="modal"><div class="modal-title">待审批申请</div>${html}<button class="btn ghost" id="m-close" style="margin-top:10px">关闭</button></div>`;
        document.body.appendChild(overlay);
        overlay.querySelectorAll("[data-ap]").forEach(b => b.onclick = async () => {
          try { await api(`/api/topics/${topic}/requests/${b.dataset.ap}/approve`, { method: "POST" }); toast("已通过", "ok"); overlay.remove(); document.getElementById("t-req").click(); }
          catch (e) { toast(e.message, "err"); }
        });
        overlay.querySelectorAll("[data-rj]").forEach(b => b.onclick = async () => {
          try { await api(`/api/topics/${topic}/requests/${b.dataset.rj}/reject`, { method: "POST" }); toast("已拒绝", "ok"); overlay.remove(); document.getElementById("t-req").click(); }
          catch (e) { toast(e.message, "err"); }
        });
        overlay.querySelector("#m-close").onclick = () => overlay.remove();
      } catch (e) { toast(e.message, "err"); }
    };

    const r = await api("/api/topics/" + topic + "/messages?limit=50").catch(() => ({ messages: [] }));
    const msg = document.getElementById("tmsg");
    if (!r.messages.length) { msg.innerHTML = `<div class="empty">还没有消息</div>`; return; }
    msg.innerHTML = r.messages.map(m => `<div class="tmsg-item">
      <div class="tmsg-sender"><b>${esc(m.sender_name || "未知")}</b><span class="tmsg-time">${fmtTime(m.timestamp)}</span></div>
      ${m.title ? `<div style="font-weight:600;margin-top:2px">${esc(m.title)}</div>` : ""}
      <div>${esc(m.text)}</div>
      ${mediaHtml(m)}
    </div>`).join("");
    msg.scrollTop = msg.scrollHeight;
  }

  // ---------- Account ----------
  function renderAccount(main) {
    main.innerHTML = `<h2 class="page-title">账号</h2>
      <p class="page-sub">当前登录：<b>${esc(state.username)}</b>${state.role === "admin" ? "（管理员）" : ""}</p>
      <div class="card" style="max-width:420px;margin-bottom:16px">
        <label>当前密码</label>
        <input id="a-old" type="password" />
        <label>新密码（至少 6 位）</label>
        <input id="a-new" type="password" />
        <button class="btn" id="a-chg" style="margin-top:14px">修改密码</button>
      </div>
      <div class="card danger-zone" style="max-width:420px">
        <label style="color:var(--danger)">退出登录</label>
        <button class="btn danger" id="a-out">退出当前账号</button>
      </div>`;
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
        const overlay = document.createElement("div");
        overlay.className = "modal-mask";
        overlay.innerHTML = `<div class="modal" style="max-width:560px"><div class="modal-title">#${esc(b.dataset.view)} 的消息（${r2.messages.length}）</div><pre class="msgpre">${esc(txt || "（无消息）")}</pre><button class="btn ghost" id="m-close">关闭</button></div>`;
        document.body.appendChild(overlay);
        overlay.querySelector("#m-close").onclick = () => overlay.remove();
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
        // 通过用户名解析 userId
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
  function currentTheme() {
    return document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light";
  }
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
    // 跟随系统模式下，系统外观变化时实时跟随
    if (saved === "system") {
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
        if ((localStorage.getItem("ns_theme") || "system") === "system") applyTheme("system");
      });
    }
  }

  // ---------- boot ----------
  setupThemeToggle();
  if (state.token) {
    api("/api/auth/me")
      .then(r => { state.username = r.user.username; state.role = r.user.role || "user"; localStorage.setItem("ns_username", state.username); localStorage.setItem("ns_role", state.role); })
      .catch(() => { logout(); });
  }
  render();
})();
