// NotifySync Desktop — Tauri 2 应用入口
// 关闭控制台窗口（Windows release 默认是 subsystem:windows）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    notifysync_desktop_lib::run();
}