from __future__ import annotations

import json
import os
import subprocess
import threading
import uuid
from pathlib import Path

from app.acquisition_runtime import (
    acknowledge_acquisition_operation,
    dispatch_acquisition_operation,
    peek_acquisition_operation,
)
from app.registry import list_custom_mcp_servers
from app.config import load_settings
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
            error_message=str(exc),
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

    init_request_id = f"init-{uuid.uuid4().hex}"
    call_request_id = f"call-{uuid.uuid4().hex}"
    init_message = {
        "jsonrpc": "2.0",
        "id": init_request_id,
        "method": "initialize",
        "params": {"protocolVersion": "2025-06-18"},
    }
    tool_call_message = {
        "jsonrpc": "2.0",
        "id": call_request_id,
        "method": "tools/call",
        "params": {
            "name": str(operation.get("tool_name", "")),
            "arguments": _build_tool_arguments(operation),
        },
    }
    raw_input = "\n".join([json.dumps(init_message), json.dumps(tool_call_message)]) + "\n"
    env = {**os.environ, **server.launch_env}
    completed = subprocess.run(
        command,
        input=raw_input,
        cwd=working_directory,
        env=env or None,
        text=True,
        capture_output=True,
        check=False,
        timeout=load_settings().mcp_process_timeout_seconds,
    )
    if completed.returncode != 0:
        raise ValueError(completed.stderr.strip() or completed.stdout.strip() or "custom MCP process failed")
    responses = []
    for raw_line in completed.stdout.splitlines():
        stripped = raw_line.strip()
        if not stripped:
            continue
        responses.append(json.loads(stripped))
    tool_response = next(
        (item for item in responses if str(item.get("id", "")) == call_request_id),
        None,
    )
    if tool_response is None:
        raise ValueError("custom MCP tool call returned no matching response")
    if "error" in tool_response:
        error = tool_response["error"]
        raise ValueError(str(error.get("message") or "custom MCP tool call failed"))
    result = tool_response.get("result") or {}
    if result.get("isError"):
        raise ValueError("custom MCP tool call returned isError=true")
    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        validate_json_payload_size(structured, label="MCP structured content")
        return structured
    content = result.get("content") or []
    if content:
        text = str(content[0].get("text", "")).strip()
        if text:
            payload = json.loads(text)
            if not isinstance(payload, dict):
                raise ValueError("custom MCP tool content must decode to an object")
            validate_json_payload_size(payload, label="MCP text content")
            return payload
    raise ValueError("custom MCP tool call returned no structured content")


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
