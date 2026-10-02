"""
Octop ↔ EchoLink 桥接脚本

用法:
  1. 推送通知:
     python echolink.py push --title "任务完成" --text "所有文件已处理完毕" --poster "https://..." \
        --details '[{"key":"耗时","value":"3分钟"},{"key":"结果","value":"成功"}]' \
        --buttons '[{"text":"查看详情","callback_data":"view:abc123"}]' \
        --stream

  2. 流式消息:
     python echolink.py stream-start --text "正在分析..."
     python echolink.py stream-append --message-id 12345 --text " 第一步完成"
     python echolink.py stream-end --message-id 12345

  3. 长轮询拉取用户指令:
     python echolink.py poll --offset 0 --timeout 30

环境变量:
  ECHOLINK_URL       EchoLink 服务器地址（如 http://192.168.1.100:4000）
  ECHOLINK_USERNAME  EchoLink 用户名
  ECHOLINK_TOKEN     Octop 通道 Token（在 EchoLink 管理后台生成）
"""

import argparse
import json
import os
import sys
import time
import requests


def get_env():
    url = os.environ.get("ECHOLINK_URL", "").rstrip("/")
    username = os.environ.get("ECHOLINK_USERNAME", "")
    token = os.environ.get("ECHOLINK_TOKEN", "")
    if not url or not username or not token:
        print("错误: 请设置环境变量 ECHOLINK_URL, ECHOLINK_USERNAME, ECHOLINK_TOKEN", file=sys.stderr)
        sys.exit(1)
    return url, username, token


def api(url, token, path, method="POST", json_body=None, params=None):
    headers = {"X-Octop-Token": token, "Content-Type": "application/json"}
    if method == "GET":
        r = requests.get(f"{url}{path}", headers=headers, params=params or {}, timeout=30)
    else:
        r = requests.post(f"{url}{path}", headers=headers, json=json_body or {}, timeout=30)
    r.raise_for_status()
    return r.json()


def cmd_push(args):
    url, username, token = get_env()
    card = {
        "title": args.title,
        "text": args.text or "",
    }
    if args.poster:
        card["poster"] = args.poster
    if args.details:
        try:
            card["details"] = json.loads(args.details)
        except json.JSONDecodeError:
            print(f"错误: --details 不是有效的 JSON 数组: {args.details}", file=sys.stderr)
            sys.exit(1)
    if args.buttons:
        try:
            card["buttons"] = json.loads(args.buttons)
        except json.JSONDecodeError:
            print(f"错误: --buttons 不是有效的 JSON 数组: {args.buttons}", file=sys.stderr)
            sys.exit(1)

    # 流式模式
    if args.stream:
        msg_id = api(url, token, "/api/octop/stream_start", "POST",
                      json_body={"username": username, "text": card.get("text", "")})
        print(f"流式消息已创建: message_id={msg_id.get('message_id')}", file=sys.stderr)
        # 模拟流式追加
        text_chunks = card.get("text", "")
        for i in range(0, len(text_chunks), 10):
            chunk = text_chunks[i:i+10]
            if chunk:
                api(url, token, "/api/octop/stream_append", "POST",
                    json_body={"message_id": msg_id.get("message_id"), "text": chunk})
                time.sleep(0.1)
        api(url, token, "/api/octop/stream_end", "POST",
            json_body={"message_id": msg_id.get("message_id")})
        print(json.dumps({"ok": True, "message_id": msg_id.get("message_id")}))
        return

    # 普通推送
    body = {"token": token, "username": username, "card": card}
    result = api(url, token, "/api/octop/receive", "POST", json_body=body)
    print(json.dumps(result))


def cmd_stream_start(args):
    url, username, token = get_env()
    result = api(url, token, "/api/octop/stream_start", "POST",
                 json_body={"username": username, "text": args.text or ""})
    print(json.dumps(result))


def cmd_stream_append(args):
    url, username, token = get_env()
    result = api(url, token, "/api/octop/stream_append", "POST",
                 json_body={"message_id": args.message_id, "text": args.text or ""})
    print(json.dumps(result))


def cmd_stream_end(args):
    url, username, token = get_env()
    result = api(url, token, "/api/octop/stream_end", "POST",
                 json_body={"message_id": args.message_id})
    print(json.dumps(result))


def cmd_poll(args):
    url, username, token = get_env()
    # 长轮询：offset 为上次拉取的 update_id，timeout 为挂起秒数
    params = {
        "offset": args.offset,
        "timeout": args.timeout,
    }
    result = api(url, token, f"/api/octop/updates/{username}", "GET", params=params)
    print(json.dumps(result, ensure_ascii=False))


def cmd_status(args):
    url, username, token = get_env()
    result = api(url, token, "/api/octop/status", "GET")
    print(json.dumps(result, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description="Octop ↔ EchoLink 桥接")
    sub = parser.add_subparsers(dest="command", required=True)

    # push
    p_push = sub.add_parser("push", help="推送通知到 EchoLink")
    p_push.add_argument("--title", required=True)
    p_push.add_argument("--text", default="")
    p_push.add_argument("--poster", default="")
    p_push.add_argument("--details", default="", help='JSON 数组: [{"key":"k","value":"v"}]')
    p_push.add_argument("--buttons", default="", help='JSON 数组: [{"text":"t","callback_data":"cd"}]')
    p_push.add_argument("--stream", action="store_true", help="以流式方式推送")
    p_push.set_defaults(func=cmd_push)

    # stream_start
    p_ss = sub.add_parser("stream_start", help="创建流式消息")
    p_ss.add_argument("--text", default="")
    p_ss.set_defaults(func=cmd_stream_start)

    # stream_append
    p_sa = sub.add_parser("stream_append", help="追加流式消息")
    p_sa.add_argument("--message-id", type=int, required=True)
    p_sa.add_argument("--text", default="")
    p_sa.set_defaults(func=cmd_stream_append)

    # stream_end
    p_se = sub.add_parser("stream_end", help="结束流式消息")
    p_se.add_argument("--message-id", type=int, required=True)
    p_se.set_defaults(func=cmd_stream_end)

    # poll
    p_poll = sub.add_parser("poll", help="长轮询拉取用户指令")
    p_poll.add_argument("--offset", type=int, default=0)
    p_poll.add_argument("--timeout", type=int, default=30)
    p_poll.set_defaults(func=cmd_poll)

    # status
    p_st = sub.add_parser("status", help="查询通道状态")
    p_st.set_defaults(func=cmd_status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
