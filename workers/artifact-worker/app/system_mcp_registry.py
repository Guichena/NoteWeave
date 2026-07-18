from __future__ import annotations

import sys
from pathlib import Path

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
            launch_env={},
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
            ],
        )
    ]


def resolve_system_mcp_server(server_id: str) -> CustomMcpServerRegistration:
    normalized = server_id.strip().lower()
    for server in list_system_mcp_servers():
        if server.server_id == normalized:
            return server
    raise ValueError(f"system MCP server not found: {server_id}")
