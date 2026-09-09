import json
from typing import Any, List, Dict, Tuple, Optional

from app.core.event import eventmanager, Event
from app.log import logger
from app.plugins import _PluginBase
from app.schemas.types import EventType
from app.utils.http import RequestUtils


class echolink(_PluginBase):
    # 插件名称
    plugin_name = "EchoLink"
    # 插件描述
    plugin_desc = "通过 EchoLink 接收 MoviePilot 通知并远程控制，支持富文本卡片和交互按钮"
    # 插件版本
    plugin_version = "1.0.0"
    # 插件作者
    plugin_author = "gybeyond"
    # 作者主页
    author_url = "https://github.com/gybeyond1"
    # 插件配置项ID前缀
    plugin_config_prefix = "echolink_"
    # 加载顺序
    plugin_order = 10
    # 可使用的用户级别
    auth_level = 1

    # 私有属性
    _enabled = False
    _echolink_url = ""
    _echolink_username = ""
    _echolink_token = ""

    def init_plugin(self, config: dict = None):
        if config:
            self._enabled = config.get("enabled", False)
            self._echolink_url = (config.get("echolink_url") or "").rstrip("/")
            self._echolink_username = config.get("echolink_username", "")
            self._echolink_token = config.get("echolink_token", "")

        if self._enabled and self._echolink_url and self._echolink_username and self._echolink_token:
            logger.info(f"EchoLink 插件已启用，推送地址: {self._echolink_url}/api/webhook/moviepilot/{self._echolink_username}")
        else:
            logger.info("EchoLink 插件未配置完整，通知推送已禁用")

    def get_state(self) -> bool:
        return self._enabled

    @staticmethod
    def get_command() -> List[Dict[str, Any]]:
        pass

    def get_api(self) -> List[Dict[str, Any]]:
        return [
            {
                "path": "/callback",
                "endpoint": self.callback,
                "methods": ["POST"],
                "summary": "EchoLink 按钮回调",
                "description": "接收 EchoLink 端用户点击交互按钮的回调"
            },
            {
                "path": "/message",
                "endpoint": self.message,
                "methods": ["POST"],
                "summary": "EchoLink 用户消息",
                "description": "接收 EchoLink 端用户发送的文字消息"
            }
        ]

    def get_form(self) -> Tuple[List[dict], Dict[str, Any]]:
        return [
            {
                'component': 'VForm',
                'content': [
                    {
                        'component': 'VRow',
                        'content': [
                            {
                                'component': 'VCol',
                                'props': {'cols': 12},
                                'content': [
                                    {
                                        'component': 'VSwitch',
                                        'props': {
                                            'model': 'enabled',
                                            'label': '启用插件',
                                        }
                                    }
                                ]
                            }
                        ]
                    },
                    {
                        'component': 'VRow',
                        'content': [
                            {
                                'component': 'VCol',
                                'props': {'cols': 12},
                                'content': [
                                    {
                                        'component': 'VTextField',
                                        'props': {
                                            'model': 'echolink_url',
                                            'label': 'EchoLink 服务器地址',
                                            'placeholder': 'http://192.168.1.100:3000',
                                            'hint': 'EchoLink 服务端的访问地址（内网地址即可）'
                                        }
                                    }
                                ]
                            }
                        ]
                    },
                    {
                        'component': 'VRow',
                        'content': [
                            {
                                'component': 'VCol',
                                'props': {'cols': 6},
                                'content': [
                                    {
                                        'component': 'VTextField',
                                        'props': {
                                            'model': 'echolink_username',
                                            'label': 'EchoLink 用户名',
                                            'placeholder': 'gybeyond'
                                        }
                                    }
                                ]
                            },
                            {
                                'component': 'VCol',
                                'props': {'cols': 6},
                                'content': [
                                    {
                                        'component': 'VTextField',
                                        'props': {
                                            'model': 'echolink_token',
                                            'label': 'EchoLink 通道 Token',
                                            'placeholder': '在 EchoLink 管理员页面生成',
                                            'password': True
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        ], {
            "enabled": self._enabled,
            "echolink_url": self._echolink_url,
            "echolink_username": self._echolink_username,
            "echolink_token": self._echolink_token
        }

    def get_page(self) -> List[dict]:
        pass

    def stop_service(self):
        pass

    # ===== 事件监听 =====

    @eventmanager.register(EventType.NoticeMessage)
    def on_notice(self, event: Event):
        if not self._enabled or not self._echolink_url or not self._echolink_username or not self._echolink_token:
            return

        event_data = event.event_data or {}
        if not event_data:
            return

        try:
            card = self._build_card(event_data)
            if not card:
                return

            payload = {
                "source": "moviepilot",
                "token": self._echolink_token,
                "card": card
            }

            url = f"{self._echolink_url}/api/webhook/moviepilot/{self._echolink_username}"
            headers = {
                "Content-Type": "application/json",
                "X-API-Token": self._echolink_token
            }

            resp = RequestUtils().post_res(url, json=payload, headers=headers, timeout=10)
            if resp and resp.status_code == 200:
                logger.info(f"EchoLink 推送成功: {card.get('title', '无标题')}")
            else:
                logger.error(f"EchoLink 推送失败: {resp.status_code if resp else 'unknown'} - {resp.text if resp else ''}")
        except Exception as e:
            logger.error(f"EchoLink 推送异常: {str(e)}")

    def _build_card(self, event_data: dict) -> dict:
        title = event_data.get("title", "") or (event_data.get("text", "") or "")[:50]
        if not title:
            title = "MoviePilot 通知"

        text = event_data.get("text", "")
        image = event_data.get("image", "")

        details = []
        for key in ["type", "channel", "source", "category", "year", "rating", "size", "status"]:
            if key in event_data and event_data[key]:
                label_map = {
                    "type": "类型", "channel": "渠道", "source": "来源",
                    "category": "分类", "year": "年份", "rating": "评分",
                    "size": "大小", "status": "状态"
                }
                details.append({"key": label_map.get(key, key), "value": str(event_data[key])})

        buttons = []
        if "buttons" in event_data and isinstance(event_data["buttons"], list):
            for b in event_data["buttons"]:
                if isinstance(b, dict) and "text" in b:
                    buttons.append({
                        "text": b["text"],
                        "callback_data": b.get("callback_data", b.get("url", ""))
                    })

        card = {"title": title, "text": text, "details": details, "buttons": buttons}
        if image:
            card["poster"] = image
        return card

    # ===== API 回调 =====

    def _parse_request_body(self, request: Any) -> dict:
        """从 MP 插件 API 的 request 对象解析请求体"""
        if request is None:
            return {}
        # Flask request 对象
        if hasattr(request, 'get_json'):
            try:
                return request.get_json(silent=True) or {}
            except Exception:
                return {}
        # 字典对象（MP 内部传递）
        if isinstance(request, dict):
            if 'json' in request and isinstance(request['json'], dict):
                return request['json']
            if 'body' in request:
                try:
                    return json.loads(request['body'])
                except Exception:
                    return {}
            return request
        # 字符串
        if isinstance(request, str):
            try:
                return json.loads(request)
            except Exception:
                return {}
        return {}

    def callback(self, apikey: str, request: Any):
        data = self._parse_request_body(request)
        username = data.get("username", "")
        callback_data = data.get("callback_data", "")
        message_id = data.get("message_id", "")

        logger.info(f"EchoLink 按钮回调: user={username}, callback={callback_data}, msg_id={message_id}")

        return {
            "code": 0,
            "message": "回调已接收",
            "data": {"username": username, "callback_data": callback_data, "message_id": message_id}
        }

    def message(self, apikey: str, request: Any):
        data = self._parse_request_body(request)
        username = data.get("username", "")
        text = data.get("text", "")

        logger.info(f"EchoLink 用户消息: user={username}, text={text}")

        if not text:
            return {"code": 1, "message": "消息内容为空"}

        # TODO: 调用 MP Agent API 处理用户消息
        # 目前先返回已接收，后续对接 Agent 对话

        return {
            "code": 0,
            "message": "消息已接收",
            "data": {"username": username, "text": text}
        }
