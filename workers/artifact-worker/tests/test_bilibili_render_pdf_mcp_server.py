from __future__ import annotations

import json
import hashlib
import subprocess
from pathlib import Path
from types import SimpleNamespace

import anyio
import pytest
from mcp import ClientSession
from mcp.shared.memory import create_client_server_memory_streams
from PIL import Image

import mcp_servers.bilibili_render_pdf_server as mcp_module
from mcp_servers.bilibili_render_pdf_server import BilibiliRenderPdfServer
from app.video_frame_capture import CapturedFrame, CaptureResult


def _sandboxed_server(root: Path) -> BilibiliRenderPdfServer:
    server = BilibiliRenderPdfServer()
    server.allow_portable_pdf_fallback = True
    server.sandbox_root = root.resolve()
    server.input_root = server.sandbox_root
    server.output_root = server.sandbox_root
    return server


def _dispatch(server: BilibiliRenderPdfServer, message: dict) -> dict:
    """经官方 MCP SDK 的内存会话（真实握手与协议编解码）发送一条请求，返回 JSON-RPC 形状的结果。"""

    async def run() -> dict:
        sdk_server = server.build_server()
        async with create_client_server_memory_streams() as (client_streams, server_streams):
            async with anyio.create_task_group() as task_group:
                task_group.start_soon(
                    sdk_server.run,
                    server_streams[0],
                    server_streams[1],
                    sdk_server.create_initialization_options(),
                )
                async with ClientSession(*client_streams) as session:
                    initialized = await session.initialize()
                    method = message["method"]
                    params = message.get("params") or {}
                    if method == "initialize":
                        result = initialized
                    elif method == "tools/list":
                        result = await session.list_tools()
                    elif method == "tools/call":
                        result = await session.call_tool(params["name"], params.get("arguments"))
                    else:
                        raise AssertionError(f"unsupported test method: {method}")
                task_group.cancel_scope.cancel()
        return {"result": result.model_dump(mode="json", by_alias=True, exclude_none=True)}

    return anyio.run(run)


def _error_text(response: dict) -> str:
    assert response["result"]["isError"] is True
    return response["result"]["content"][0]["text"]


def test_initialize_should_expose_mcp_server_info() -> None:
    server = BilibiliRenderPdfServer()

    response = _dispatch(server, 
        {
            "jsonrpc": "2.0",
            "id": "init",
            "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {}},
        }
    )

    assert response is not None
    assert response["result"]["protocolVersion"]
    assert response["result"]["serverInfo"]["name"] == "bilibili-render-pdf"
    assert response["result"]["capabilities"]["tools"]["listChanged"] is False


def test_tools_list_should_expose_expected_skill_surface() -> None:
    server = BilibiliRenderPdfServer()

    response = _dispatch(server, 
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/list",
            "params": {},
        }
    )

    tools = response["result"]["tools"]
    names = [tool["name"] for tool in tools]

    assert "get_bilibili_subtitle" in names
    assert "transcribe_local_audio" in names
    assert "render_latex_pdf" in names
    assert "capture_bilibili_frames" in names
    assert "analyze_frame" in names
    assert "analyze_frames" in names


def test_analyze_frame_mcp_passes_only_frozen_frame_identity(tmp_path, monkeypatch) -> None:
    server = _sandboxed_server(tmp_path)
    observed = {}

    def fake_observe(**kwargs):
        observed.update(kwargs)
        return {"schema_version": "frame-observation-v1", "observations": []}

    monkeypatch.setattr(mcp_module, "observe_staged_frame", fake_observe)
    response = _dispatch(server, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "analyze_frame", "arguments": {
            "task_id": "task-1", "file_id": "frame-1", "checksum_sha256": "a" * 64,
        }},
    })
    assert response["result"]["isError"] is False
    assert observed == {
        "sandbox_root": tmp_path.resolve(), "task_id": "task-1",
        "file_id": "frame-1", "checksum_sha256": "a" * 64,
    }


def test_analyze_frames_mcp_reports_one_receipt_for_exact_manifest(tmp_path, monkeypatch) -> None:
    server = _sandboxed_server(tmp_path)
    seen = []

    def fake_observe(**kwargs):
        seen.append(kwargs)
        return {"schema_version": "frame-observation-v1",
                "file_id": kwargs["file_id"], "observations": []}

    monkeypatch.setattr(mcp_module, "observe_staged_frame", fake_observe)
    result = server._analyze_frames({"task_id": "task-1", "files": [
        {"file_id": "frame-1", "checksum_sha256": "a" * 64},
        {"file_id": "frame-2", "checksum_sha256": "b" * 64},
    ]})
    assert result["schema_version"] == "frame-observation-batch-v1"
    assert [item["file_id"] for item in result["frames"]] == ["frame-1", "frame-2"]
    assert all(item["stage"] == "CAPTURED" for item in seen)
    with pytest.raises(ValueError, match="duplicated"):
        server._analyze_frames({"task_id": "task-1", "files": [
            {"file_id": "frame-1", "checksum_sha256": "a" * 64},
            {"file_id": "frame-1", "checksum_sha256": "a" * 64},
        ]})


def test_capture_bilibili_frames_keeps_part_identity_and_file_manifest(
        tmp_path: Path, monkeypatch) -> None:
    server = _sandboxed_server(tmp_path)
    server._ensure_yt_dlp_available = lambda: None
    server._fetch_video_metadata = lambda url, cookies_file="": {
        "id": "BV1234567890", "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=2",
        "duration": 60,
    }
    observed = {}

    def fake_download(command, **kwargs):
        observed["command"] = command
        (Path(kwargs["cwd"]) / "video.mp4").write_bytes(b"local test video")
        return subprocess.CompletedProcess(command, 0, "", "")

    def fake_capture(**kwargs):
        observed["capture"] = kwargs
        path = kwargs["output_dir"] / "frame-2-000.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (2, 2), "red").save(path, format="PNG")
        content = path.read_bytes()
        return CaptureResult((CapturedFrame(
            "f-2-000", 2, 1000, "frame-2-000", hashlib.sha256(content).hexdigest(),
            len(content), path,
        ),), (30_000,))

    monkeypatch.setattr(mcp_module.subprocess, "run", fake_download)
    monkeypatch.setattr(mcp_module, "capture_local_video", fake_capture)
    result = server._capture_bilibili_frames({
        "video_url": "https://www.bilibili.com/video/BV1234567890?p=2",
        "interval_ms": 30_000, "max_frames": 4,
    })
    assert result["normalized_video_id"] == "BV1234567890"
    assert result["part"] == 2
    assert result["frames"][0]["at_ms"] == 1000
    assert result["files"][0]["checksum_sha256"] == result["frames"][0]["checksum_sha256"]
    assert result["coverage_gaps"] == ["FRAME_CAPTURE_PARTIAL"]
    assert observed["capture"]["sandbox_root"] == tmp_path.resolve()
    assert "--no-playlist" in observed["command"]

    server._fetch_video_metadata = lambda url, cookies_file="": {
        "id": "BV1234567890", "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=1",
        "duration": 60,
    }
    with pytest.raises(ValueError, match="requested part"):
        server._capture_bilibili_frames({
            "video_url": "https://www.bilibili.com/video/BV1234567890?p=2",
        })


def test_pdf_renderer_embeds_verified_frame_and_rejects_wrong_part_or_digest(
        tmp_path: Path, monkeypatch) -> None:
    server = _sandboxed_server(tmp_path)
    image_path = tmp_path / "frame.png"
    Image.new("RGB", (64, 32), "blue").save(image_path, format="PNG")
    digest = hashlib.sha256(image_path.read_bytes()).hexdigest()
    ref = {"file_id": "frame-2-001", "path": str(image_path),
           "checksum_sha256": digest, "part": 2, "at_ms": 1200}
    arguments = {
        "title": "Frame Evidence", "video_part": 2, "video_duration_ms": 5000,
        "output_dir": str(tmp_path / "exports"), "output_stem": "frame-evidence",
        "sections": [{"heading": "Concept", "body": "Verified explanation",
                      "image_refs": [ref]}],
    }
    monkeypatch.setattr(mcp_module.shutil, "which", lambda name: None)
    result = server._render_latex_pdf(arguments)
    pdf = Path(result["pdf_path"]).read_bytes()
    tex = Path(result["tex_path"]).read_text(encoding="utf-8")
    assert pdf.startswith(b"%PDF-") and b"/Subtype /Image" in pdf
    assert r"\includegraphics" in tex and "frame-2-001" in tex
    assert (tmp_path / "exports" / "evidence" / "frame-2-001.png").is_file()

    wrong_part = {**ref, "part": 1}
    with pytest.raises(ValueError, match="part or time"):
        server._render_latex_pdf({**arguments, "sections": [
            {"heading": "Concept", "body": "Verified explanation", "image_refs": [wrong_part]}]})
    wrong_digest = {**ref, "checksum_sha256": "0" * 64}
    with pytest.raises(ValueError, match="do not match"):
        server._render_latex_pdf({**arguments, "sections": [
            {"heading": "Concept", "body": "Verified explanation", "image_refs": [wrong_digest]}]})


def test_get_bilibili_subtitle_should_fetch_remote_subtitle_artifact(tmp_path: Path) -> None:
    server = _sandboxed_server(tmp_path)
    subtitle_dir = tmp_path / "manual"
    subtitle_dir.mkdir(parents=True, exist_ok=True)
    subtitle_path = subtitle_dir / "BV1NoteWeaveDemo.zh-Hans.srt"
    subtitle_path.write_text(
        "1\n00:00:00,000 --> 00:00:02,000\n测试字幕第一句\n\n2\n00:00:02,000 --> 00:00:04,000\n测试字幕第二句\n",
        encoding="utf-8",
    )

    server._ensure_yt_dlp_available = lambda: None
    server._fetch_video_metadata = lambda video_url, cookies_file="": {
        "id": "BV1NoteWeaveDemo",
        "title": "Demo Course",
        "subtitle_languages": ["zh-Hans"],
        "automatic_caption_languages": [],
    }
    server._download_subtitles = lambda *args, **kwargs: {
        "subtitle_files": [str(subtitle_path)],
        "selected_subtitle_path": str(subtitle_path),
        "selected_language": "zh-Hans",
        "fetch_command": ["python", "-m", "yt_dlp"],
        "fetch_stdout": "downloaded",
        "fetch_stderr": "",
        "fetch_exit_code": 0,
    }

    response = _dispatch(server, 
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "get_bilibili_subtitle",
                "arguments": {
                    "video_url": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    "output_dir": str(tmp_path),
                },
            },
        }
    )

    result = response["result"]["structuredContent"]
    assert result["source_platform"] == "bilibili"
    assert result["acquisition_mode"] == "remote_cc_subtitle_fetch"
    assert result["normalized_video_id"] == "BV1NoteWeaveDemo"
    assert result["selected_subtitle_path"] == str(subtitle_path)
    assert result["selected_language"] == "zh-Hans"
    assert result["subtitle_preview"][0] == "测试字幕第一句"
    assert Path(result["metadata_path"]).exists()


def test_get_bilibili_subtitle_should_fallback_to_transcription_when_subtitles_missing(
    tmp_path: Path,
) -> None:
    server = _sandboxed_server(tmp_path)
    audio_path = tmp_path / "audio" / "audio.wav"
    audio_path.parent.mkdir(parents=True, exist_ok=True)
    audio_path.write_bytes(b"fake-audio")

    generated_srt = tmp_path / "transcripts" / "audio.srt"
    generated_srt.parent.mkdir(parents=True, exist_ok=True)
    generated_srt.write_text(
        "1\n00:00:00,000 --> 00:00:02,000\n转写结果第一句\n",
        encoding="utf-8",
    )

    server._ensure_yt_dlp_available = lambda: None
    server._fetch_video_metadata = lambda video_url, cookies_file="": {
        "id": "BV1NoteWeaveDemo",
        "title": "Demo Course",
        "subtitle_languages": [],
        "automatic_caption_languages": [],
    }

    call_counter = {"count": 0}

    def fake_download_subtitles(*args, **kwargs):
        call_counter["count"] += 1
        return {
            "subtitle_files": [],
            "selected_subtitle_path": "",
            "selected_language": "",
            "fetch_command": ["python", "-m", "yt_dlp"],
            "fetch_stdout": "",
            "fetch_stderr": "",
            "fetch_exit_code": 0,
        }

    server._download_subtitles = fake_download_subtitles
    server._download_audio_for_transcription = lambda *args, **kwargs: (
        audio_path,
        {
            "fetch_command": ["python", "-m", "yt_dlp"],
            "fetch_stdout": "downloaded-audio",
            "fetch_stderr": "",
            "fetch_exit_code": 0,
            "audio_files": [str(audio_path)],
        },
    )
    server._run_transcription = lambda **kwargs: {
        "input_path": str(audio_path),
        "output_dir": str(tmp_path / "transcripts"),
        "execution_mode": "delegated_skill_script_in_mcp_conda_env",
        "command": ["python", "transcribe_local_audio.py"],
        "exit_code": 0,
        "stdout": "ok",
        "stderr": "",
        "artifacts": {
            "srt_files": [str(generated_srt)],
            "txt_files": [],
            "json_files": [],
        },
        "environment_notes": ["Using the MCP server's active conda environment."],
        "ok": True,
    }

    response = _dispatch(server, 
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "get_bilibili_subtitle",
                "arguments": {
                    "video_url": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    "output_dir": str(tmp_path),
                },
            },
        }
    )

    result = response["result"]["structuredContent"]
    assert call_counter["count"] == 2
    assert result["acquisition_mode"] == "remote_audio_transcription_fallback"
    assert result["audio_artifact_path"] == str(audio_path)
    assert result["selected_subtitle_path"] == str(generated_srt)
    assert result["subtitle_preview"][0] == "转写结果第一句"
    assert result["transcription"]["ok"] is True


def test_render_latex_pdf_should_write_tex_and_real_pdf_artifacts(tmp_path: Path) -> None:
    server = _sandboxed_server(tmp_path)

    response = _dispatch(server, 
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {
                "name": "render_latex_pdf",
                "arguments": {
                    "title": "B站课程笔记",
                    "video_url": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    "output_dir": str(tmp_path),
                    "output_stem": "demo-course-notes",
                    "sections": [
                        {"heading": "课程概览", "body": "这一节介绍核心主题。"},
                        {"heading": "关键机制", "body": "这一节展开系统链路。"},
                    ],
                },
            },
        }
    )

    result = response["result"]["structuredContent"]
    tex_path = Path(result["tex_path"])
    pdf_path = Path(result["pdf_path"])

    assert tex_path.exists()
    tex_text = tex_path.read_text(encoding="utf-8")
    assert "\\section{课程概览}" in tex_text
    assert "\\section{关键机制}" in tex_text
    assert result["pdf_status"] == "COMPILED"
    assert pdf_path.exists()
    assert pdf_path.read_bytes().startswith(b"%PDF-1.")


def test_fallback_template_should_replace_metadata_and_insert_body_before_document_end(
    tmp_path: Path,
) -> None:
    server = _sandboxed_server(tmp_path)
    server.template_path = tmp_path / "missing-template.tex"

    latex = server._build_latex_document(
        title="B站课程讲义验证",
        video_url="https://www.bilibili.com/video/BV1NoteWeaveDemo",
        video_channel="测试频道",
        video_publish_date="2026-08-19",
        video_duration="12:34",
        cover_image_path="",
        sections=[{"heading": "课程概览", "body": "验证中文正文。"}],
    )

    assert r"\newcommand{\notetitle}{B站课程讲义验证}" in latex
    assert r"\newcommand{\videochannel}{测试频道}" in latex
    assert r"\section{课程概览}" in latex
    assert latex.index(r"\section{课程概览}") < latex.index(r"\end{document}")


def test_render_latex_pdf_should_run_xelatex_twice_for_toc_and_references(
    tmp_path: Path,
    monkeypatch,
) -> None:
    server = _sandboxed_server(tmp_path)
    server.allow_portable_pdf_fallback = False
    server.template_path = tmp_path / "missing-template.tex"
    calls: list[list[str]] = []

    monkeypatch.setattr(
        "mcp_servers.bilibili_render_pdf_server.shutil.which",
        lambda executable: "/usr/bin/xelatex" if executable == "xelatex" else None,
    )

    def fake_run(command, *, cwd, **kwargs):
        calls.append(command)
        (Path(cwd) / "two-pass.pdf").write_bytes(b"%PDF-1.7\n")
        return SimpleNamespace(returncode=0, stdout=f"pass {len(calls)}", stderr="")

    monkeypatch.setattr("mcp_servers.bilibili_render_pdf_server.subprocess.run", fake_run)

    result = server._render_latex_pdf(
        {
            "title": "两遍编译验证",
            "output_dir": str(tmp_path),
            "output_stem": "two-pass",
            "sections": [{"heading": "课程概览", "body": "验证目录。"}],
        }
    )

    assert len(calls) == 2
    assert result["execution_mode"] == "controlled_xelatex_render"
    assert result["compile_message"] == "pass 2"


def test_transcribe_local_audio_should_fail_cleanly_when_skill_env_missing(
    tmp_path: Path,
    monkeypatch,
) -> None:
    server = _sandboxed_server(tmp_path)
    audio_path = tmp_path / "sample.mp3"
    audio_path.write_bytes(b"fake-audio")
    # 转写脚本存在、但专用 Python 环境缺失：应提示环境安装脚本，而不依赖本机是否装过 Codex skill。
    server.transcribe_script = tmp_path / "transcribe_local_audio.py"
    server.transcribe_script.write_text("", encoding="utf-8")

    original_python = server.transcribe_venv_python
    server.transcribe_venv_python = tmp_path / "missing-python.exe"
    monkeypatch.setattr(
        "mcp_servers.bilibili_render_pdf_server.importlib.util.find_spec",
        lambda _name: None,
    )
    monkeypatch.setattr(server, "_looks_like_conda_env", lambda _path: False)
    try:
        response = _dispatch(server, 
            {
                "jsonrpc": "2.0",
                "id": 5,
                "method": "tools/call",
                "params": {
                    "name": "transcribe_local_audio",
                    "arguments": {"input_path": str(audio_path)},
                },
            }
        )
    finally:
        server.transcribe_venv_python = original_python

    message = _error_text(response)
    assert (
        "setup_bilibili_render_pdf_mcp_env.ps1" in message
        or "setup_audio_env.ps1" in message
    )


def test_transcribe_local_audio_should_use_bundled_runtime_without_codex_skill(
    tmp_path: Path,
    monkeypatch,
) -> None:
    server = _sandboxed_server(tmp_path)
    audio_path = tmp_path / "sample.mp3"
    audio_path.write_bytes(b"fake-audio")
    server.transcribe_script = tmp_path / "missing-codex-skill-script.py"
    monkeypatch.setattr(
        "mcp_servers.bilibili_render_pdf_server.importlib.util.find_spec",
        lambda name: object() if name == "faster_whisper" else None,
    )
    server._run_transcription = lambda **kwargs: {
        "input_path": str(kwargs["input_path"]),
        "execution_mode": "bundled_faster_whisper",
        "artifacts": {"srt_files": [], "json_files": []},
        "ok": True,
    }

    result = server._transcribe_local_audio({"input_path": str(audio_path)})

    assert result["execution_mode"] == "bundled_faster_whisper"


def test_tool_call_should_reject_arguments_outside_input_schema() -> None:
    server = BilibiliRenderPdfServer()

    missing = _dispatch(server, {
        "method": "tools/call",
        "params": {"name": "get_bilibili_subtitle", "arguments": {}},
    })
    unexpected = _dispatch(server, {
        "method": "tools/call",
        "params": {"name": "analyze_frame", "arguments": {
            "task_id": "task-1", "file_id": "frame-1", "checksum_sha256": "a" * 64, "path": "/etc/passwd",
        }},
    })

    assert "video_url" in _error_text(missing)
    assert "path" in _error_text(unexpected)


def test_local_paths_outside_mcp_sandbox_should_be_rejected(tmp_path: Path) -> None:
    server = _sandboxed_server(tmp_path / "sandbox")
    outside = tmp_path / "outside.mp3"
    outside.write_bytes(b"fake-audio")

    response = _dispatch(server, {
        "jsonrpc": "2.0",
        "id": "outside",
        "method": "tools/call",
        "params": {
            "name": "transcribe_local_audio",
            "arguments": {"input_path": str(outside.resolve())},
        },
    })

    assert "must stay inside" in _error_text(response)
