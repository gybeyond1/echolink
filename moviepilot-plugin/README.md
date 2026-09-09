# EchoLink MoviePilot 插件

通过 EchoLink 接收 MoviePilot 通知并远程控制，支持富文本卡片和交互按钮。

## 功能

- 接收 MoviePilot 所有通知（下载完成、入库成功、订阅更新等）
- 富文本卡片展示：海报 + 详情字段 + 交互按钮
- 双向通信：在 EchoLink 里点击按钮或发送文字，远程控制 MoviePilot
- 多用户隔离：每个 EchoLink 用户独立通道

## 安装

1. 在 MoviePilot 插件管理页面安装本插件
2. 配置 EchoLink 服务器地址、用户名、通道 Token
3. 在 EchoLink 管理员页面为对应用户生成 MP 通道 Token

## 配置项

| 配置项 | 说明 |
|--------|------|
| 启用插件 | 开关 |
| EchoLink 服务器地址 | EchoLink 服务端地址（内网地址即可） |
| EchoLink 用户名 | 接收通知的 EchoLink 用户名 |
| EchoLink 通道 Token | 在 EchoLink 管理员页面生成 |

## 开发

按钮回调和文字消息的具体处理逻辑需要根据 MoviePilot 的命令系统进一步实现。
