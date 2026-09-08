# EchoLink

跨设备消息互联平台 —— 通知同步 · 话题群聊 · 好友私聊 · 留言板联动。

在 Android / 平板 / Windows 设备装客户端，登录同一账号后，一台设备收到的通知会实时同步到其他设备；同时支持公共话题群聊、好友私聊、图片/语音/视频/文件传输，以及门边留言板消息推送。私有部署，数据自持。

## 功能特性

- **跨设备通知同步**：设备 A 收到微信 / 短信等通知，同账号下的手机、平板、电脑实时收到
- **应用过滤（黑名单）**：默认同步所有应用通知，可在设置里屏蔽掉不想同步的应用
- **我的设备**：自动生成"我的设备"话题，给自己跨设备发消息、传文件
- **话题群聊**：输入话题名即可进入聊天，支持文字 / 图片 / 语音 / 视频 / 文件，订阅该话题的设备实时收到
- **好友私聊**：搜索用户名加好友，一对一私聊，支持媒体消息
- **留言板联动**：配合 [MessageWall](https://github.com/gybeyond1/messagewall) 门边留言板使用，访客扫码留言直接推送到 EchoLink 对应账号
- **多用户留言板**：每个 EchoLink 用户有独立的留言板话题，管理员可在后台开启/关闭
- **语音消息**：长按录音、上滑取消、微信式语音气泡、声波播放动画
- **图片/视频预览**：全屏查看、左右滑动切换、双指缩放、视频内置播放器、本地缓存自动清理
- **新拟态 UI**：亮/暗双主题，手机/平板自适应布局，平板端侧滑栏
- **实时推送**：WebSocket 长连接，断线自动重连补拉
- **Web 管理后台**：用户管理、媒体大小上限、留言板配置、服务器设置
- **Windows 桌面端**：Tauri 打包，轻量低占用
- **私有部署**：Docker 一键启动，SQLite 数据库，数据全部自持

## 服务端部署（Docker Compose）

创建 `docker-compose.yml`：

```yaml
services:
  echolink:
    image: gybeyond/echolink-server:latest
    container_name: echolink
    restart: unless-stopped
    ports:
      - "4000:3000"
    environment:
      - ADMIN_USERNAME=admin
      - ADMIN_PASSWORD=请改成你的管理员密码
    volumes:
      - ./data:/app/data
```

启动：

```bash
docker compose up -d
```

- 服务监听容器内 `3000` 端口，上面映射到宿主机 `4000`，可自行修改
- 访问 `http://<服务器IP>:4000` 打开 Web 端
- 数据持久化在 `./data`（SQLite 数据库 + 用户头像等）
- `JWT_SECRET` 首次启动自动生成并持久化，无需手动设置
- **请务必修改 `ADMIN_PASSWORD`**，默认管理员账号在后台可改密码

如需公网访问，用 Nginx / Caddy 等反向代理转发到容器 `3000` 端口，并配置 HTTPS。⚠️ 反代必须转发 WebSocket `/ws` 路径，否则实时推送失效。

### 离线部署

从 [Releases](https://github.com/gybeyond1/echolink/releases) 下载 `echolink-server-image.tar.gz`：

```bash
docker load -i echolink-server-image.tar.gz
docker compose up -d
```

## 客户端下载

| 平台 | 文件 | 说明 |
|------|------|------|
| Android / 平板 | `EchoLink_Android_v1.0.0.apk` | 手机平板自适应，已签名 |
| Windows | `EchoLink_0.1.0_x64-setup.exe` | 桌面端安装包 |
| 服务端 | `echolink-server-image.tar.gz` | Docker 离线镜像 |

全部在 [Releases](https://github.com/gybeyond1/echolink/releases) 页面下载。

## 基本用法

1. 打开 App，填入服务器地址（如 `http://192.168.1.100:4000` 或公网域名）
2. 注册并登录**同一账号**（多设备共用一个账号实现通知同步）
3. 进入「设置」→「通知监听权限」，在系统设置中开启 EchoLink 的通知使用权
4. 进入「应用过滤」，屏蔽掉不想同步通知的应用（默认所有应用通知都同步，屏蔽的才不同步）
5. 设备 A 收到未被屏蔽应用的通知时，同账号其他设备自动收到
6. 「消息」页查看通知、我的设备、留言板消息；「好友」页搜索加好友私聊
7. 右下角「+」可发现/创建话题、添加好友、进入设置

### 留言板联动

1. 先部署 [MessageWall](https://github.com/gybeyond1/messagewall) 留言板服务
2. 在 EchoLink 管理后台 → 服务器设置 → 填写 MessageWall 同步地址（留言板内网地址，如 `http://192.168.1.100:13000`）
3. 在 MessageWall 管理后台 → EchoLink 联动 → 填写 EchoLink 内网地址（如 `http://192.168.1.100:4000`）
4. 配置完成后，EchoLink 每注册一个新用户会自动在 MessageWall 创建对应留言板用户
5. 访客访问 `http://留言板地址/用户名` 留言，消息直接推送到该 EchoLink 用户的留言板话题

## 技术栈

| 组件 | 技术 |
|------|------|
| 服务端 | Node.js + Express + SQLite(WAL) + WebSocket |
| Android | Kotlin + Material Design 3 |
| Windows | Tauri 2 + Rust |
| 前端 WebUI | 原生 HTML/CSS/JS |

## License

MIT
