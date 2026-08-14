# NotifySync

跨设备通知同步系统。在 Android 设备装 App，自动读取指定应用的通知并同步到同一账号下的其他设备；同时支持「公共话题」，按话题名收发消息（类似 ntfy）。

## 功能特性

- **跨设备通知同步**：设备 A 收到微信 / 短信等通知，同账号的其他设备自动收到
- **公共话题**：输入话题名即可进入聊天，发送文字 / 图片 / 语音 / 文件 / 表情，订阅该话题的设备实时收到
- **Web 管理后台**：配置媒体大小上限、话题历史保留条数等
- **私有部署**：服务端一键 Docker 启动，数据自持

## 服务端部署（Docker Compose）

项目 `server/` 目录已内置 `docker-compose.yml`，下面这份可直接复制，按需改一下管理员密码和端口就能用：

```yaml
# NotifySync 一键部署
# 使用方法：
#   1. 按需修改下方 ADMIN_PASSWORD（管理员初始密码）和对外端口
#   2. docker compose up -d --build
#   3. 反向代理把 80/443 转发到本容器的 3000 端口
# 数据保存在 ./data 目录（SQLite 数据库）

services:
  notifysync:
    build: .
    container_name: notifysync
    restart: unless-stopped
    ports:
      - "${PORT:-3000}:3000"
    environment:
      - PORT=3000
      # JWT_SECRET 首次启动自动生成并持久化到数据卷，无需手动设置；
      # 如需固定密钥，取消下一行注释并设置强随机串：
      # - JWT_SECRET=***REDACTED***
      - DB_PATH=/app/data/notifysync.db
      - MAX_NOTIFICATION_HISTORY=${MAX_NOTIFICATION_HISTORY:-500}
      - MAX_TOPIC_HISTORY=${MAX_TOPIC_HISTORY:-200}
      # 管理员账号：首次启动自动创建；之后改这里并重启即可改密码。留空则不创建管理员。
      - ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
      - ADMIN_PASSWORD=***REDACTED***
    volumes:
      - ./data:/app/data
    healthcheck:
      test: ["CMD", "node", "-e", "fetch('http://127.0.0.1:3000/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
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

如需公网访问，用 Nginx 等反向代理把 `80/443` 转发到容器 `3000` 端口，并配置 HTTPS / WSS。

## APK 下载与安装

从 [Releases](https://github.com/gybeyond1/notify-sync/releases) 下载 `NotifySync-v1.0.0.apk`（已用固定密钥签名）安装到手机 / 平板。

基本用法：

1. 打开 App，填入服务器地址（如 `http://192.168.1.100:3000` 或公网域名）
2. 注册并登录**同一账号**（多设备共用一个账号）
3. 进入「设置」→「去授权通知监听」，在系统设置开启 NotifySync 权限
4. 进入「应用」，勾选要同步通知的应用（微信、QQ、短信等）并保存
5. 设备 A 收到勾选应用的通知时，同账号其他设备自动收到
6. 进入「话题」tab，输入话题名进入，即可发消息（支持图片 / 语音 / 文件 / 表情），同一账号其他设备实时收到

> 使用公共话题（类 ntfy）时，话题为服务器公开命名空间：任何已登录账号知道话题名即可订阅收发。

## 技术栈

| 组件 | 技术 |
|------|------|
| 服务端 | Node.js + Express + SQLite + WebSocket |
| 客户端 | Kotlin + Material Design 3 |

## License

MIT
