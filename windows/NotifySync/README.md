# EchoLink Desktop (Windows)

Tauri 2 桌面客户端，壳里加载 EchoLink WebUI，提供 Windows 专属能力：

- 🪟 **常驻托盘** — 关闭按钮 = 隐藏到托盘，不退出进程；托盘右键菜单（打开主页 / 在浏览器中打开 / 开机自启 / 退出）
- 🔁 **单实例锁** — 重复启动会聚焦已有窗口，不开新窗口
- 🚀 **开机自启** — 托盘菜单一键开关（写入 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`）
- 🔔 **原生通知** — WebUI 可调用 `window.__TAURI__.core.invoke('show_notification', { title, body })` 触发 Windows toast（v1 暂未在 WebUI 中集成，后续可加）
- 📥 **未读计数提示** — WebUI 可调用 `set_unread_count({ count })` 更新窗口标题和托盘 tooltip

## 目录结构

```
windows/NotifySync/
├── package.json            # @tauri-apps/cli
├── dist/                   # 前端资源（一个自动跳转到 NOTIFYSYNC_SERVER 的 redirector）
│   └── index.html
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── build.rs
│   ├── icons/              # PNG + ICO 多尺寸（Telegram 蓝主题）
│   ├── capabilities/
│   │   └── default.json
│   └── src/
│       ├── main.rs         # Windows release 隐藏控制台
│       └── lib.rs          # tray / 命令 / 单实例 / 托盘提示
└── .github/workflows/windows.yml  # CI: windows-latest 跑 tauri build
```

## 本地开发

```powershell
cd windows\NotifySync
npm install
npx tauri dev    # 开发模式（加载 frontendDist）
```

需要 Rust 工具链：<https://rustup.rs/>
WebView2 Runtime 已在 Win10 1803+/Win11 自带。

## 打包

```powershell
$env:NOTIFYSYNC_SERVER = "https://ntfy.225600.xyz:1314"
npx tauri build
```

产物路径：
- `src-tauri/target/release/bundle/nsis/EchoLink_0.1.0_x64-setup.exe`
- `src-tauri/target/release/bundle/msi/EchoLink_0.1.0_x64_en-US.msi`

## 服务器地址配置

- 默认 `https://ntfy.225600.xyz:1314`（与服务器 docker 反代一致）
- 启动时设置环境变量 `NOTIFYSYNC_SERVER=...` 覆盖
- 编译时修改 `src-tauri/src/lib.rs::detect_server_url()` 默认值

## CI

`.github/workflows/windows.yml` 在 push 到 main 且 `windows/**` 变更时自动：
1. 安装 Rust + Node + tauri CLI
2. 跑 `tauri build`
3. 上传 `out/*.exe` + `*.msi` 为 workflow artifact
4. 自动创建/更新 `windows-desktop-latest` Release

手动触发：GitHub Actions → Build Windows Desktop → Run workflow

## 已实现 vs 后续可加

| 功能 | 状态 |
| --- | --- |
| 系统托盘 + 菜单 | ✅ |
| 关窗隐藏到托盘 | ✅ |
| 单实例锁 | ✅ |
| 开机自启切换 | ✅ |
| 原生通知（`show_notification`） | ✅ 命令已实现，WebUI 暂未调用 |
| 未读计数（`set_unread_count`） | ✅ 命令已实现，WebUI 暂未调用 |
| 浏览器中打开当前服务器 | ✅ |
| 自动更新（tauri-plugin-updater） | 后续 |
| P2P 传文件 | 仅 Android/桌面互传场景后续 |
