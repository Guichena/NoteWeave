"""通过 Streamable HTTP 调用独立音视频解析服务。"""
from __future__ import annotations

import asyncio
import json
import os
from datetime import timedelta

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def _call(tool_name: str, arguments: dict) -> dict:
    url = os.environ.get("NOTEWEAVE_MEDIA_MCP_URL", "http://127.0.0.1:18094/mcp")
    token = os.environ.get("NOTEWEAVE_MEDIA_MCP_AUTH_TOKEN", "")
    timeout = int(os.environ.get("NOTEWEAVE_MEDIA_MCP_TIMEOUT_SECONDS", "3660"))
    if not token:
        raise ValueError("媒体服务内部令牌未配置")
    async with httpx.AsyncClient(headers={"X-NoteWeave-Internal-Token": token},
                                timeout=httpx.Timeout(timeout, connect=10), follow_redirects=False) as client:
        async with streamable_http_client(url, http_client=client) as (read, write, _):
            async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=timeout)) as session:
                await session.initialize()
                result = await session.call_tool(tool_name, arguments)
                if result.isError:
                    message = "；".join(block.text for block in result.content if block.type == "text")
                    raise ValueError(message[:1000] or "媒体解析失败")
                data = result.structuredContent
                if data is None:
                    data = next((json.loads(block.text) for block in result.content if block.type == "text"), None)
                if not isinstance(data, dict):
                    raise ValueError("媒体服务未返回图文文档对象")
                return data


def call_media_tool(tool_name: str, arguments: dict) -> dict:
    if tool_name not in {"get_video_info", "parse_video", "parse_media"}:
        raise ValueError("不支持的媒体解析工具")
    return asyncio.run(_call(tool_name, arguments))
