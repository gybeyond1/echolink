// EchoLink Desktop —— Tauri 2 库入口
// 功能：托盘菜单 / 单实例锁 / 关闭窗口隐藏到托盘 / 系统通知 / 未读计数提示 /
//       开机自启 / 服务器地址配置 / Windows 系统通知监听同步 / 应用过滤

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
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
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
    if let Some(state) = app.try_state::<TrayState>() {
        if let Some(item) = state.sync_check.lock().unwrap().as_ref() {
            let _ = item.set_checked(enabled);
        }
    }

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

/// 列出本机已安装应用（从注册表读取）
#[derive(Serialize, Clone)]
struct InstalledApp {
    name: String,
    exe: String,
}

#[tauri::command]
fn list_installed_apps() -> Vec<InstalledApp> {
    installed_apps::list()
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

/// 托盘状态：保存 CheckMenuItem 引用以便运行时更新勾选
struct TrayState {
    sync_check: Mutex<Option<CheckMenuItem<tauri::Wry>>>,
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let config = read_config(app);

    let show = MenuItem::with_id(app, "show", "打开主页", true, None::<&str>)?;
    let open_browser = MenuItem::with_id(app, "open_browser", "在浏览器中打开", true, None::<&str>)?;
    let toggle_autostart = MenuItem::with_id(app, "toggle_autostart", "开机自启", true, None::<&str>)?;
    let sync_notifications = CheckMenuItem::with_id(
        app,
        "sync_notifications",
        "通知同步",
        true,
        config.notification_sync_enabled,
        None::<&str>,
    )?;
    let app_filter = MenuItem::with_id(app, "app_filter", "应用过滤设置", true, None::<&str>)?;
    let switch_server = MenuItem::with_id(app, "switch_server", "切换服务器", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let sep1 = PredefinedMenuItem::separator(app)?;
    let sep2 = PredefinedMenuItem::separator(app)?;
    let sep3 = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[&show, &open_browser, &sep1, &sync_notifications, &app_filter, &switch_server, &sep2, &toggle_autostart, &sep3, &quit],
    )?;

    // 保存 CheckMenuItem 以便运行时更新勾选状态
    app.manage(TrayState {
        sync_check: Mutex::new(Some(sync_notifications.clone())),
    });

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
                let config = read_config(app);
                let new_state = !config.notification_sync_enabled;
                let _ = set_notification_sync(app.clone(), new_state);
            }
            "app_filter" => {
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                    #[cfg(target_os = "windows")]
                    let _ = win.eval("window.location.replace('http://tauri.localhost/settings.html')");
                    #[cfg(not(target_os = "windows"))]
                    let _ = win.eval("window.location.replace('tauri://localhost/settings.html')");
                }
            }
            "switch_server" => {
                let mut config = read_config(app);
                config.server_url = String::new();
                let _ = write_config(app, &config);
                stop_notification_listener();
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
                if enabled { let _ = mgr.disable(); } else { let _ = mgr.enable(); }
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
    use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
    use windows_sys::Win32::Foundation::{CloseHandle, HWND, HANDLE};
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows_sys::Win32::UI::Accessibility::{SetWinEventHook, UnhookWinEvent, HWINEVENTHOOK};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        DispatchMessageW, GetClassNameW, GetMessageW, GetWindowThreadProcessId,
        GetWindowTextLengthW, GetWindowTextW, MSG, EVENT_OBJECT_SHOW,
    };

    // WinEvent 常量（windows-sys 0.59 未导出，手动定义）
    const WINEVENT_OUTOFCONTEXT: u32 = 0x0000;
    const WINEVENT_SKIPOWNPROCESS: u32 = 0x0002;

    static HOOK: AtomicIsize = AtomicIsize::new(0);
    static SHOULD_RUN: AtomicBool = AtomicBool::new(false);

    // 已见通知去重
    struct SeenSet {
        entries: HashSet<(isize, u32)>,
        last_cleanup: u32,
    }
    static SEEN: Mutex<Option<SeenSet>> = Mutex::new(None);

    fn get_class_name(hwnd: HWND) -> String {
        unsafe {
            let mut buf = [0u16; 512];
            let len = GetClassNameW(hwnd, buf.as_mut_ptr(), buf.len() as i32);
            if len > 0 { String::from_utf16_lossy(&buf[..len as usize]) } else { String::new() }
        }
    }

    fn get_window_text(hwnd: HWND) -> String {
        unsafe {
            let len = GetWindowTextLengthW(hwnd);
            if len <= 0 { return String::new(); }
            let mut buf = vec![0u16; (len + 1) as usize];
            let actual = GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32);
            if actual > 0 { String::from_utf16_lossy(&buf[..actual as usize]) } else { String::new() }
        }
    }

    fn get_process_path(pid: u32) -> Option<String> {
        unsafe {
            let handle: HANDLE = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if handle.is_null() { return None; }
            let mut buf = [0u16; 1024];
            let mut len = buf.len() as u32;
            let ok = QueryFullProcessImageNameW(handle, 0, buf.as_mut_ptr(), &mut len);
            CloseHandle(handle);
            if ok != 0 && len > 0 { Some(String::from_utf16_lossy(&buf[..len as usize])) } else { None }
        }
    }

    fn extract_process_name(path: &str) -> String {
        path.rsplit('\\').next().unwrap_or(path).to_string()
    }

    fn is_toast_window(class_name: &str) -> bool {
        class_name.contains("Toast") || class_name.contains("Notification")
            || class_name == "Windows.UI.Core.CoreWindow"
    }

    unsafe extern "system" fn win_event_callback(
        _hook: HWINEVENTHOOK,
        event: u32,
        hwnd: HWND,
        id_object: i32,
        _id_child: i32,
        _thread: u32,
        event_time: u32,
    ) {
        if event != EVENT_OBJECT_SHOW || id_object != 0 || hwnd.is_null() {
            return;
        }
        if !SHOULD_RUN.load(Ordering::SeqCst) { return; }

        let class = get_class_name(hwnd);
        if !is_toast_window(&class) { return; }

        // 去重
        {
            let mut seen = SEEN.lock().unwrap();
            let seen = seen.get_or_insert_with(|| SeenSet {
                entries: HashSet::new(),
                last_cleanup: event_time,
            });
            if event_time.wrapping_sub(seen.last_cleanup) > 60_000 {
                seen.entries.retain(|(_, t)| event_time.wrapping_sub(*t) < 60_000);
                seen.last_cleanup = event_time;
            }
            let key = (hwnd as isize, event_time);
            if seen.entries.contains(&key) { return; }
            seen.entries.insert(key);
        }

        // 等待通知内容渲染
        std::thread::sleep(std::time::Duration::from_millis(200));

        let title = get_window_text(hwnd);
        if title.is_empty() { return; }

        let mut pid: u32 = 0;
        let _ = GetWindowThreadProcessId(hwnd, &mut pid);
        let process_name = if pid > 0 {
            get_process_path(pid).map(|p| extract_process_name(&p)).unwrap_or_else(|| format!("pid:{}", pid))
        } else { "unknown".to_string() };

        // 跳过自身
        if process_name.eq_ignore_ascii_case("echolink.exe") { return; }

        if let Some(app) = super::APP_HANDLE.get() {
            let config = read_config(app);
            if config.blocked_apps.iter().any(|b| b.eq_ignore_ascii_case(&process_name)) { return; }
            if config.server_url.is_empty() { return; }

            if let Some(win) = app.get_webview_window("main") {
                let esc = |s: &str| s.replace('\\', "\\\\").replace('\'', "\\'").replace('"', "\\\"");
                let display = process_name.trim_end_matches(".exe").to_string();
                let js = format!(
                    r#"fetch('{srv}/api/notifications',{{method:'POST',headers:{{'Content-Type':'application/json','Authorization':'Bearer '+(localStorage.getItem('ns_token')||'')}},body:JSON.stringify({{package_name:'{pkg}',app_name:'{app}',title:'{title}',text:'',timestamp:Date.now()}})}}).catch(function(){{}})"#,
                    srv = config.server_url,
                    pkg = esc(&process_name),
                    app = esc(&display),
                    title = esc(&title),
                );
                let _ = win.eval(&js);
            }
        }
    }

    pub fn start(app: &AppHandle) {
        if SHOULD_RUN.load(Ordering::SeqCst) { return; }
        SHOULD_RUN.store(true, Ordering::SeqCst);
        let _ = APP_HANDLE.set(app.clone());

        std::thread::spawn(|| {
            unsafe {
                let hook = SetWinEventHook(
                    EVENT_OBJECT_SHOW, EVENT_OBJECT_SHOW,
                    core::ptr::null_mut(),
                    Some(win_event_callback),
                    0, 0,
                    WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS,
                );
                if !hook.is_null() {
                    HOOK.store(hook as isize, Ordering::SeqCst);
                    let mut msg: MSG = std::mem::zeroed();
                    while GetMessageW(&mut msg, core::ptr::null_mut(), 0, 0) > 0 {
                        DispatchMessageW(&msg);
                    }
                    UnhookWinEvent(hook);
                    HOOK.store(0, Ordering::SeqCst);
                }
            }
            SHOULD_RUN.store(false, Ordering::SeqCst);
        });
    }

    pub fn stop() {
        SHOULD_RUN.store(false, Ordering::SeqCst);
        let hook = HOOK.swap(0, Ordering::SeqCst);
        if hook != 0 {
            unsafe { UnhookWinEvent(hook as HWINEVENTHOOK); }
        }
    }
}

#[cfg(not(target_os = "windows"))]
mod win_notif {
    use super::*;
    pub fn start(_app: &AppHandle) {}
    pub fn stop() {}
}

// ============== 已安装应用列表（注册表读取） ==============

#[cfg(target_os = "windows")]
mod installed_apps {
    use super::*;
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegEnumKeyW, RegOpenKeyExW, RegQueryValueExW, HKEY, HKEY_CURRENT_USER,
        HKEY_LOCAL_MACHINE,
    };

    // 注册表常量（避免依赖 windows-sys 是否导出）
    const KEY_READ: u32 = 0x20019;
    const REG_SZ: u32 = 1;
    const ERROR_SUCCESS: u32 = 0;

    fn to_wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// 读取注册表字符串值 (REG_SZ)
    fn read_reg_string(hkey: HKEY, name: &str) -> Option<String> {
        let name_w = to_wide(name);
        let mut data_type: u32 = 0;
        let mut data_len: u32 = 0;

        // 第一次调用：获取数据大小
        let ret = unsafe {
            RegQueryValueExW(
                hkey,
                name_w.as_ptr(),
                std::ptr::null(),
                &mut data_type,
                std::ptr::null_mut(),
                &mut data_len,
            )
        };
        if ret != ERROR_SUCCESS || data_len == 0 {
            return None;
        }

        // 第二次调用：读取数据
        let mut buf = vec![0u8; data_len as usize];
        let ret = unsafe {
            RegQueryValueExW(
                hkey,
                name_w.as_ptr(),
                std::ptr::null(),
                &mut data_type,
                buf.as_mut_ptr(),
                &mut data_len,
            )
        };
        if ret != ERROR_SUCCESS || data_type != REG_SZ {
            return None;
        }

        // REG_SZ 是 UTF-16 编码
        let u16_count = buf.len() / 2;
        let u16_data: &[u16] =
            unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u16, u16_count) };
        let s = String::from_utf16_lossy(u16_data);
        let s = s.trim_end_matches('\0').to_string();
        if s.is_empty() { None } else { Some(s) }
    }

    /// 从 DisplayIcon 值提取 exe 文件名
    /// 例如: "C:\Program Files\Google\Chrome\Application\chrome.exe",0 → chrome.exe
    fn extract_exe_name(icon_path: &str) -> Option<String> {
        let path = icon_path.trim();
        if path.is_empty() { return None; }

        // 去掉引号
        let path = path.trim_matches('"');

        // 去掉图标索引 (逗号后面的部分)
        let path = path.split(',').next()?.trim();

        // 只接受 .exe 文件
        if !path.to_lowercase().ends_with(".exe") { return None; }

        // 提取文件名
        let exe = path.rsplit('\\').next().or_else(|| path.rsplit('/').next())?;
        if exe.is_empty() { return None; }
        Some(exe.to_string())
    }

    /// 从注册表 Uninstall 键读取已安装应用列表
    pub fn list() -> Vec<InstalledApp> {
        let locations: [(HKEY, &str); 3] = [
            (HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"),
            (HKEY_LOCAL_MACHINE, "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"),
            (HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"),
        ];

        let mut apps = Vec::new();
        let mut seen = HashSet::new();

        for (root, subkey) in locations {
            let subkey_w = to_wide(subkey);
            let mut hkey: HKEY = 0;
            let ret = unsafe { RegOpenKeyExW(root, subkey_w.as_ptr(), 0, KEY_READ, &mut hkey) };
            if ret != ERROR_SUCCESS { continue; }

            let mut index: u32 = 0;
            loop {
                let mut name_buf = [0u16; 256];
                let mut name_len = name_buf.len() as u32;
                let ret = unsafe { RegEnumKeyW(hkey, index, name_buf.as_mut_ptr(), &mut name_len) };
                if ret != ERROR_SUCCESS { break; }
                if name_len == 0 { index += 1; continue; }

                let subkey_name = String::from_utf16_lossy(&name_buf[..name_len as usize]);
                let full_path = format!("{}\\{}", subkey, subkey_name);
                let full_path_w = to_wide(&full_path);
                let mut sub_hkey: HKEY = 0;
                let ret =
                    unsafe { RegOpenKeyExW(root, full_path_w.as_ptr(), 0, KEY_READ, &mut sub_hkey) };
                if ret == ERROR_SUCCESS {
                    let display_name = read_reg_string(sub_hkey, "DisplayName");
                    let display_icon = read_reg_string(sub_hkey, "DisplayIcon");
                    if let (Some(name), Some(icon)) = (display_name, display_icon) {
                        if let Some(exe) = extract_exe_name(&icon) {
                            let exe_lower = exe.to_lowercase();
                            if !seen.contains(&exe_lower) {
                                seen.insert(exe_lower);
                                apps.push(InstalledApp { name, exe });
                            }
                        }
                    }
                    unsafe { RegCloseKey(sub_hkey) };
                }
                index += 1;
            }
            unsafe { RegCloseKey(hkey) };
        }

        apps.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        apps
    }
}

#[cfg(not(target_os = "windows"))]
mod installed_apps {
    use super::*;
    pub fn list() -> Vec<InstalledApp> {
        Vec::new()
    }
}

fn start_notification_listener(app: &AppHandle) {
    win_notif::start(app);
}

fn stop_notification_listener() {
    win_notif::stop();
}

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
            show_notification, set_unread_count, app_info, save_server_url,
            get_config, set_notification_sync, set_blocked_apps, list_installed_apps
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            build_tray(app.handle())?;
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
