# EchoLink

跨设备消息互联平台 —— 通知同步 · 话题群聊 · 好友私聊 · P2P 传文件。

在 Android 设备装 App，自动读取指定应用的通知并同步到同一账号下的其他设备；同时支持公共话题群聊、好友私聊、图片/语音/文件传输（含 WebRTC P2P 直连），以及 Web 管理后台。私有部署，数据自持。

## 功能特性

- **跨设备通知同步**：设备 A 收到微信 / 短信等通知，同账号的其他设备自动收到
- **话题群聊**：输入话题名即可进入聊天，发送文字 / 图片 / 语音 / 文件 / 表情，订阅该话题的设备实时收到
- **好友私聊**：搜索用户名加好友，一对一私聊（dm 话题），媒体消息优先 P2P 直连传输
- **P2P 传文件**：好友私聊中图片/文件走 WebRTC DataChannel 点对点直传，30s 超时回退 HTTP
- **我的设备**：自动生成"我的设备"话题，给自己跨设备发消息
- **实时推送**：WebSocket 长连接，消息实时下发；断线自动补拉遗漏通知
- **Web 管理后台**：配置媒体大小上限、话题历史保留条数、用户管理等
- **私有部署**：服务端一键 Docker 启动，数据自持

## 服务端部署（Docker Compose）

项目 `server/` 目录已内置 `docker-compose.yml`，下面这份可直接复制，按需改一下管理员密码和端口就能用：

```yaml
services:
  echolink:
    image: gybeyond/echolink-server:latest
    container_name: echolink
    restart: unless-stopped
    ports:
      - "3000:3000"
    environment:
      - ADMIN_USERNAME=***
      - ADMIN_PASSWORD=***
    volumes:
      - ./data:/app/data
```

启动：

```bash
cd server
docker compose up -d --build
```

- 默认监听 `3000` 端口，访问 `http://<服务器IP>:3000`
- 数据持久化在 `./data`（SQLite 数据库）
- `JWT_SECRET` 首次启动自动生成并持久化，无需手动设置
- 管理员账号默认 `admin / changeme123`，**请务必修改 `ADMIN_PASSWORD`**

如需公网访问，用 Nginx 等反向代理把 `80/443` 转发到容器 `3000` 端口，并配置 HTTPS / WSS（⚠️ 反代必须转发 WebSocket `/ws` 路径，否则实时推送失效）。

### 离线部署（无 Docker Hub）

从 [Releases](https://github.com/gybeyond1/echolink/releases) 下载 `echolink-server-image.tar.gz`，离线加载：

```bash
docker load -i echolink-server-image.tar.gz
docker compose up -d
```

## 下载安装

| 平台 | 文件 | 说明 |
|------|------|------|
| Android | `EchoLink_Android_v1.0.0.apk` | 已用固定密钥签名 |
| Windows | `EchoLink_0.1.0_x64-setup.exe` | NSIS 安装包 |
| Windows | `EchoLink_0.1.0_x64_zh-CN.msi` | MSI 安装包 |
| 服务端 | `echolink-server-image.tar.gz` | Docker 离线镜像包 |

全部在 [Releases](https://github.com/gybeyond1/echolink/releases) 页面下载。

## Android 基本用法

1. 打开 App，填入服务器地址（如 `http://192.168.1.100:3000` 或公网域名）
2. 注册并登录**同一账号**（多设备共用一个账号）
3. 进入「设置」→「去授权通知监听」，在系统设置开启 EchoLink 权限
4. 进入「应用」，勾选要同步通知的应用（微信、QQ、短信等）并保存
5. 设备 A 收到勾选应用的通知时，同账号其他设备自动收到
6. 进入「话题」tab，输入话题名进入，即可发消息（支持图片 / 语音 / 文件 / 表情），同一账号其他设备实时收到
7. 进入「好友」tab，搜索用户名加好友，好友通过后可一对一私聊

> 使用公共话题时，话题为服务器公开命名空间：任何已登录账号知道话题名即可订阅收发。

## 技术栈

| 组件 | 技术 |
|------|------|
| 服务端 | Node.js + Express + SQLite(WAL) + WebSocket |
| Android | Kotlin + Material Design 3 |
| Windows | Tauri 2 + Rust |
| P2P | WebRTC (io.getstream:stream-webrtc-android) |

## License

MIT
