from __future__ import annotations

import asyncio
import concurrent.futures
import json
import os
import tempfile
import threading
from datetime import timedelta
from pathlib import Path
from typing import Any

import anyio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from app.acquisition_runtime import (
    acknowledge_acquisition_operation,
    dispatch_acquisition_operation,
    peek_acquisition_operation,
)
from app.registry import list_custom_mcp_servers
from app.config import load_settings
from app.error_sanitizer import sanitize_error_message
from app.io_limits import validate_json_payload_size


_executor_threads_lock = threading.Lock()
_executor_threads: dict[str, threading.Thread] = {}


def submit_custom_mcp_acquisition_operation(request_id: str) -> dict[str, object]:
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    if str(operation.get("server_id", "")).strip() not in {
        server.server_id for server in list_custom_mcp_servers()
    }:
        raise ValueError(f"acquisition operation is not bound to a registered custom MCP server: {request_id}")

    dispatch_response = dispatch_acquisition_operation(request_id)
    thread = threading.Thread(
        target=_run_custom_mcp_acquisition_operation,
        args=(request_id,),
        name=f"custom-mcp-acq-{request_id}",
        daemon=True,
    )
    with _executor_threads_lock:
        _executor_threads[request_id] = thread
    thread.start()
    return {
        **dispatch_response,
        "execution_mode": "CUSTOM_MCP_ASYNC_RUNTIME",
    }


def wait_for_custom_mcp_operation(request_id: str, timeout: float = 30.0) -> dict[str, object]:
    with _executor_threads_lock:
        thread = _executor_threads.get(request_id)
    if thread is not None:
        thread.join(timeout=timeout)
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    return operation


def _run_custom_mcp_acquisition_operation(request_id: str) -> None:
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        return
    callback_token = str(operation.get("callback_token", ""))
    try:
        server = _resolve_custom_server(str(operation.get("server_id", "")))
        provider_payload = _call_custom_mcp_tool(server, operation)
        acknowledge_acquisition_operation(
            callback_token=callback_token,
            final_status="ACKNOWLEDGED",
            provider_payload=provider_payload,
        )
    except Exception as exc:  # pragma: no cover - guarded by tests through runtime state
        acknowledge_acquisition_operation(
            callback_token=callback_token,
            final_status="FAILED",
            error_code="CUSTOM_MCP_EXECUTION_FAILED",
            error_message=sanitize_error_message(str(exc)),
        )
    finally:
        with _executor_threads_lock:
            _executor_threads.pop(request_id, None)


def _resolve_custom_server(server_id: str) -> object:
    normalized_server_id = server_id.strip().lower()
    for server in list_custom_mcp_servers():
        if server.server_id == normalized_server_id:
            return server
    raise ValueError(f"custom MCP server not found: {server_id}")


def _call_custom_mcp_tool(server: object, operation: dict[str, object]) -> dict[str, object]:
    if str(server.launch_transport).strip().lower() != "stdio":
        raise ValueError(f"unsupported custom MCP transport: {server.launch_transport}")
    command = [str(server.launch_command), *[str(arg) for arg in server.launch_args]]
    if not command[0]:
        raise ValueError(f"custom MCP server launch command is missing: {server.server_id}")
    working_directory = str(server.working_directory or "").strip()
    if not working_directory:
        raise ValueError(f"custom MCP server working directory is missing: {server.server_id}")

    tool_name = str(operation.get("tool_name", ""))
    arguments = _build_tool_arguments(operation)
    parameters = StdioServerParameters(
        command=command[0],
        args=command[1:],
        env={**os.environ, **server.launch_env},
        cwd=working_directory,
        encoding="utf-8",
        encoding_error_handler="replace",
    )
    timeout_seconds = load_settings().mcp_process_timeout_seconds
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as stderr_log:
        try:
            result = _run_blocking(
                lambda: _call_tool_over_stdio(parameters, tool_name, arguments, timeout_seconds, stderr_log)
            )
        except Exception as exc:
            stderr_log.seek(0)
            stderr_tail = stderr_log.read()[-2000:].strip()
            raise ValueError(stderr_tail or str(exc) or "custom MCP process failed") from exc
    if result.isError:
        message = next((item.text for item in result.content if getattr(item, "type", "") == "text"), "")
        raise ValueError(message.strip() or "custom MCP tool call returned isError=true")
    structured = result.structuredContent
    if isinstance(structured, dict):
        validate_json_payload_size(structured, label="MCP structured content")
        return structured
    text = next((item.text for item in result.content if getattr(item, "type", "") == "text"), "").strip()
    if text:
        payload = json.loads(text)
        if not isinstance(payload, dict):
            raise ValueError("custom MCP tool content must decode to an object")
        validate_json_payload_size(payload, label="MCP text content")
        return payload
    raise ValueError("custom MCP tool call returned no structured content")


async def _call_tool_over_stdio(
    parameters: StdioServerParameters,
    tool_name: str,
    arguments: dict[str, Any],
    timeout_seconds: float,
    stderr_log: Any,
) -> Any:
    """用官方 MCP SDK 启动 stdio 服务端，完成握手后调用一次工具；整个会话受进程超时约束。"""
    with anyio.fail_after(timeout_seconds):
        async with stdio_client(parameters, errlog=stderr_log) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                return await session.call_tool(
                    tool_name, arguments, read_timeout_seconds=timedelta(seconds=timeout_seconds)
                )


def _run_blocking(coroutine_factory: Any) -> Any:
    """在同步调用方里执行协程；调用方线程已有事件循环时，换到独立线程执行。"""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return anyio.run(coroutine_factory)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(anyio.run, coroutine_factory).result()


def _build_tool_arguments(operation: dict[str, object]) -> dict[str, object]:
    explicit_arguments = operation.get("tool_arguments")
    if isinstance(explicit_arguments, dict):
        return dict(explicit_arguments)
    input_locator = str(operation.get("input_locator", "")).strip()
    tool_name = str(operation.get("tool_name", "")).strip()
    if tool_name == "get_bilibili_subtitle":
        return {
            "video_url": input_locator,
            "fallback_to_transcription": True,
            "allow_auto_subtitles": True,
        }
    if tool_name == "capture_bilibili_frames":
        return {"video_url": input_locator, "max_frames": 16, "interval_ms": 30_000}
    if tool_name == "transcribe_local_audio":
        return {
            "input_path": input_locator,
            "skip_existing": False,
            "json_output": True,
        }
    if tool_name == "render_latex_pdf":
        output_stem = Path(input_locator).stem if input_locator else "artifact"
        return {
            "title": output_stem,
            "sections": [
                {
                    "heading": "Generated Artifact",
                    "body": "Controlled export task prepared by Artifact Runtime.",
                }
            ],
            "output_stem": output_stem,
        }
    raise ValueError(f"unsupported custom MCP acquisition tool: {tool_name}")
