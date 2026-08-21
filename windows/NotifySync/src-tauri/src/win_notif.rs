// EchoLink Desktop —— Windows 通知监听与上报（对齐 Android 端：过滤本机应用 + 上报服务器广播）
#![cfg(target_os = "windows")]
use crate::api::{InstalledApp, Result};
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Mutex;
use windows_sys::Win32::Foundation::{CloseHandle, HWND, HANDLE};
use windows_sys::Win32::System::Threading::{
    OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows_sys::Win32::UI::Accessibility::{SetWinEventHook, UnhookWinEvent, HWINEVENTHOOK};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    DispatchMessageW, GetClassNameW, GetMessageW, GetWindowTextLengthW, GetWindowTextW,
    GetWindowThreadProcessId, MSG, EVENT_OBJECT_SHOW,
};

const WINEVENT_OUTOFCONTEXT: u32 = 0x0000;
const WINEVENT_SKIPOWNPROCESS: u32 = 0x0002;

static HOOK: AtomicIsize = AtomicIsize::new(0);
static SHOULD_RUN: AtomicBool = AtomicBool::new(false);

struct SeenSet {
    entries: HashSet<(isize, u32)>,
    last_cleanup: u32,
}
static SEEN: Mutex<Option<SeenSet>> = Mutex::new(None);

// 全局客户端快照（上报用）：server + token + filtered exe 集合
struct NotifCtx {
    server: String,
    token: String,
    blocked: HashSet<String>,
    enabled: bool,
}
static CTX: Mutex<Option<NotifCtx>> = Mutex::new(None);

pub fn start(server: String, token: String, blocked: HashSet<String>) {
    SHOULD_RUN.store(true, Ordering::SeqCst);
    *CTX.lock().unwrap() = Some(NotifCtx {
        server,
        token,
        blocked,
        enabled: true,
    });
    std::thread::spawn(|| unsafe {
        let hook = SetWinEventHook(
            EVENT_OBJECT_SHOW,
            EVENT_OBJECT_SHOW,
            core::ptr::null_mut(),
            Some(win_event_callback),
            0,
            0,
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
        SHOULD_RUN.store(false, Ordering::SeqCst);
    });
}

pub fn stop() {
    SHOULD_RUN.store(false, Ordering::SeqCst);
    let hook = HOOK.swap(0, Ordering::SeqCst);
    if hook != 0 {
        unsafe { UnhookWinEvent(hook as HWINEVENTHOOK) };
    }
    *CTX.lock().unwrap() = None;
}

/// 更新过滤列表（用户在设置页增删后调用）
pub fn update_blocked(blocked: HashSet<String>) {
    if let Some(c) = CTX.lock().unwrap().as_mut() {
        c.blocked = blocked;
    }
}

/// 更新凭据（登录/登出后调用）
pub fn update_auth(server: String, token: String) {
    if let Some(c) = CTX.lock().unwrap().as_mut() {
        c.server = server;
        c.token = token;
    }
}

/// 枚举本机已安装应用（注册表 Uninstall 键），供过滤页勾选
pub fn list_installed_apps() -> Vec<InstalledApp> {
    let mut out = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let roots = [
        (
            windows_sys::Win32::System::Registry::HKEY_LOCAL_MACHINE,
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        ),
        (
            windows_sys::Win32::System::Registry::HKEY_LOCAL_MACHINE,
            "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        ),
        (
            windows_sys::Win32::System::Registry::HKEY_CURRENT_USER,
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        ),
    ];
    for (hkey, sub) in roots {
        enum_keys(hkey, sub, &mut out, &mut seen);
    }
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    out
}

fn enum_keys(hkey: windows_sys::Win32::System::Registry::HKEY, sub: &str, out: &mut Vec<InstalledApp>, seen: &mut HashSet<String>) {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegEnumKeyExW, RegOpenKeyExW, HKEY,
    };
    use windows_sys::Win32::Foundation::WIN32_ERROR;
    let sub_w: Vec<u16> = to_wstring(sub);
    let mut key: HKEY = core::ptr::null_mut();
    let status = unsafe {
        RegOpenKeyExW(
            hkey,
            sub_w.as_ptr(),
            0,
            windows_sys::Win32::System::Registry::KEY_READ,
            &mut key,
        )
    };
    if status != 0 {
        return;
    }
    let mut index: u32 = 0;
    loop {
        let mut name_buf = [0u16; 256];
        let mut name_len: u32 = name_buf.len() as u32;
        let r = unsafe {
            RegEnumKeyExW(
                key,
                index,
                name_buf.as_mut_ptr(),
                &mut name_len,
                core::ptr::null_mut(),
                core::ptr::null_mut(),
                core::ptr::null_mut(),
                core::ptr::null_mut(),
            )
        };
        if r != 0 {
            break;
        }
        index += 1;
        let subkey = String::from_utf16_lossy(&name_buf[..name_len as usize]);
        // 读取该子键的 DisplayName / DisplayIcon
        let full = format!("{}\\{}", sub, subkey);
        let full_w = to_wstring(&full);
        let mut subkey_h: HKEY = core::ptr::null_mut();
        if unsafe {
            RegOpenKeyExW(
                hkey,
                full_w.as_ptr(),
                0,
                windows_sys::Win32::System::Registry::KEY_READ,
                &mut subkey_h,
            )
        } != 0
        {
            continue;
        }
        let display_name = read_reg_str(subkey_h, "DisplayName");
        let icon = read_reg_str(subkey_h, "DisplayIcon");
        unsafe { RegCloseKey(subkey_h) };
        if let Some(name) = display_name {
            if name.trim().is_empty() {
                continue;
            }
            // 从 icon 路径推导 exe 名（如 "C:\...\app.exe,0"）
            let exe = icon
                .as_ref()
                .and_then(|i| i.split(',').next())
                .map(|s| s.trim().to_string())
                .unwrap_or_default();
            let key = format!("{}|{}", name, exe.to_lowercase());
            if seen.insert(key) {
                out.push(InstalledApp { name, exe });
            }
        }
    }
    unsafe { RegCloseKey(key) };
}

fn read_reg_str(hkey: windows_sys::Win32::System::Registry::HKEY, val: &str) -> Option<String> {
    use windows_sys::Win32::System::Registry::{RegQueryValueExW, RegCloseKey};
    let val_w = to_wstring(val);
    let mut buf = [0u16; 512];
    let mut len: u32 = (buf.len() as u32) * 2;
    let mut typ: u32 = 0;
    let r = unsafe {
        RegQueryValueExW(
            hkey,
            val_w.as_ptr(),
            core::ptr::null_mut(),
            &mut typ,
            buf.as_mut_ptr() as *mut u8,
            &mut len,
        )
    };
    if r != 0 || len == 0 {
        return None;
    }
    let nchars = (len / 2) as usize;
    let s = String::from_utf16_lossy(&buf[..nchars.min(buf.len())]);
    Some(s.trim_end_matches('\0').to_string())
}

fn to_wstring(s: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    use std::ffi::OsStr;
    OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
}

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

fn get_process_path(pid: u32) -> Option<String> {
    unsafe {
        let handle: HANDLE = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
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

fn extract_process_name(path: &str) -> String {
    path.rsplit('\\').next().unwrap_or(path).to_string()
}

fn is_toast_window(class: &str) -> bool {
    class.contains("Toast") || class.contains("Notification")
        || class == "Windows.UI.Core.CoreWindow"
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
    if !SHOULD_RUN.load(Ordering::SeqCst) {
        return;
    }
    let class = get_class_name(hwnd);
    if !is_toast_window(&class) {
        return;
    }
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
        if seen.entries.contains(&key) {
            return;
        }
        seen.entries.insert(key);
    }

    std::thread::sleep(std::time::Duration::from_millis(200));
    let title = get_window_text(hwnd);
    if title.is_empty() {
        return;
    }
    let mut pid: u32 = 0;
    let _ = GetWindowThreadProcessId(hwnd, &mut pid);
    let process_name = if pid > 0 {
        get_process_path(pid)
            .map(|p| extract_process_name(&p))
            .unwrap_or_else(|| format!("pid:{}", pid))
    } else {
        "unknown".to_string()
    };

    if process_name.eq_ignore_ascii_case("echolink.exe") {
        return;
    }

    let ctx_opt = CTX.lock().unwrap();
    let ctx = match ctx_opt.as_ref() {
        Some(c) if c.enabled && !c.token.is_empty() => CtxState {
            server: c.server.clone(),
            token: c.token.clone(),
            blocked: c.blocked.clone(),
        },
        _ => return,
    };
    drop(ctx_opt);

    if ctx.blocked.iter().any(|b| b.eq_ignore_ascii_case(&process_name)) {
        return;
    }
    let display = process_name.trim_end_matches(".exe").to_string();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new();
        if let Ok(rt) = rt {
            rt.block_on(async move {
                let _ = report(&ctx.server, &ctx.token, &process_name, &display, &title).await;
            });
        }
    });
}

struct CtxState {
    server: String,
    token: String,
    blocked: HashSet<String>,
}

async fn report(
    server: &str,
    token: &str,
    pkg: &str,
    app: &str,
    title: &str,
) -> Result<()> {
    use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION};
    let cli = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;
    let mut h = HeaderMap::new();
    if let Ok(v) = HeaderValue::from_str(&format!("Bearer {}", token)) {
        h.insert(AUTHORIZATION, v);
    }
    let body = serde_json::json!({
        "package_name": pkg,
        "app_name": app,
        "title": title,
        "text": "",
        "timestamp": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    });
    let resp = cli
        .post(format!("{}/api/notifications", server))
        .headers(h)
        .json(&body)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if resp.status().is_success() {
        Ok(())
    } else {
        Err(format!("上报失败: {}", resp.status()))
    }
}
