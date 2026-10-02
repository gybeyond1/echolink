# EchoLink Octop 桥接

将 Octop（AI 助手）通知/任务结果推送到 EchoLink，并在 EchoLink 里通过文字/按钮下达指令回传给 Octop。

## 安装

1. 在 EchoLink 管理后台 → 通道管理 → 为你生成 **Octop 通道 Token**
2. 设置环境变量：
   ```bash
   export ECHOLINK_URL="http://192.168.1.100:4000"
   export ECHOLINK_USERNAME="your_user"
   export ECHOLINK_TOKEN="<在后台生成的token>"
   ```
3. 安装 requests：`pip install requests`

## 用法

### 推送通知

```bash
# 简单文本
python echolink.py push --title "构建完成" --text "Docker 镜像 v2.1 已推送至 DockerHub"

# 富文本卡片（带详情和按钮）
python echolink.py push \
  --title "任务报告" \
  --text "全部 42 个测试通过" \
  --details '[{"key":"耗时","value":"2m 30s"},{"key":"覆盖率","value":"94%"}]' \
  --buttons '[{"text":"查看日志","callback_data":"logs:build-123"}]'

# 带海报图片
python echolink.py push \
  --title "部署成功" \
  --text "新版本已上线" \
  --poster "https://example.com/deploy.png"
```

### 流式推送

```bash
# 1. 创建消息
python echolink.py stream_start --text ""
# 输出: {"ok":true,"message_id":12345,...}

# 2. 追加文本
python echolink.py stream_append --message-id 12345 --text "正在分析数据..."
python echolink.py stream_append --message-id 12345 --text "\n结果：一切正常"

# 3. 结束
python echolink.py stream_end --message-id 12345
```

### 拉取用户指令（双向交互）

```bash
# 用户在 EchoLink Octop 话题里发的文字/按钮点击会通过长轮询推送
python echolink.py poll --offset 0 --timeout 30
# 输出: {"ok":true,"updates":[{"message_id":12345,"from":{"username":"u"},"text":"检查磁盘空间","date":...}]}
```

## 与 MoviePilot 插件的区别

| | MoviePilot 插件 | Octop 桥接 |
|---|---|---|
| 形态 | Python MP 插件（自动安装到 MoviePilot） | 独立 Python 脚本（Octop 任务中调用） |
| 触发 | MoviePilot 事件自动触发 | Octop 主动调用 |
| 双向 | 用户文字/按钮 → MP 命令处理 | 用户文字/按钮 → Octop 任务 |
| 部署 | 只需 EchoLink 服务端 | 需 EchoLink 服务端 + Octop 运行环境 |
