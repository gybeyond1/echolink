// NotifySync Desktop —— Tauri 2 库入口
// 提供：托盘菜单 / 单实例锁 / 关闭窗口隐藏到托盘 / 系统通知 / 未读计数托盘提示 / 开机自启
use serde::Serialize;
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager,
};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;

// ============== Tauri 命令（可由 WebUI JS 调用） ==============

/// 触发 Windows 原生通知（无 JS 依赖：从任何 window.__TAURI__ 可达的地方调用即可）
#[tauri::command]
async fn show_notification(app: AppHandle, title: String, body: String) -> Result<(), String> {
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|e| e.to_string())
}

/// 更新托盘悬浮提示（用作未读计数提示；Windows 任务栏图标无法直接显示数字，
/// 只能通过 tooltip + 主窗口标题同步给用户感知）。
#[tauri::command]
async fn set_unread_count(app: AppHandle, count: u32) -> Result<(), String> {
    let tip = if count > 0 {
        format!("NotifySync · {} 条未读", count)
    } else {
        "NotifySync".to_string()
    };
    if let Some(tray) = app.tray_by_id("main-tray") {
        tray.set_tooltip(Some(&tip)).map_err(|e| e.to_string())?;
    }
    if let Some(win) = app.get_webview_window("main") {
        let title = if count > 0 {
            format!("NotifySync ({} 未读)", count)
        } else {
            "NotifySync".to_string()
        };
        win.set_title(&title).ok();
    }
    Ok(())
}

#[derive(Serialize)]
struct AppInfo {
    version: &'static str,
    target: &'static str,
    server_url: String,
}

#[tauri::command]
fn app_info(app: AppHandle) -> AppInfo {
    AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        target: std::env::consts::OS,
        server_url: app.state::<ServerUrl>().0.clone(),
    }
}

// ============== 托盘 ==============

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
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
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[&show, &open_browser, &toggle_autostart, &sep, &quit],
    )?;

    TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        .show_menu_on_left_click(false) // 左键单击 = 显示窗口，菜单靠右键
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "open_browser" => {
                let url = app
                    .state::<crate::ServerUrl>()
                    .0
                    .clone();
                let _ = app.opener().open_url(url, None::<&str>);
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
            // 左键单击：显示主窗口
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

// ============== 应用入口 ==============

/// 通过 NOTIFYSYNC_SERVER 环境变量覆盖服务器地址；默认从本地 dev 服务或公网反代地址。
fn detect_server_url() -> String {
    if let Ok(v) = std::env::var("NOTIFYSYNC_SERVER") {
        if !v.trim().is_empty() {
            return v.trim().trim_end_matches('/').to_string();
        }
    }
    // 默认指向公网反代地址（与发布版一致）
    "https://ntfy.225600.xyz:1314".to_string()
}

pub struct ServerUrl(pub String);

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let server_url = detect_server_url();

    tauri::Builder::default()
        // 单实例：第二次启动时聚焦已有窗口
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            show_main(app);
        }))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .manage(ServerUrl(server_url))
        .invoke_handler(tauri::generate_handler![
            show_notification,
            set_unread_count,
            app_info
        ])
        .on_window_event(|window, event| {
            // 关闭按钮 = 隐藏到托盘，不退出进程
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            build_tray(app.handle())?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running NotifySync desktop");
}