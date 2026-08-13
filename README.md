# NotifySync - 跨设备通知同步系统

类似 ntfy 的跨设备通知同步方案。在 Android 设备上安装 App，自动读取应用通知并同步到同一账号下的其他设备。

## 架构

```
┌──────────────┐     HTTP POST      ┌──────────────┐     WebSocket      ┌──────────────┐
│  Android A   │ ──────────────────> │   Node.js    │ ──────────────────> │  Android B   │
│ (Notification │   (发送通知)        │   Server     │   (实时推送)         │  (显示通知)   │
│  Listener)   │                     │  (SQLite)    │                     │              │
└──────────────┘ <────────────────── └──────────────┘ <────────────────── └──────────────┘
                       WebSocket           ↑                              HTTP POST
                       (接收通知)           │                            (发送通知)
                                  ┌──────────────┐
                                  │  Android C   │
                                  │  (显示通知)   │
                                  └──────────────┘
```

**工作流程：**
1. Android 设备 A 收到某应用通知 → NotificationListenerService 捕获
2. App 将通知发送到服务器（仅同步用户选中的应用）
3. 服务器通过 WebSocket 实时推送到同账号**其他**设备 B、C（已自动排除发送者本机，不会重复收到自己的通知）
4. 设备 B、C 收到后以本地通知形式展示

**公共话题（类似 ntfy）：**
- 任意设备可订阅一个 `topic`（如 `work`、`family`），向话题发消息
- 所有订阅该话题的设备实时收到，发送者本机自动过滤（不重复弹通知）
- 话题为服务器公开命名空间，所有已登录账号均可按话题名订阅（与 ntfy 一致）

## 项目结构

```
notify-sync/
├── server/                    # Node.js 服务器
│   ├── src/
│   │   ├── index.js           # 入口
│   │   ├── db.js              # SQLite 数据库
│   │   ├── websocket.js       # WebSocket 服务
│   │   ├── middleware/
│   │   │   └── auth.js        # JWT 认证中间件
│   │   └── routes/
│   │       ├── auth.js        # 注册/登录/改密
│   │       ├── devices.js     # 设备管理
│   │       ├── notifications.js # 通知收发（自动排除发送本机）
│   │       ├── filters.js     # 应用过滤器
│   │       └── topics.js      # 公共话题（类似 ntfy）
│   ├── .env                   # 配置文件
│   ├── Dockerfile             # Docker 镜像
│   ├── docker-compose.yml     # Docker Compose 部署
│   ├── test-integration.js    # 集成测试（通知过滤 + 话题）
│   └── package.json
│
└── android/                   # Android 应用
    ├── app/src/main/java/com/notifysync/
    │   ├── App.kt             # Application 类
    │   ├── data/
    │   │   ├── Models.kt      # 数据模型
    │   │   ├── AuthManager.kt # 认证管理
    │   │   ├── ApiClient.kt   # HTTP API 客户端
    │   │   └── WebSocketClient.kt # WebSocket 客户端
    │   ├── service/
    │   │   ├── NotificationListener.kt # 通知监听服务
    │   │   ├── SyncService.kt          # 前台同步服务
    │   │   └── BootReceiver.kt         # 开机自启
    │   └── ui/
    │       ├── LoginActivity.kt        # 登录/注册（填服务器地址+账号+密码）
    │       ├── MainActivity.kt         # 主界面
    │       ├── NotificationsFragment.kt # 通知列表
    │       ├── TopicFragment.kt         # 公共话题（订阅/发言）
    │       ├── AppFilterFragment.kt     # 应用选择
    │       ├── SettingsFragment.kt      # 设置
    │       └── *Adapter.kt             # 列表适配器
    └── app/src/main/res/      # 布局和资源文件
```

---

## 服务器部署

### 环境要求
- Node.js >= 18
- npm 或 yarn

### 安装与启动

```bash
cd notify-sync/server
npm install
npm start
```

服务器默认运行在 `http://0.0.0.0:3000`。

### 配置

编辑 `.env` 文件（不写也可，docker-compose 会通过环境变量覆盖）：

```env
PORT=3000
JWT_SECRET=your-random-secret-key   # 务必修改！
DB_PATH=./data/notifysync.db
MAX_NOTIFICATION_HISTORY=500        # 每用户保留的通知条数
MAX_TOPIC_HISTORY=200               # 每个话题保留的消息条数
```

### 部署方式一：Docker Compose（推荐，最省事）

项目已内置 `Dockerfile` 和 `docker-compose.yml`。

```bash
cd notify-sync/server

# 1. 生成随机 JWT 密钥（二选一）
export JWT_SECRET=$(openssl rand -hex 32)

# 2. 启动（构建镜像 + 后台运行）
docker compose up -d --build

# 3. 查看日志
docker compose logs -f
```

- 数据持久化在 `./data` 目录（已挂载为 volume）
- 容器监听 `3000` 端口，可用 `http://<服务器IP>:3000` 访问

### 部署方式二：Docker 单独运行

```bash
cd notify-sync/server
docker build -t notifysync .
docker run -d \
  --name notifysync \
  -p 3000:3000 \
  -e JWT_SECRET=your-random-secret-key \
  -e DB_PATH=/app/data/notifysync.db \
  -v $(pwd)/data:/app/data \
  --restart unless-stopped \
  notifysync
```

### 部署方式三：本地裸跑 + pm2

```bash
npm install -g pm2
pm2 start src/index.js --name notifysync
pm2 save
pm2 startup
```

### 部署到公网（反向代理）

你的场景：Docker 部署后，通过反向代理把服务暴露到公网，手机/平板填公网地址登录。

**Nginx 反向代理**（支持 HTTPS/WSS）
```nginx
server {
    listen 443 ssl;
    server_name sync.yourdomain.com;

    ssl_certificate     /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /ws {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

---

## Android 应用编译

### 环境要求
- Android Studio (Hedgehog 2023.1.1 或更高)
- JDK 17
- Android SDK 34
- Kotlin 1.9.22

### 编译步骤

1. **打开项目**
   - 打开 Android Studio
   - 选择 `File > Open` > 选择 `notify-sync/android` 目录

2. **等待 Gradle 同步**
   - 首次打开会自动下载依赖，请耐心等待

3. **编译 APK**
   - `Build > Build Bundle(s) / APK(s) > Build APK(s)`
   - 或在命令行：
     ```bash
     cd notify-sync/android
     ./gradlew assembleDebug
     ```
   - 生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`

4. **安装到设备**
   - 通过 USB 安装：`adb install app/build/outputs/apk/debug/app-debug.apk`
   - 或将 APK 传到手机直接安装

### 修改服务器地址

默认服务器地址是 `http://10.0.2.2:3000`（Android 模拟器访问本机的地址）。

**真机使用时**，在登录页修改为你的服务器地址，如 `http://192.168.1.100:3000`。

---

## 使用指南

### 1. 启动服务器
```bash
cd notify-sync/server
npm start
```

### 2. 手机安装 App
- 编译 APK 并安装到手机和平板

### 3. 注册账号
- 打开 App，输入服务器地址
- 点击"没有账号？去注册"
- 创建用户名和密码（同一账号在所有设备上使用）

### 4. 授权通知监听
- 登录后进入「设置」tab
- 点击"去授权通知监听"
- 在系统设置中找到 NotifySync 并开启权限

### 5. 选择同步应用
- 进入「应用」tab
- 勾选需要同步通知的应用（如微信、QQ、短信等）
- 点击"保存"

### 6. 开始使用
- 在另一台设备上重复步骤 2-5
- 当设备 A 收到勾选应用的通知时，设备 B 会自动收到同步通知

### 7. 使用公共话题（类似 ntfy）

公共话题用于多设备之间按话题名互通消息，不依赖具体 App 通知。

1. 进入「话题」tab（底部第四个图标）
2. 点击「添加」，输入话题名（如 `work`、`family`，仅字母/数字/下划线/连字符）
3. 在底部输入框输入消息，点击「发送」
4. 同一账号下**其他已订阅该话题**的设备会实时收到，本机不会重复弹通知
5. 也可以在电脑上用 curl 发消息到话题：

```bash
# 需先注册/登录拿到 token
curl -X POST "http://<你的服务器>/api/topics/work/publish" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"title":"提醒","text":"该开会了","device_id":<设备ID>}'
```

> 话题为服务器公开命名空间：任何已登录账号只要知道话题名即可订阅并收发，与 ntfy 的 public topic 行为一致。

---

## API 文档

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 验证 token |
| POST | `/api/auth/change-password` | 修改密码 |

### 设备
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/devices/register` | 注册设备 |
| GET | `/api/devices` | 设备列表 |
| DELETE | `/api/devices/:id` | 删除设备 |
| POST | `/api/devices/heartbeat` | 心跳 |

### 通知
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/notifications` | 发送通知 |
| GET | `/api/notifications` | 通知列表 |
| GET | `/api/notifications/since/:timestamp` | 增量获取 |
| DELETE | `/api/notifications/:id` | 删除通知 |
| DELETE | `/api/notifications` | 清空通知 |

### 应用过滤器
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/filters` | 过滤器列表 |
| POST | `/api/filters` | 添加/更新 |
| POST | `/api/filters/batch` | 批量更新 |
| DELETE | `/api/filters/:package_name` | 删除 |

### 公共话题（类似 ntfy）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/topics/:topic/publish` | 向话题发布消息（body: `title`,`text`,`sender_name?`,`device_id?`），服务器自动推送给订阅者并排除发送者本机 |
| GET | `/api/topics/:topic/messages?limit=50&since=` | 获取话题历史消息 |
| GET | `/api/topics` | 列出有消息记录的话题 |

### WebSocket
- 连接：`ws://host:3000/ws?token=<JWT>&device_id=<设备ID>`（`device_id` 用于服务器过滤本机自己的消息）
- 服务器 → 客户端推送：
  - `{"type":"notification","data":{...}}` —— 其他设备同步来的系统通知
  - `{"type":"topic_message","topic":"work","data":{...}}` —— 话题新消息
- 客户端 → 服务器（通过 WS 发布/订阅，无需每条都走 HTTP）：
  - `{"type":"subscribe","topic":"work"}`
  - `{"type":"unsubscribe","topic":"work"}`
  - `{"type":"publish","topic":"work","title":"","text":"内容"}`

---

## 安全注意事项

1. **务必修改 JWT_SECRET**：`.env` 中的 `JWT_SECRET` 必须改为随机字符串
2. **生产环境使用 HTTPS**：通过 Nginx 配置 SSL 证书，确保数据传输加密
3. **服务器防火墙**：仅开放必要端口（3000 或 443）
4. **App 明文传输**：`AndroidManifest.xml` 中 `usesCleartextTraffic="true"` 仅用于开发调试，生产环境应使用 HTTPS 并关闭此选项

---

## 技术栈

| 组件 | 技术 |
|------|------|
| 服务器 | Node.js + Express |
| 数据库 | SQLite (better-sqlite3) |
| 实时通信 | WebSocket (ws) |
| 认证 | JWT (jsonwebtoken) + bcrypt |
| Android | Kotlin + Material Design 3 |
| 网络 | OkHttp (HTTP + WebSocket) |
| 架构 | MVVM + Fragment + ViewBinding |

---

## License

MIT
