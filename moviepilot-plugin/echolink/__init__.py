import json
import requests
from typing import Any, Dict, List, Tuple

from app.core.event import eventmanager, Event
from app.core.config import settings
from app.plugins import _PluginBase
from app.schemas import Notification
from app.log import logger


class EchoLinkPlugin(_PluginBase):
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
        """
        初始化插件
        """
        if config:
            self._enabled = config.get("enabled", False)
            self._echolink_url = config.get("echolink_url", "").rstrip("/")
            self._echolink_username = config.get("echolink_username", "")
            self._echolink_token = config.get("echolink_token", "")

        if self._enabled and self._echolink_url and self._echolink_username and self._echolink_token:
            logger.info(f"EchoLink 插件已启用，推送地址: {self._echolink_url}/webhook/moviepilot/{self._echolink_username}")
        else:
            logger.info("EchoLink 插件未配置完整，通知推送已禁用")

    def get_state(self) -> bool:
        return self._enabled

    @staticmethod
    def get_command() -> List[Dict[str, Any]]:
        return []

    def get_api(self) -> List[Dict[str, Any]]:
        """
        注册插件 API 路由
        路由会被注册到 /api/v1/plugin/echolink/<path>
        """
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
                "description": "接收 EchoLink 端用户发送的文字消息，当作远程命令处理"
            }
        ]

    def get_form(self) -> Tuple[List[dict], Dict[str, Any]]:
        """
        拼装插件配置页面
        """
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
        return []

    def stop_service(self):
        pass

    # ===== 事件监听 =====

    @eventmanager.register(EventType.NoticeMessage)
    def on_notice(self, event: Event):
        """
        监听 MoviePilot 通知消息，推送到 EchoLink
        """
        if not self._enabled or not self._echolink_url or not self._echolink_username or not self._echolink_token:
            return

        event_data = event.event_data or {}
        if not event_data:
            return

        try:
            # 构建 EchoLink 卡片消息
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

            resp = requests.post(url, json=payload, headers=headers, timeout=10)
            if resp.status_code == 200:
                logger.info(f"EchoLink 推送成功: {card.get('title', '无标题')}")
            else:
                logger.error(f"EchoLink 推送失败: {resp.status_code} - {resp.text}")
        except Exception as e:
            logger.error(f"EchoLink 推送异常: {str(e)}")

    def _build_card(self, event_data: dict) -> dict:
        """
        将 MP 通知事件数据转换成 EchoLink 卡片格式
        """
        title = event_data.get("title", "") or event_data.get("text", "")[:50]
        if not title:
            title = "MoviePilot 通知"

        text = event_data.get("text", "")
        image = event_data.get("image", "")

        # 详情字段
        details = []
        for key in ["type", "channel", "source", "category", "year", "rating", "size", "status"]:
            if key in event_data and event_data[key]:
                label_map = {
                    "type": "类型",
                    "channel": "渠道",
                    "source": "来源",
                    "category": "分类",
                    "year": "年份",
                    "rating": "评分",
                    "size": "大小",
                    "status": "状态"
                }
                details.append({
                    "key": label_map.get(key, key),
                    "value": str(event_data[key])
                })

        # 交互按钮（如果有）
        buttons = []
        if "buttons" in event_data and isinstance(event_data["buttons"], list):
            for b in event_data["buttons"]:
                if isinstance(b, dict) and "text" in b:
                    buttons.append({
                        "text": b["text"],
                        "callback_data": b.get("callback_data", b.get("url", ""))
                    })

        card = {
            "title": title,
            "text": text,
            "details": details,
            "buttons": buttons
        }
        if image:
            card["poster"] = image

        return card

    # ===== API 回调 =====

    def callback(self, apikey: str, request: Any):
        """
        接收 EchoLink 端的按钮点击回调
        """
        data = request.get_json() if hasattr(request, 'get_json') else {}
        username = data.get("username", "")
        callback_data = data.get("callback_data", "")
        message_id = data.get("message_id", "")

        logger.info(f"EchoLink 按钮回调: user={username}, callback={callback_data}, msg_id={message_id}")

        # TODO: 根据 callback_data 执行对应的 MP 操作
        # 例如：订阅、搜索、下载等
        # 这里需要根据实际的 callback_data 格式来处理

        return {
            "code": 0,
            "message": "回调已接收",
            "data": {
                "username": username,
                "callback_data": callback_data,
                "message_id": message_id
            }
        }

    def message(self, apikey: str, request: Any):
        """
        接收 EchoLink 端用户发送的文字消息，当作远程命令处理
        """
        data = request.get_json() if hasattr(request, 'get_json') else {}
        username = data.get("username", "")
        text = data.get("text", "")

        logger.info(f"EchoLink 用户消息: user={username}, text={text}")

        if not text:
            return {"code": 1, "message": "消息内容为空"}

        # TODO: 将文字消息当作 MP 远程命令处理
        # 例如：/search 电影名、/download 链接等
        # 可以调用 MP 的命令处理模块

        return {
            "code": 0,
            "message": "消息已接收",
            "data": {
                "username": username,
                "text": text
            }
        }
