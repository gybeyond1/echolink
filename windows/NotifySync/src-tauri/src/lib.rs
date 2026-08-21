// EchoLink Desktop —— Tauri 2 + Slint 原生客户端入口
// 界面全部为 Slint 原生控件（无 WebView 网页），Rust 直连 REST + WebSocket。

mod api;
mod win_notif;

use api::{Client, FilterEntry, InstalledApp, Message, Topic};
use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use tauri::{
    menu::{MenuItem, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager,
};
use tauri_plugin_notification::NotificationExt;

slint::include_modules!();

#[derive(Default)]
struct AppState {
    client: Client,
    current_topic: String,
}

type Shared = Arc<Mutex<AppState>>;

fn config_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path().app_data_dir().ok().map(|d| d.join("config.json"))
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Default)]
struct Persist {
    server_url: String,
    token: String,
    username: String,
    user_id: i64,
    role: String,
    display_name: String,
    avatar: String,
    notification_sync_enabled: bool,
    blocked_apps: Vec<String>,
}

fn load_persist(app: &AppHandle) -> Persist {
    if let Some(p) = config_path(app) {
        if let Ok(s) = std::fs::read_to_string(&p) {
            if let Ok(v) = serde_json::from_str::<Persist>(&s) {
                return v;
            }
        }
    }
    if let Ok(v) = std::env::var("NOTIFYSYNC_SERVER") {
        if !v.trim().is_empty() {
            return Persist {
                server_url: v.trim().trim_end_matches('/').to_string(),
                ..Default::default()
            };
        }
    }
    Persist::default()
}

fn save_persist(app: &AppHandle, p: &Persist) {
    if let Some(dir) = app.path().app_data_dir().ok() {
        let _ = std::fs::create_dir_all(&dir);
        if let Some(path) = config_path(app) {
            if let Ok(s) = serde_json::to_string_pretty(p) {
                let _ = std::fs::write(path, s);
            }
        }
    }
}

fn fmt_time(ts: Option<u64>) -> String {
    match ts {
        Some(t) => {
            let secs = if t > 1e12 { t / 1000 } else { t };
            let d = chrono_like(secs);
            d
        }
        None => String::new(),
    }
}

fn chrono_like(secs: u64) -> String {
    // 简单格式化：用 std 不依赖 chrono
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let diff = now.saturating_sub(secs);
    if diff < 60 {
        "刚刚".to_string()
    } else if diff < 3600 {
        format!("{} 分钟前", diff / 60)
    } else if diff < 86400 {
        format!("{} 小时前", diff / 3600)
    } else {
        format!("{} 天前", diff / 86400)
    }
}

fn topic_to_item(t: &Topic) -> TopicItem {
    TopicItem {
        name: t.name.clone(),
        display: t.display_name.clone(),
        kind: t.kind.clone(),
        avatar: t.avatar.clone().unwrap_or_default(),
        preview: t.last_message.clone().unwrap_or_else(|| {
            if t.kind == "devices" {
                "我的设备同步会话".to_string()
            } else {
                "暂无消息".to_string()
            }
        }),
        count: t.message_count as i32,
        unread: t.unread_count as i32,
        time: fmt_time(t.last_message_at),
    }
}

fn msg_to_item(m: &Message, my_id: i64) -> MsgItem {
    let mine = (my_id > 0 && m.user_id == my_id)
        || (my_id == 0 && m.sender_name == "");
    let media = match m.media_type.as_deref() {
        Some("image") => "[图片]".to_string(),
        Some("voice") => "[语音]".to_string(),
        Some("file") => format!("[文件] {}", m.media_name.clone().unwrap_or_default()),
        _ => String::new(),
    };
    MsgItem {
        id: m.id as i32,
        text: m.text.clone().unwrap_or_else(|| m.title.clone().unwrap_or_default()),
        mine,
        sender: m.sender_display_name.clone().unwrap_or(m.sender_name.clone()),
        time: fmt_time(Some(m.timestamp)),
        media,
    }
}

async fn spawn_ws(shared: Shared, _app: AppHandle, win: MainWindow) {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    // 启动 WS 接收循环
    tokio::spawn(async move {
        loop {
            let (server, token) = {
                let s = shared.lock().unwrap();
                (s.client.server.clone(), s.client.token.clone())
            };
            if server.is_empty() || token.is_empty() {
                tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                continue;
            }
            let url = if server.starts_with("https") {
                format!("wss://{}/ws?token={}", server.trim_start_matches("https://"), token)
            } else {
                format!("ws://{}/ws?token={}", server.trim_start_matches("http://"), token)
            };
            let (ws_stream, _) = match tokio_tungstenite::connect_async(&url).await {
                Ok(v) => v,
                Err(_) => {
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                    continue;
                }
            };
            let (mut write, mut read) = ws_stream.split();
            // 订阅现有话题
            {
                let s = shared.lock().unwrap();
                for t in &s.client.topics_cache {
                    let _ = write.send(tokio_tungstenite::tungstenite::Message::Text(
                        format!("{{\"type\":\"subscribe\",\"topic\":\"{}\"}}", t.name),
                    )).await;
                }
            }
            // 接收
            use futures_util::{StreamExt, SinkExt};
            while let Some(msg) = read.next().await {
                if let Ok(tokio_tungstenite::tungstenite::Message::Text(t)) = msg {
                    let _ = tx.send(t);
                }
            }
            // 断线重连
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
        }
    });

    // 处理收到的 WS 消息（在主 tokio 线程，通过 invoke_from_event_loop 更新 UI）
    tokio::spawn(async move {
        while let Some(text) = rx.recv().await {
            let parsed: serde_json::Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(_) => continue,
            };
            let mtype = parsed["type"].as_str().unwrap_or("");
            if mtype == "topic_message" {
                let data = &parsed["data"];
                let topic = parsed["topic"].as_str().unwrap_or("").to_string();
                let msg: Message = match serde_json::from_value(data.clone()) {
                    Ok(m) => m,
                    Err(_) => continue,
                };
                let my_id = shared.lock().unwrap().client.user_id;
                let item = msg_to_item(&msg, my_id);
                let cur = shared.lock().unwrap().current_topic.clone();
                if cur == topic {
                    slint::invoke_from_event_loop(move || {
                        let current: Vec<MsgItem> = win.get_messages().iter().collect();
                        let mut v = slint::VecModel::from(current);
                        v.push(item);
                        win.set_messages(v.into());
                    })
                    .ok();
                }
                // 刷新会话列表
                refresh_topics(shared.clone(), win.clone()).await;
            } else if mtype == "message_deleted" {
                let topic = parsed["topic"].as_str().unwrap_or("").to_string();
                let mid = parsed["message_id"].as_i64().unwrap_or(0);
                let cur = shared.lock().unwrap().current_topic.clone();
                if cur == topic {
                    slint::invoke_from_event_loop(move || {
                        let current: Vec<MsgItem> = win.get_messages().iter().collect();
                        let filtered: Vec<MsgItem> =
                            current.into_iter().filter(|m| m.id as i64 != mid).collect();
                        win.set_messages(slint::VecModel::from(filtered).into());
                    })
                    .ok();
                }
            }
        }
    });
}

async fn refresh_topics(shared: Shared, win: MainWindow) {
    let topics = {
        let mut s = shared.lock().unwrap();
        if !s.client.authed() {
            return;
        }
        match s.client.topics().await {
            Ok(t) => t,
            Err(_) => return,
        }
    };
    {
        let mut s = shared.lock().unwrap();
        s.client.topics_cache = topics.clone();
    }
    let items: Vec<TopicItem> = topics.iter().map(topic_to_item).collect();
    slint::invoke_from_event_loop(move || {
        win.set_topics(slint::VecModel::from(items).into());
    })
    .ok();
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(w) = app.get_webview_window("host") {
                let _ = w.show();
            }
        }))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            let handle = app.handle().clone();
            let persist = load_persist(&handle);

            // 隐藏宿主 WebView 窗口（仅作进程宿主，不渲染任何网页）
            if let Some(w) = app.get_webview_window("host") {
                let _ = w.hide();
            }

            let shared = Arc::new(Mutex::new(AppState {
                client: Client::new(persist.server_url.clone()),
                current_topic: String::new(),
            }));
            {
                let mut s = shared.lock().unwrap();
                s.client.token = persist.token.clone();
                s.client.username = persist.username.clone();
                s.client.user_id = persist.user_id;
                s.client.role = persist.role.clone();
                s.client.display_name = persist.display_name.clone();
                s.client.avatar = persist.avatar.clone();
            }

            // 构建 Slint 主窗口
            let main_window = MainWindow::new().expect("Slint 窗口创建失败");

            // 初始状态推送
            main_window.set_server_url(persist.server_url.clone().into());
            main_window.set_username(persist.username.clone().into());
            main_window.set_display_name(persist.display_name.clone().into());
            main_window.set_logged_in(!persist.token.is_empty());
            main_window.set_sync_enabled(persist.notification_sync_enabled);

            // 回调绑定
            bind_callbacks(shared.clone(), handle.clone(), main_window.clone());

            // 若已有登录态，恢复会话
            if !persist.token.is_empty() && !persist.server_url.is_empty() {
                let sh = shared.clone();
                let mw = main_window.clone();
                tauri::async_runtime::spawn(async move {
                    {
                        let mut s = sh.lock().unwrap();
                        let _ = s.client.me().await;
                    }
                    main_window_set_profile(&mw, &sh);
                    refresh_topics(sh.clone(), mw.clone()).await;
                    // 启动 WS
                    spawn_ws(sh.clone(), handle.clone(), mw.clone());
                });
                // 通知同步
                if persist.notification_sync_enabled {
                    win_notif::start(
                        persist.server_url.clone(),
                        persist.token.clone(),
                        persist.blocked_apps.iter().cloned().collect(),
                    );
                }
            }

            main_window.show().expect("Slint 窗口显示失败");

            // 托盘
            build_tray(app.handle(), shared.clone(), main_window.clone());

            app.manage(shared);
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running EchoLink desktop");
}

fn main_window_set_profile(mw: &MainWindow, sh: &Shared) {
    let s = sh.lock().unwrap();
    mw.set_username(s.client.username.clone().into());
    mw.set_display_name(s.client.display_name.clone().into());
}

fn bind_callbacks(shared: Shared, app: AppHandle, win: MainWindow) {
    // 登录
    {
        let sh = shared.clone();
        let w = win.clone();
        let a = app.clone();
        win.on_do_login(move |server, user, pass| {
            let sh = sh.clone();
            let w = w.clone();
            let a = a.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let mut s = sh.lock().unwrap();
                    s.client = Client::new(server.to_string());
                    let r = s.client.login(&user, &pass).await;
                    drop(s);
                    r
                };
                match r {
                    Ok(_) => {
                        persist_and_apply(&a, &sh, &w, true, "").await;
                    }
                    Err(e) => set_status(&w, &e),
                }
            });
        });
    }
    // 注册
    {
        let sh = shared.clone();
        let w = win.clone();
        let a = app.clone();
        win.on_do_register(move |server, user, pass| {
            let sh = sh.clone();
            let w = w.clone();
            let a = a.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let mut s = sh.lock().unwrap();
                    s.client = Client::new(server.to_string());
                    let r = s.client.register(&user, &pass).await;
                    drop(s);
                    r
                };
                match r {
                    Ok(_) => {
                        persist_and_apply(&a, &sh, &w, true, "").await;
                    }
                    Err(e) => set_status(&w, &e),
                }
            });
        });
    }
    // 加载话题
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_load_topics(move || {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                refresh_topics(sh.clone(), w.clone()).await;
            });
        });
    }
    // 打开话题
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_open_topic(move |name| {
            let name = name.to_string();
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let (topic, display, sub) = {
                    let s = sh.lock().unwrap();
                    let t = s.client.topics_cache_names().iter().find(|t| t.name == name).cloned();
                    let t = match t {
                        Some(t) => t,
                        None => return,
                    };
                    (
                        t.name.clone(),
                        t.display_name.clone(),
                        if t.kind == "devices" {
                            "设备同步会话 · 本账号互通".to_string()
                        } else if t.kind == "dm" {
                            "好友私聊".to_string()
                        } else {
                            format!("成员 · #{}", t.name)
                        },
                    )
                };
                {
                    let mut s = sh.lock().unwrap();
                    s.current_topic = topic.clone();
                }
                w.set_current_topic(topic.clone().into());
                w.set_chat_title(display.clone().into());
                w.set_chat_sub(sub.into());
                w.set_chat_open(true);
                // 加载消息
                let msgs = {
                    let s = sh.lock().unwrap();
                    match s.client.messages(&topic, 100).await {
                        Ok(m) => m,
                        Err(_) => Vec::new(),
                    }
                };
                let my_id = sh.lock().unwrap().client.user_id;
                let items: Vec<MsgItem> = msgs.iter().map(|m| msg_to_item(m, my_id)).collect();
                w.set_messages(slint::VecModel::from(items).into());
            });
        });
    }
    // 发送消息
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_send_message(move |text| {
            let text = text.trim().to_string();
            if text.is_empty() {
                return;
            }
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let topic = sh.lock().unwrap().current_topic.clone();
                if topic.is_empty() {
                    return;
                }
                let r = {
                    let s = sh.lock().unwrap();
                    s.client.publish(&topic, &text).await
                };
                if let Ok(m) = r {
                    let my_id = sh.lock().unwrap().client.user_id;
                    let item = msg_to_item(&m, my_id);
                    slint::invoke_from_event_loop(move || {
                        let current: Vec<MsgItem> = w.get_messages().iter().collect();
                        let mut v = slint::VecModel::from(current);
                        v.push(item);
                        w.set_messages(v.into());
                    })
                    .ok();
                    w.set_input_text("".into());
                }
            });
        });
    }
    // 删除消息（来自聊天气泡）
    {
        let sh = shared.clone();
        win.on_delete_msg_topic(move |id, topic| {
            let sh = sh.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let s = sh.lock().unwrap();
                    s.client.delete_message(&topic, id as i64).await
                };
                if r.is_ok() {
                    let mut s = sh.lock().unwrap();
                    s.client.topics_cache.clear();
                }
            });
        });
    }
    // 切换 tab
    {
        let w = win.clone();
        win.on_switch_tab(move |tab| {
            w.set_current_tab(tab.clone());
            match tab.as_str() {
                "friends" => {
                    let sh = sh.clone();
                    let w = w.clone();
                    tauri::async_runtime::spawn(async move { load_friends(sh, w).await; });
                }
                "notifications" => {
                    let sh = sh.clone();
                    let w = w.clone();
                    tauri::async_runtime::spawn(async move { load_notifs(sh, w).await; });
                }
                "devices" => {
                    let sh = sh.clone();
                    let w = w.clone();
                    tauri::async_runtime::spawn(async move { load_devices(sh, w).await; });
                }
                "settings" => {
                    let sh = sh.clone();
                    let w = w.clone();
                    tauri::async_runtime::spawn(async move { load_filters(sh, w).await; });
                }
                "messages" => {
                    let sh = sh.clone();
                    let w = w.clone();
                    tauri::async_runtime::spawn(async move { refresh_topics(sh, w).await; });
                }
                _ => {}
            }
        });
    }
    // 好友私聊
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_friend_chat(move |username| {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let topic = {
                    let s = sh.lock().unwrap();
                    match s.client.friend_chat(&username).await {
                        Ok(t) => t,
                        Err(e) => {
                            set_status(&w, &e);
                            return;
                        }
                    }
                };
                if !topic.is_empty() {
                    w.set_current_tab("messages".into());
                    w.invoke_open_topic(topic.into());
                }
            });
        });
    }
    // 加好友
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_add_friend(move |username| {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let s = sh.lock().unwrap();
                    s.client
                        .add_friend_req(&username)
                        .await
                };
                match r {
                    Ok(_) => set_status(&w, "好友申请已发送"),
                    Err(e) => set_status(&w, &e),
                }
            });
        });
    }
    // 通知同步开关
    {
        let sh = shared.clone();
        let a = app.clone();
        win.on_toggle_sync(move |on| {
            let sh = sh.clone();
            let a = a.clone();
            let (server, token) = {
                let s = sh.lock().unwrap();
                (s.client.server.clone(), s.client.token.clone())
            };
            if on {
                win_notif::start(server, token, HashSet::new());
            } else {
                win_notif::stop();
            }
            // 持久化
            update_persist_sync(&a, &sh, on);
        });
    }
    // 刷新本机应用列表
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_refresh_apps(move || {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                refresh_apps(sh, w).await;
            });
        });
    }
    // 保存过滤
    {
        let sh = shared.clone();
        win.on_save_filter(move |exe, checked| {
            let sh = sh.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let s = sh.lock().unwrap();
                    if checked {
                        let name = exe.trim_end_matches(".exe").to_string();
                        s.client.add_filter(&exe, &name).await
                    } else {
                        s.client.del_filter(&exe).await
                    }
                };
                if r.is_ok() {
                    let blocked: HashSet<String> = {
                        let mut s = sh.lock().unwrap();
                        if checked {
                            let name = exe.trim_end_matches(".exe").to_string();
                            if !s.client.filters_cache.iter().any(|f| f.package_name.as_str() == exe.as_str()) {
                                s.client.filters_cache.push(FilterEntry {
                                    package_name: exe.to_string(),
                                    app_name: name,
                                    enabled: true,
                                });
                            }
                        } else {
                            s.client.filters_cache.retain(|f| f.package_name.as_str() != exe.as_str());
                        }
                        s.client.filters_cache.iter().filter(|f| f.enabled).map(|f| f.package_name.clone()).collect()
                    };
                    win_notif::update_blocked(blocked);
                }
            });
        });
    }
    // 保存昵称
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_save_nickname(move |name| {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let mut s = sh.lock().unwrap();
                    s.client.set_nickname(&name).await
                };
                match r {
                    Ok(_) => {
                        w.set_display_name(name);
                        set_status(&w, "昵称已保存");
                    }
                    Err(e) => set_status(&w, &e),
                }
            });
        });
    }
    // 改密码
    {
        let sh = shared.clone();
        let w = win.clone();
        win.on_change_pass(move |old, new| {
            let sh = sh.clone();
            let w = w.clone();
            tauri::async_runtime::spawn(async move {
                let r = {
                    let s = sh.lock().unwrap();
                    s.client.change_password(&old, &new).await
                };
                match r {
                    Ok(_) => set_status(&w, "密码已修改"),
                    Err(e) => set_status(&w, &e),
                }
            });
        });
    }
    // 登出
    {
        let sh = shared.clone();
        let a = app.clone();
        let w = win.clone();
        win.on_do_logout(move || {
            win_notif::stop();
            {
                let mut s = sh.lock().unwrap();
                s.client = Client::new(s.client.server.clone());
                s.current_topic = String::new();
            }
            update_persist_logout(&a);
            w.set_logged_in(false);
            w.set_messages(slint::VecModel::new().into());
            w.set_topics(slint::VecModel::new().into());
        });
    }
}

fn set_status(w: &MainWindow, msg: &str) {
    w.set_status(msg.into());
}

async fn load_friends(sh: Shared, w: MainWindow) {
    let fs = {
        let s = sh.lock().unwrap();
        match s.client.friends().await {
            Ok(f) => f,
            Err(_) => return,
        }
    };
    let items: Vec<FriendItem> = fs
        .iter()
        .map(|f| FriendItem {
            username: f.username.clone(),
            display: f.display_name.clone().unwrap_or(f.username.clone()),
        })
        .collect();
    slint::invoke_from_event_loop(move || {
        w.set_friends(slint::VecModel::from(items).into());
    })
    .ok();
}

async fn load_notifs(sh: Shared, w: MainWindow) {
    let ns = {
        let s = sh.lock().unwrap();
        match s.client.notifications(100).await {
            Ok(n) => n,
            Err(_) => return,
        }
    };
    let items: Vec<NotifItem> = ns
        .iter()
        .map(|n| NotifItem {
            id: n.id as i32,
            app: n.app_name.clone().unwrap_or_default(),
            title: n.title.clone().unwrap_or_default(),
            text: n.text.clone().unwrap_or_default(),
            time: fmt_time(n.timestamp),
        })
        .collect();
    slint::invoke_from_event_loop(move || {
        w.set_notifs(slint::VecModel::from(items).into());
    })
    .ok();
}

async fn load_devices(sh: Shared, w: MainWindow) {
    let ds = {
        let s = sh.lock().unwrap();
        match s.client.devices().await {
            Ok(d) => d,
            Err(_) => return,
        }
    };
    let items: Vec<DeviceItem> = ds
        .iter()
        .map(|d| DeviceItem {
            name: d.device_name.clone(),
            platform: d.platform.clone().unwrap_or_default(),
            last: fmt_time(d.last_seen),
        })
        .collect();
    slint::invoke_from_event_loop(move || {
        w.set_devices(slint::VecModel::from(items).into());
    })
    .ok();
}

async fn load_filters(sh: Shared, w: MainWindow) {
    let (filters, apps) = {
        let s = sh.lock().unwrap();
        let f = s.client.filters().await.ok().unwrap_or_default();
        let a = win_notif::list_installed_apps();
        (f, a)
    };
    // 合并：apps 列表 + 已勾选状态
    let checked_set: HashSet<String> =
        filters.iter().filter(|f| f.enabled).map(|f| f.package_name.clone()).collect();
    let app_items: Vec<AppItem> = apps
        .into_iter()
        .map(|a: InstalledApp| AppItem {
            name: a.name.clone(),
            exe: a.exe.clone(),
            checked: checked_set.contains(&a.exe),
        })
        .collect();
    let filter_items: Vec<FilterItem> = filters
        .iter()
        .map(|f| FilterItem {
            pkg: f.package_name.clone(),
            name: f.app_name.clone(),
            checked: f.enabled,
        })
        .collect();
    {
        let mut s = sh.lock().unwrap();
        s.client.filters_cache = filters;
    }
    slint::invoke_from_event_loop(move || {
        w.set_apps(slint::VecModel::from(app_items).into());
        w.set_filters(slint::VecModel::from(filter_items).into());
    })
    .ok();
}

async fn refresh_apps(sh: Shared, w: MainWindow) {
    // 重新拉取并保留当前勾选
    load_filters(sh, w).await;
}

async fn persist_and_apply(app: &AppHandle, sh: &Shared, w: &MainWindow, ok: bool, err: &str) {
    if ok {
        {
            let s = sh.lock().unwrap();
            let p = Persist {
                server_url: s.client.server.clone(),
                token: s.client.token.clone(),
                username: s.client.username.clone(),
                user_id: s.client.user_id,
                role: s.client.role.clone(),
                display_name: s.client.display_name.clone(),
                avatar: s.client.avatar.clone(),
                notification_sync_enabled: false,
                blocked_apps: Vec::new(),
            };
            save_persist(app, &p);
        }
        w.set_logged_in(true);
        w.set_status("".into());
        main_window_set_profile(w, sh);
        refresh_topics(sh.clone(), w.clone()).await;
        // 启动 WS
        spawn_ws(sh.clone(), app.clone(), w.clone());
    } else {
        w.set_status(err.into());
    }
}

fn update_persist_sync(app: &AppHandle, sh: &Shared, on: bool) {
    let p = {
        let s = sh.lock().unwrap();
        Persist {
            server_url: s.client.server.clone(),
            token: s.client.token.clone(),
            username: s.client.username.clone(),
            user_id: s.client.user_id,
            role: s.client.role.clone(),
            display_name: s.client.display_name.clone(),
            avatar: s.client.avatar.clone(),
            notification_sync_enabled: on,
            blocked_apps: s.client.filters_cache.iter().filter(|f| f.enabled).map(|f| f.package_name.clone()).collect(),
        }
    };
    save_persist(app, &p);
}

fn update_persist_logout(app: &AppHandle) {
    // 仅清空 token 等登录态，保留 server_url
    let dir = match app.path().app_data_dir().ok() {
        Some(d) => d,
        None => return,
    };
    let path = dir.join("config.json");
    if let Ok(s) = std::fs::read_to_string(&path) {
        if let Ok(mut p) = serde_json::from_str::<Persist>(&s) {
            p.token.clear();
            p.username.clear();
            p.user_id = 0;
            p.role.clear();
            p.display_name.clear();
            p.avatar.clear();
            p.notification_sync_enabled = false;
            let _ = std::fs::create_dir_all(&dir);
            if let Ok(j) = serde_json::to_string_pretty(&p) {
                let _ = std::fs::write(path, j);
            }
        }
    }
}

fn build_tray(app: &AppHandle, _sh: Shared, _win: MainWindow) {
    let show = MenuItem::with_id(app, "show", "打开", true, None::<&str>).unwrap();
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>).unwrap();
    let sep = PredefinedMenuItem::separator(app).unwrap();
    let menu = tauri::menu::Menu::with_items(app, &[&show, &sep, &quit]).unwrap();
    TrayIconBuilder::with_id("main-tray")
        .icon(app.default_window_icon().cloned().expect("icon"))
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("host") {
                    let _ = w.show();
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, .. } = event {
                if let Some(w) = tray.app_handle().get_webview_window("host") {
                    let _ = w.show();
                }
            }
        })
        .build(app)
        .ok();
}
