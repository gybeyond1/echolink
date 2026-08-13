/* NotifySync Web 管理后台 —— 纯前端，调用同域 REST API */
(function () {
  "use strict";

  const app = document.getElementById("app");
  const toastBox = document.getElementById("toast");

  const state = {
    token: localStorage.getItem("ns_token") || "",
    username: localStorage.getItem("ns_username") || "",
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

  function saveAuth(token, username) {
    state.token = token; state.username = username;
    localStorage.setItem("ns_token", token);
    localStorage.setItem("ns_username", username);
  }
  function logout() {
    state.token = ""; state.username = ""; state.topic = null;
    localStorage.removeItem("ns_token");
    localStorage.removeItem("ns_username");
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
    const passInput = document.getElementById("au-pass");
    passInput.autocomplete = "current-password";

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
        saveAuth(r.token, r.user.username);
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
  const NAV = [
    { id: "overview", ic: "📊", label: "概览" },
    { id: "devices", ic: "📱", label: "设备" },
    { id: "filters", ic: "🧩", label: "应用过滤" },
    { id: "notifications", ic: "🔔", label: "通知" },
    { id: "topics", ic: "💬", label: "话题" },
    { id: "account", ic: "👤", label: "账号" },
  ];

  function render() {
    if (!state.token) { renderAuth(); return; }
    app.innerHTML = `
      <div class="layout">
        <aside class="sidebar">
          <div class="brand"><div class="logo">N</div><h1>NotifySync</h1></div>
          ${NAV.map(n => `<button class="nav-item ${state.tab === n.id ? "active" : ""}" data-tab="${n.id}"><span class="ic">${n.ic}</span>${n.label}</button>`).join("")}
          <div class="spacer"></div>
          <div class="user-box">已登录 <b>${esc(state.username)}</b></div>
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
    } catch (e) {
      main.innerHTML = `<div class="empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  // ---------- Overview ----------
  async function renderOverview(main) {
    main.innerHTML = `<h2 class="page-title">概览</h2><p class="page-sub">服务器状态与快速信息</p><div id="ov">加载中…</div>`;
    const [info, dev, topics] = await Promise.all([
      api("/api/info"),
      api("/api/devices").catch(() => ({ devices: [] })),
      api("/api/topics").catch(() => ({ topics: [] })),
    ]);
    const notif = await api("/api/notifications?limit=50").catch(() => ({ notifications: [] }));
    const total = dev.devices.length + topics.topics.length;
    document.getElementById("ov").innerHTML = `
      <div class="grid" style="margin-bottom:16px">
        <div class="card stat"><div class="label">已注册设备</div><div class="value">${dev.devices.length}</div></div>
        <div class="card stat"><div class="label">话题数</div><div class="value">${topics.topics.length}</div></div>
        <div class="card stat"><div class="label">最近通知(50条内)</div><div class="value">${notif.notifications.length}</div></div>
      </div>
      <div class="card">
        <div class="kv"><span class="k">服务名称</span><span>${esc(info.name)}</span></div>
        <div class="kv"><span class="k">版本</span><span>${esc(info.version)}</span></div>
        <div class="kv"><span class="k">已运行时长</span><span>${fmtUptime(info.uptime)}</span></div>
        <div class="kv"><span class="k">数据目录</span><span class="mono">${esc(info.dataDir)}</span></div>
      </div>
      <div class="hint" style="margin-top:16px">
        <b>快速上手：</b>① 在「账号」里可修改密码；② 在手机 App 用本后台注册的账号登录；
        ③ 在「应用过滤」里决定同步哪些 App；④ 在「话题」里创建多设备共享频道。
        所有数据已持久化到数据目录，重启不丢。
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
        <td style="text-align:right"><button class="btn danger sm" data-del="${d.id}">移除</button></td>
      </tr>`).join("")}
    </tbody></table>`;
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

  // ---------- Notifications ----------
  async function renderNotifications(main) {
    main.innerHTML = `<h2 class="page-title">通知</h2>
      <p class="page-sub">从各设备同步过来的通知记录</p>
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

  // ---------- Topics ----------
  async function renderTopics(main) {
    main.innerHTML = `<h2 class="page-title">话题</h2>
      <p class="page-sub">类似 ntfy 的公共频道，多设备按话题名互通消息</p>
      <div class="toolbar">
        <input id="t-new" type="text" placeholder="新话题名（字母/数字/_/-）" style="max-width:260px" />
        <button class="btn" id="t-add">新建</button>
        <div class="grow"></div>
      </div>
      <div class="grid" id="tlist">加载中…</div>
      <div id="tview" style="margin-top:18px"></div>`;
    document.getElementById("t-add").onclick = async () => {
      const name = document.getElementById("t-new").value.trim().toLowerCase();
      if (!/^[a-z0-9_-]{1,64}$/.test(name)) return toast("话题名不合法（1-64位字母/数字/_/-）", "err");
      try { await api("/api/topics/" + name + "/publish", { method: "POST", body: { title: "频道已创建", text: "" } }); toast("已创建", "ok"); state.topic = name; renderTopics(main); }
      catch (e) { toast(e.message, "err"); }
    };
    const r = await api("/api/topics");
    const list = document.getElementById("tlist");
    if (!r.topics.length) { list.innerHTML = `<div class="empty">还没有话题。在上方新建一个，或等手机端发布后自动出现。</div>`; }
    else {
      list.innerHTML = r.topics.map(t => `<div class="card" style="cursor:pointer" data-topic="${esc(t.topic)}">
        <div style="font-weight:600">#${esc(t.topic)}</div>
        <div class="label" style="color:var(--muted);font-size:12px;margin-top:4px">${t.message_count} 条消息</div>
      </div>`).join("");
      list.querySelectorAll("[data-topic]").forEach(c => c.onclick = () => { state.topic = c.dataset.topic; renderTopics(main); });
    }
    if (state.topic) await renderTopicView(main, state.topic);
  }

  async function renderTopicView(main, topic) {
    const box = document.getElementById("tview");
    box.innerHTML = `<div class="card">
      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:10px">
        <b>话题 #${esc(topic)}</b>
        <button class="btn ghost sm" id="t-back">返回列表</button>
      </div>
      <div id="tmsg" style="max-height:300px;overflow:auto">加载中…</div>
      <div class="row" style="margin-top:12px">
        <input id="t-title" type="text" placeholder="标题（可选）" />
        <input id="t-text" type="text" placeholder="输入消息内容…" />
        <button class="btn" id="t-send" style="flex:0 0 auto">发送</button>
      </div>
    </div>`;
    document.getElementById("t-back").onclick = () => { state.topic = null; renderTopics(main); };
    const send = async () => {
      const title = document.getElementById("t-title").value.trim();
      const text = document.getElementById("t-text").value.trim();
      if (!title && !text) return toast("消息不能为空", "err");
      try {
        await api("/api/topics/" + topic + "/publish", { method: "POST", body: { title, text, sender_name: state.username } });
        document.getElementById("t-title").value = ""; document.getElementById("t-text").value = "";
        renderTopicView(main, topic);
      } catch (e) { toast(e.message, "err"); }
    };
    document.getElementById("t-send").onclick = send;
    const r = await api("/api/topics/" + topic + "/messages?limit=50");
    const msg = document.getElementById("tmsg");
    if (!r.messages.length) { msg.innerHTML = `<div class="empty">还没有消息</div>`; return; }
    msg.innerHTML = r.messages.map(m => `<div style="padding:8px 0;border-bottom:1px solid var(--border)">
      <div style="font-size:13px"><b>${esc(m.sender_name || "未知")}</b> <span style="color:var(--muted);font-size:11px">${fmtTime(m.timestamp)}</span></div>
      ${m.title ? `<div style="font-weight:600">${esc(m.title)}</div>` : ""}
      <div>${esc(m.text)}</div>
    </div>`).join("");
    msg.scrollTop = msg.scrollHeight;
  }

  // ---------- Account ----------
  function renderAccount(main) {
    main.innerHTML = `<h2 class="page-title">账号</h2>
      <p class="page-sub">当前登录：<b>${esc(state.username)}</b></p>
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

  // ---------- boot ----------
  if (state.token) {
    // 校验 token 是否仍然有效
    api("/api/auth/me").catch(() => { logout(); });
  }
  render();
})();
