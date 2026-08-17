// EchoLink Desktop —— Tauri 2 库入口
// 功能：托盘菜单 / 单实例锁 / 关闭窗口隐藏到托盘 / 系统通知 / 未读计数提示 /
//       开机自启 / 服务器地址配置 / Windows 系统通知监听同步

use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager,
};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;

// ============== 配置文件 ==============

#[derive(Serialize, Deserialize, Clone)]
struct AppConfig {
    server_url: String,
    notification_sync_enabled: bool,
    blocked_apps: Vec<String>,
}

impl Default for AppConfig {
    fn default() -> Self {
        AppConfig {
            server_url: String::new(),
            notification_sync_enabled: false,
            blocked_apps: Vec::new(),
        }
    }
}

fn config_file_path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_data_dir().ok().map(|d| d.join("config.json"))
}

fn read_config(app: &AppHandle) -> AppConfig {
    if let Some(path) = config_file_path(app) {
        if let Ok(content) = fs::read_to_string(&path) {
            if let Ok(config) = serde_json::from_str::<AppConfig>(&content) {
                return config;
            }
        }
    }
    // 回退：环境变量
    if let Ok(v) = std::env::var("NOTIFYSYNC_SERVER") {
        if !v.trim().is_empty() {
            return AppConfig {
                server_url: v.trim().trim_end_matches('/').to_string(),
                ..Default::default()
            };
        }
    }
    AppConfig::default()
}

fn write_config(app: &AppHandle, config: &AppConfig) -> Result<(), String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = config_file_path(app).ok_or("Cannot determine config path")?;
    let json = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    fs::write(&path, json).map_err(|e| e.to_string())
}

// ============== Tauri 命令 ==============

#[derive(Serialize)]
struct AppInfo {
    version: &'static str,
    target: &'static str,
    server_url: String,
    notification_sync_enabled: bool,
}

#[tauri::command]
fn app_info(app: AppHandle) -> AppInfo {
    let config = read_config(&app);
    AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        target: std::env::consts::OS,
        server_url: config.server_url.clone(),
        notification_sync_enabled: config.notification_sync_enabled,
    }
}

#[tauri::command]
fn save_server_url(app: AppHandle, url: String) -> Result<(), String> {
    let mut config = read_config(&app);
    config.server_url = url.trim().trim_end_matches('/').to_string();
    write_config(&app, &config)
}

#[tauri::command]
fn get_config(app: AppHandle) -> AppConfig {
    read_config(&app)
}

#[tauri::command]
fn set_notification_sync(app: AppHandle, enabled: bool) -> Result<(), String> {
    let mut config = read_config(&app);
    config.notification_sync_enabled = enabled;
    write_config(&app, &config)?;

    // 更新托盘菜单勾选状态
    if let Some(tray) = app.tray_by_id("main-tray") {
        if let Some(item) = tray.menu().and_then(|m| m.get("sync_notifications")) {
            if let Some(check_item) = item.as_checkMenuItem() {
                let _ = check_item.set_checked(enabled);
            }
        }
    }

    // 启动/停止通知监听
    if enabled {
        start_notification_listener(&app);
    } else {
        stop_notification_listener();
    }
    Ok(())
}

#[tauri::command]
fn set_blocked_apps(app: AppHandle, apps: Vec<String>) -> Result<(), String> {
    let mut config = read_config(&app);
    config.blocked_apps = apps;
    write_config(&app, &config)
}

/// 触发 Windows 原生通知
#[tauri::command]
async fn show_notification(app: AppHandle, title: String, body: String) -> Result<(), String> {
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|e| e.to_string())
}

/// 更新托盘悬浮提示（未读计数）
#[tauri::command]
async fn set_unread_count(app: AppHandle, count: u32) -> Result<(), String> {
    let tip = if count > 0 {
        format!("EchoLink · {} 条未读", count)
    } else {
        "EchoLink".to_string()
    };
    if let Some(tray) = app.tray_by_id("main-tray") {
        tray.set_tooltip(Some(&tip)).map_err(|e| e.to_string())?;
    }
    if let Some(win) = app.get_webview_window("main") {
        let title = if count > 0 {
            format!("EchoLink ({} 未读)", count)
        } else {
            "EchoLink".to_string()
        };
        win.set_title(&title).ok();
    }
    Ok(())
}

// ============== 托盘 ==============

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let config = read_config(app);

    let show = MenuItem::with_id(app, "show", "打开主页", true, None::<&str>)?;
    let open_browser = MenuItem::with_id(
        app,
        "open_browser",
        "在浏览器中打开",
        true,
        None::<&str>,
    )?;
    let toggle_autostart = MenuItem::with_id(
        app,
        "toggle_autostart",
        "开机自启",
        true,
        None::<&str>,
    )?;
    let sync_notifications = CheckMenuItem::with_id(
        app,
        "sync_notifications",
        "通知同步",
        true,
        config.notification_sync_enabled,
        None::<&str>,
    )?;
    let switch_server = MenuItem::with_id(
        app,
        "switch_server",
        "切换服务器",
        true,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let sep1 = PredefinedMenuItem::separator(app)?;
    let sep2 = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[
            &show,
            &open_browser,
            &sep1,
            &sync_notifications,
            &switch_server,
            &sep2,
            &toggle_autostart,
            &quit,
        ],
    )?;

    TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "open_browser" => {
                let config = read_config(app);
                if !config.server_url.is_empty() {
                    let _ = app.opener().open_url(config.server_url, None::<&str>);
                }
            }
            "sync_notifications" => {
                // 从配置读取当前状态，取反后设置
                let config = read_config(app);
                let new_state = !config.notification_sync_enabled;
                let _ = set_notification_sync(app.clone(), new_state);
            }
            "switch_server" => {
                // 清除保存的服务器地址，重新显示输入页
                let mut config = read_config(app);
                config.server_url = String::new();
                let _ = write_config(app, &config);
                stop_notification_listener();
                // 导航回本地 index.html
                if let Some(win) = app.get_webview_window("main") {
                    #[cfg(target_os = "windows")]
                    let _ = win.eval("window.location.replace('http://tauri.localhost/index.html')");
                    #[cfg(not(target_os = "windows"))]
                    let _ = win.eval("window.location.replace('tauri://localhost/index.html')");
                }
            }
            "toggle_autostart" => {
                use tauri_plugin_autostart::ManagerExt;
                let mgr = app.autolaunch();
                let enabled = mgr.is_enabled().unwrap_or(false);
                if enabled {
                    let _ = mgr.disable();
                } else {
                    let _ = mgr.enable();
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, .. } = event {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn show_main(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.show();
        let _ = win.unminimize();
        let _ = win.set_focus();
    }
}

// ============== Windows 通知监听 ==============

#[cfg(target_os = "windows")]
mod win_notif {
    use super::*;
    use std::sync::Mutex;
    use windows_sys::Win32::Foundation::{CloseHandle, HWND, HANDLE};
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows_sys::Win32::UI::Accessibility::{
        SetWinEventHook, UnhookWinEvent, WINEVENT_OUTOFCONTEXT, WINEVENT_SKIPOWNPROCESS,
        HWINEVENTHOOK,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        DispatchMessageW, GetClassNameW, GetMessageW, GetWindowThreadProcessId, GetWindowTextLengthW,
        GetWindowTextW, MSG, EVENT_OBJECT_SHOW,
    };

    // 已见通知窗口去重（窗口句柄 + 时间戳）
    struct SeenSet {
        entries: HashSet<(isize, u32)>,
        last_cleanup: u32,
    }
    static SEEN: Mutex<Option<SeenSet>> = Mutex::new(None);
    static HOOK: Mutex<Option<HWINEVENTHOOK>> = Mutex::new(None);
    static SHOULD_RUN: Mutex<bool> = Mutex::new(false);

    // 获取窗口类名
    fn get_class_name(hwnd: HWND) -> String {
        unsafe {
            let mut buf = [0u16; 512];
            let len = GetClassNameW(hwnd, buf.as_mut_ptr(), buf.len() as i32);
            if len > 0 {
                String::from_utf16_lossy(&buf[..len as usize])
            } else {
                String::new()
            }
        }
    }

    // 获取窗口标题文本
    fn get_window_text(hwnd: HWND) -> String {
        unsafe {
            let len = GetWindowTextLengthW(hwnd);
            if len <= 0 {
                return String::new();
            }
            let mut buf = vec![0u16; (len + 1) as usize];
            let actual = GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32);
            if actual > 0 {
                String::from_utf16_lossy(&buf[..actual as usize])
            } else {
                String::new()
            }
        }
    }

    // 通过 PID 获取进程可执行文件路径
    fn get_process_path(pid: u32) -> Option<String> {
        unsafe {
            let handle: HANDLE = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if handle == 0 {
                return None;
            }
            let mut buf = [0u16; 1024];
            let mut len = buf.len() as u32;
            let ok = QueryFullProcessImageNameW(handle, 0, buf.as_mut_ptr(), &mut len);
            CloseHandle(handle);
            if ok != 0 && len > 0 {
                Some(String::from_utf16_lossy(&buf[..len as usize]))
            } else {
                None
            }
        }
    }

    // 从完整路径提取进程名（如 chrome.exe）
    fn extract_process_name(path: &str) -> String {
        path.rsplit('\\')
            .next()
            .unwrap_or(path)
            .to_string()
    }

    // 判断窗口是否为 Toast 通知
    fn is_toast_window(class_name: &str) -> bool {
        // Windows 10/11 Toast 通知窗口类名特征
        class_name.contains("Toast")
            || class_name.contains("Notification")
            || class_name == "Windows.UI.Core.CoreWindow"
    }

    // 通知事件回调
    unsafe extern "system" fn win_event_callback(
        _hook: HWINEVENTHOOK,
        event: u32,
        hwnd: HWND,
        id_object: i32,
        _id_child: i32,
        _thread: u32,
        event_time: u32,
    ) {
        // 只处理窗口本身（非子对象）的 SHOW 事件
        if event != EVENT_OBJECT_SHOW || id_object != 0 || hwnd == 0 {
            return;
        }

        // 检查是否在运行
        {
            let running = SHOULD_RUN.lock().unwrap();
            if !*running {
                return;
            }
        }

        let class = get_class_name(hwnd);
        if !is_toast_window(&class) {
            return;
        }

        // 去重：同一窗口同一时间段不重复处理
        {
            let mut seen = SEEN.lock().unwrap();
            let seen = seen.get_or_insert_with(|| SeenSet {
                entries: HashSet::new(),
                last_cleanup: event_time,
            });

            // 每 60 秒清理一次过期条目
            if event_time.wrapping_sub(seen.last_cleanup) > 60_000 {
                seen.entries.retain(|(_, t)| event_time.wrapping_sub(*t) < 60_000);
                seen.last_cleanup = event_time;
            }

            let key = (hwnd, event_time);
            if seen.entries.contains(&key) {
                return;
            }
            seen.entries.insert(key);
        }

        // 稍等一下让通知内容渲染完成
        std::thread::sleep(std::time::Duration::from_millis(200));

        let title = get_window_text(hwnd);
        if title.is_empty() {
            return;
        }

        // 获取进程信息
        let mut pid: u32 = 0;
        let _ = GetWindowThreadProcessId(hwnd, &mut pid);
        let process_name = if pid > 0 {
            get_process_path(pid)
                .map(|p| extract_process_name(&p))
                .unwrap_or_else(|| format!("pid:{}", pid))
        } else {
            "unknown".to_string()
        };

        // 跳过自身（EchoLink）
        if process_name.eq_ignore_ascii_case("echolink.exe") {
            return;
        }

        // 通过 AppHandle 发送到服务器（JS eval 方式，复用 WebUI 的 auth token）
        if let Some(app) = super::APP_HANDLE.get() {
            let config = read_config(app);

            // 检查应用过滤
            if config.blocked_apps.iter().any(|b| b.eq_ignore_ascii_case(&process_name)) {
                return;
            }

            let server_url = &config.server_url;
            if server_url.is_empty() {
                return;
            }

            if let Some(win) = app.get_webview_window("main") {
                let escaped_title = title.replace('\\', "\\\\").replace('\'', "\\'").replace('"', "\\\"");
                let escaped_app = process_name.replace('\'', "\\'").replace('"', "\\\"");
                let display_name = process_name.trim_end_matches(".exe").to_string();
                let escaped_display = display_name.replace('\'', "\\'").replace('"', "\\\"");

                let js = format!(
                    r#"fetch('{srv}/api/notifications',{{method:'POST',headers:{{'Content-Type':'application/json','Authorization':'Bearer '+(localStorage.getItem('ns_token')||'')}},body:JSON.stringify({{package_name:'{pkg}',app_name:'{app}',title:'{title}',text:'',timestamp:Date.now()}})}}).catch(function(){{}})"#,
                    srv = server_url,
                    pkg = escaped_app,
                    app = escaped_display,
                    title = escaped_title,
                );
                let _ = win.eval(&js);
            }
        }
    }

    pub fn start(app: &AppHandle) {
        let mut running = SHOULD_RUN.lock().unwrap();
        if *running {
            return;
        }
        *running = true;
        drop(running);

        let app_handle = app.clone();
        let _ = APP_HANDLE.set(app_handle);

        // 在单独线程上设置 hook + 消息循环
        std::thread::spawn(|| {
            unsafe {
                let hook = SetWinEventHook(
                    EVENT_OBJECT_SHOW,
                    EVENT_OBJECT_SHOW,
                    0, // hmod
                    Some(win_event_callback),
                    0, // all processes
                    0, // all threads
                    WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS,
                );

                if hook != 0 {
                    *HOOK.lock().unwrap() = Some(hook);

                    // 消息循环（WINEVENT_OUTOFCONTEXT 需要）
                    let mut msg: MSG = std::mem::zeroed();
                    while GetMessageW(&mut msg, 0, 0, 0) > 0 {
                        DispatchMessageW(&msg);
                    }

                    // 清理
                    UnhookWinEvent(hook);
                    *HOOK.lock().unwrap() = None;
                }
            }
            *SHOULD_RUN.lock().unwrap() = false;
        });
    }

    pub fn stop() {
        *SHOULD_RUN.lock().unwrap() = false;
        // UnhookWinEvent 会让 GetMessageW 返回 0，从而退出消息循环
        if let Some(hook) = HOOK.lock().unwrap().take() {
            unsafe {
                windows_sys::Win32::UI::Accessibility::UnhookWinEvent(hook);
            }
        }
    }
}

#[cfg(not(target_os = "windows"))]
mod win_notif {
    use super::*;
    pub fn start(_app: &AppHandle) {}
    pub fn stop() {}
}

fn start_notification_listener(app: &AppHandle) {
    win_notif::start(app);
}

fn stop_notification_listener() {
    win_notif::stop();
}

// 全局 AppHandle（通知回调使用）
static APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();

// ============== 应用入口 ==============

pub struct ServerUrl(pub String);

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            show_main(app);
        }))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .manage(ServerUrl(String::new()))
        .invoke_handler(tauri::generate_handler![
            show_notification,
            set_unread_count,
            app_info,
            save_server_url,
            get_config,
            set_notification_sync,
            set_blocked_apps
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            build_tray(app.handle())?;

            // 如果配置中已开启通知同步，启动监听
            let config = read_config(app.handle());
            let _ = APP_HANDLE.set(app.handle().clone());
            if config.notification_sync_enabled && !config.server_url.is_empty() {
                start_notification_listener(app.handle());
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running EchoLink desktop");
}
