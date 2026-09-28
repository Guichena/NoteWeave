from __future__ import annotations

import sys
from pathlib import Path

from app.config import resolve_mcp_sandbox_root
from app.models import CustomMcpServerRegistration, CustomMcpToolRegistration


SYSTEM_BILIBILI_SERVER_ID = "builtin-bilibili-mcp"


def list_system_mcp_servers() -> list[CustomMcpServerRegistration]:
    worker_root = Path(__file__).resolve().parents[1]
    server_script = worker_root / "mcp" / "bilibili_render_pdf_server.py"
    return [
        CustomMcpServerRegistration(
            server_id=SYSTEM_BILIBILI_SERVER_ID,
            display_name="System Bilibili Render PDF MCP",
            endpoint_kind="mcp",
            launch_transport="stdio",
            launch_command=sys.executable,
            launch_args=[str(server_script)],
            working_directory=str(worker_root),
            launch_env={"NOTEWEAVE_MCP_SANDBOX_ROOT": str(resolve_mcp_sandbox_root())},
            registration_origin="SYSTEM",
            server_notes=[
                "System-managed MCP provider; not configurable from the product UI.",
                "The provider is executed asynchronously and reports completion through the Java host callback.",
            ],
            tools=[
                CustomMcpToolRegistration(
                    capability_name="EXTRACT_TRANSCRIPT",
                    tool_name="get_subtitle",
                    supported_routes=["VIDEO_URL"],
                    supported_actions=["VIDEO_SUMMARY", "COURSE_NOTES"],
                    preference_rank=200,
                    selection_reason_hint="system_bilibili_subtitle",
                ),
                CustomMcpToolRegistration(
                    capability_name="CAPTURE_VIDEO_FRAMES",
                    tool_name="capture_bilibili_frames",
                    supported_routes=["VIDEO_URL"],
                    supported_actions=["COURSE_NOTES"],
                    preference_rank=200,
                    selection_reason_hint="system_bilibili_frame_capture",
                ),
                CustomMcpToolRegistration(
                    capability_name="ANALYZE_FRAME",
                    tool_name="analyze_frames",
                    supported_routes=["VIDEO_URL"],
                    supported_actions=["COURSE_NOTES"],
                    preference_rank=200,
                    selection_reason_hint="system_bilibili_frame_observation",
                ),
            ],
        )
    ]


def resolve_system_mcp_server(server_id: str) -> CustomMcpServerRegistration:
    normalized = server_id.strip().lower()
    for server in list_system_mcp_servers():
        if server.server_id == normalized:
            return server
    raise ValueError(f"system MCP server not found: {server_id}")
