from __future__ import annotations

import hmac
import asyncio
import tempfile
import time
from pathlib import Path

from mcp.server.fastmcp import FastMCP
from starlette.responses import JSONResponse

from . import bilibili
from .config import Settings
from . import parser

settings = Settings.load()
mcp = FastMCP("NoteWeave Media", host="0.0.0.0", stateless_http=True, json_response=True)


@mcp.tool()
async def get_video_info(source_url: str) -> dict:
    """预览 B 站视频标题、时长和分 P 列表。"""
    with tempfile.TemporaryDirectory(prefix="noteweave-video-info-") as directory:
        return await asyncio.to_thread(bilibili.video_info, source_url, Path(directory),
                                       time.monotonic() + min(120, settings.timeout), settings)


@mcp.tool()
async def parse_video(source_url: str, parts: list[int]) -> dict:
    """将所选 B 站分 P 解析成带时间戳和关键帧的图文文档。"""
    return await asyncio.to_thread(parser.parse_video, source_url, parts, settings)


@mcp.tool()
async def parse_media(file_ref: str, title: str = "上传媒体") -> dict:
    """解析输入卷中已上传的音视频，file_ref 是卷内相对路径。"""
    return await asyncio.to_thread(parser.parse_media, file_ref, title, settings)


class AuthenticatedMediaApp:
    """仅保护媒体服务的入口，不改变 MCP 的会话与传输实现。"""
    def __init__(self):
        self.application = mcp.streamable_http_app()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            if scope["path"] == "/health":
                await JSONResponse({"status": "ok"})(scope, receive, send)
                return
            headers = dict(scope["headers"])
            supplied = headers.get(b"x-noteweave-internal-token", b"").decode("latin-1")
            if not settings.token or not hmac.compare_digest(supplied, settings.token):
                await JSONResponse({"detail": "媒体服务内部鉴权失败"}, status_code=401)(scope, receive, send)
                return
        await self.application(scope, receive, send)


app = AuthenticatedMediaApp()
