from __future__ import annotations

import json
from pathlib import Path

from mcp.bilibili_render_pdf_server import BilibiliRenderPdfServer


def test_initialize_should_expose_mcp_server_info() -> None:
    server = BilibiliRenderPdfServer()

    response = server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": "init",
            "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {}},
        }
    )

    assert response is not None
    assert response["result"]["protocolVersion"] == "2025-06-18"
    assert response["result"]["serverInfo"]["name"] == "bilibili-render-pdf"
    assert response["result"]["capabilities"]["tools"]["listChanged"] is False


def test_tools_list_should_expose_expected_skill_surface() -> None:
    server = BilibiliRenderPdfServer()

    response = server.handle_message(
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


def test_get_bilibili_subtitle_should_fetch_remote_subtitle_artifact(tmp_path: Path) -> None:
    server = BilibiliRenderPdfServer()
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

    response = server.handle_message(
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
    server = BilibiliRenderPdfServer()
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

    response = server.handle_message(
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
    server = BilibiliRenderPdfServer()

    response = server.handle_message(
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


def test_transcribe_local_audio_should_fail_cleanly_when_skill_env_missing(tmp_path: Path) -> None:
    server = BilibiliRenderPdfServer()
    audio_path = tmp_path / "sample.mp3"
    audio_path.write_bytes(b"fake-audio")

    original_python = server.transcribe_venv_python
    server.transcribe_venv_python = tmp_path / "missing-python.exe"
    try:
        response = server.handle_message(
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

    assert response is not None
    assert response["error"]["code"] == -32602
    assert (
        "setup_bilibili_render_pdf_mcp_env.ps1" in response["error"]["message"]
        or "setup_audio_env.ps1" in response["error"]["message"]
    )


def test_transcribe_local_audio_should_use_bundled_runtime_without_codex_skill(
    tmp_path: Path,
    monkeypatch,
) -> None:
    server = BilibiliRenderPdfServer()
    audio_path = tmp_path / "sample.mp3"
    audio_path.write_bytes(b"fake-audio")
    server.transcribe_script = tmp_path / "missing-codex-skill-script.py"
    monkeypatch.setattr(
        "mcp.bilibili_render_pdf_server.importlib.util.find_spec",
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


def test_handle_line_should_support_json_array_batch() -> None:
    server = BilibiliRenderPdfServer()
    payload = json.dumps(
        [
            {"jsonrpc": "2.0", "id": "a", "method": "ping"},
            {"jsonrpc": "2.0", "id": "b", "method": "tools/list", "params": {}},
        ],
        ensure_ascii=False,
    )

    responses = server.handle_line(payload)

    assert len(responses) == 2
    assert responses[0]["result"] == {}
    assert "tools" in responses[1]["result"]
