from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from app.video_frame_capture import capture_local_video
from PIL import Image


MCP_PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "bilibili-render-pdf"
SERVER_VERSION = "0.1.0"
SUBTITLE_EXTENSIONS = (".srt", ".vtt", ".ass")
VIDEO_URL_PATTERN = re.compile(
    r"^(https?://)?(?:(www\.)?bilibili\.com/video/(?P<bv>BV[0-9A-Za-z]+)|b23\.tv/(?P<short>[0-9A-Za-z]+))"
)


class BilibiliRenderPdfServer:
    def __init__(self) -> None:
        self.repo_root = Path(__file__).resolve().parents[1]
        self.skill_root = Path.home() / ".codex" / "skills" / "bilibili-render-pdf"
        self.template_path = self.skill_root / "assets" / "notes-template.tex"
        self.transcribe_script = self.skill_root / "scripts" / "transcribe_local_audio.py"
        self.skill_requirements = self.skill_root / "scripts" / "requirements-audio.txt"
        self.transcribe_venv_python = self.skill_root / ".venv" / "Scripts" / "python.exe"
        self.model_cache_dir = self.skill_root / ".cache" / "faster-whisper"
        self.sandbox_root = Path(
            os.environ.get("NOTEWEAVE_MCP_SANDBOX_ROOT", str(self.repo_root / "runtime"))
        ).expanduser().resolve()
        self.allow_portable_pdf_fallback = (
            os.environ.get("NOTEWEAVE_ALLOW_PORTABLE_PDF_FALLBACK", "false").strip().lower()
            in {"1", "true", "yes", "on"}
        )
        self.input_root = self.sandbox_root / "inputs"
        self.output_root = self.sandbox_root / "bilibili-render-pdf"
        self.input_root.mkdir(parents=True, exist_ok=True)
        self.output_root.mkdir(parents=True, exist_ok=True)

    def serve_stdio(self) -> int:
        for raw_line in sys.stdin:
            line = raw_line.strip()
            if not line:
                continue
            for response in self.handle_line(line):
                sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
                sys.stdout.flush()
        return 0

    def handle_line(self, line: str) -> list[dict[str, Any]]:
        payload = json.loads(line)
        if isinstance(payload, list):
            return [response for item in payload if (response := self.handle_message(item)) is not None]
        response = self.handle_message(payload)
        return [response] if response is not None else []

    def handle_message(self, request: dict[str, Any]) -> dict[str, Any] | None:
        request_id = request.get("id")
        method = request.get("method")
        if not method:
            return self._error(request_id, -32600, "Invalid request")
        if request_id is None:
            return None

        try:
            if method == "initialize":
                return self._result(request_id, self._initialize_result(request.get("params") or {}))
            if method == "ping":
                return self._result(request_id, {})
            if method == "tools/list":
                return self._result(request_id, {"tools": self._tools_list()})
            if method == "tools/call":
                return self._result(request_id, self._call_tool(request.get("params") or {}))
        except Exception as exc:
            return self._error(request_id, -32602, str(exc))
        return self._error(request_id, -32601, "Method not found")

    def _initialize_result(self, params: dict[str, Any]) -> dict[str, Any]:
        protocol_version = params.get("protocolVersion", MCP_PROTOCOL_VERSION)
        if protocol_version not in {"2024-11-05", "2025-03-26", MCP_PROTOCOL_VERSION}:
            protocol_version = MCP_PROTOCOL_VERSION
        return {
            "protocolVersion": protocol_version,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {
                "name": SERVER_NAME,
                "version": SERVER_VERSION,
            },
        }

    def _tools_list(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "get_bilibili_subtitle",
                "description": "Fetch bilibili subtitles remotely, falling back to remote-audio transcription when subtitles are unavailable.",
                "inputSchema": _object_schema(
                    {
                        "video_url": _string_schema("Bilibili video URL, including bilibili.com/video/BV... or b23.tv short links."),
                        "prefer_languages": {
                            "type": "array",
                            "description": "Preferred subtitle languages in priority order.",
                            "items": {"type": "string"},
                            "default": ["zh-Hans", "zh-CN", "zh", "ai-zh"],
                        },
                        "output_dir": _string_schema("Optional output directory for fetched subtitle artifacts."),
                        "allow_auto_subtitles": {
                            "type": "boolean",
                            "description": "Try automatic subtitles after manual subtitles are unavailable.",
                            "default": True,
                        },
                        "fallback_to_transcription": {
                            "type": "boolean",
                            "description": "Download remote audio and run Whisper transcription if no subtitle files are available.",
                            "default": True,
                        },
                        "transcription_model": _string_schema("Whisper model used during transcription fallback. Defaults to small."),
                        "language": _string_schema("Language hint used for subtitle fallback transcription. Defaults to zh."),
                        "cookies_file": _string_schema("Optional Netscape-format cookies file for login-gated videos."),
                    },
                    required=["video_url"],
                ),
            },
            {
                "name": "transcribe_local_audio",
                "description": "Transcribe a local audio or video file/folder using the bilibili-render-pdf skill's faster-whisper script.",
                "inputSchema": _object_schema(
                    {
                        "input_path": _string_schema("Absolute local file or directory path."),
                        "output_dir": _string_schema("Optional output directory for transcripts."),
                        "model": _string_schema("Whisper model name. Defaults to small."),
                        "language": _string_schema("Language hint. Defaults to zh."),
                        "device": _string_schema("cpu, cuda, or auto. Defaults to cpu."),
                        "compute_type": _string_schema("faster-whisper compute type. Defaults to int8."),
                        "beam_size": {
                            "type": "integer",
                            "description": "Beam size.",
                            "minimum": 1,
                            "default": 5,
                        },
                        "skip_existing": {
                            "type": "boolean",
                            "description": "Skip files whose .txt and .srt already exist.",
                            "default": True,
                        },
                        "json_output": {
                            "type": "boolean",
                            "description": "Also write per-file JSON metadata.",
                            "default": True,
                        },
                    },
                    required=["input_path"],
                ),
            },
            {
                "name": "capture_bilibili_frames",
                "description": "Capture bounded, timestamped PNG frames from one Bilibili video part into the controlled sandbox.",
                "inputSchema": _object_schema({
                    "video_url": _string_schema("Bilibili BV video URL, optionally with one ?p=N part."),
                    "output_dir": _string_schema("Optional sandbox output directory."),
                    "cookies_file": _string_schema("Optional sandbox Netscape cookies file."),
                    "interval_ms": {"type": "integer", "minimum": 1000, "default": 30000},
                    "max_frames": {"type": "integer", "minimum": 1, "maximum": 32, "default": 32},
                }, required=["video_url"]),
            },
            {
                "name": "render_latex_pdf",
                "description": "Render a controlled LaTeX note package from structured course-note content.",
                "inputSchema": _object_schema(
                    {
                        "title": _string_schema("Document title."),
                        "video_url": _string_schema("Optional source Bilibili URL."),
                        "video_channel": _string_schema("Optional speaker or channel name."),
                        "video_publish_date": _string_schema("Optional publish date."),
                        "video_duration": _string_schema("Optional video duration."),
                        "video_part": {"type": "integer", "minimum": 1},
                        "video_duration_ms": {"type": "integer", "minimum": 1},
                        "cover_image_path": _string_schema("Optional local cover image path."),
                        "output_dir": _string_schema("Optional output directory."),
                        "output_stem": _string_schema("Optional output filename stem."),
                        "sections": {
                            "type": "array",
                            "description": "Ordered top-level sections for the note.",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "heading": {"type": "string"},
                                    "body": {"type": "string"},
                                    "image_refs": {"type": "array", "items": {"type": "object",
                                        "properties": {
                                            "file_id": {"type": "string"},
                                            "path": {"type": "string"},
                                            "checksum_sha256": {"type": "string"},
                                            "part": {"type": "integer"},
                                            "at_ms": {"type": "integer"},
                                        }, "required": ["file_id", "path", "checksum_sha256", "part", "at_ms"],
                                        "additionalProperties": False}},
                                },
                                "required": ["heading", "body"],
                                "additionalProperties": False,
                            },
                            "minItems": 1,
                        },
                    },
                    required=["title", "sections"],
                ),
            },
        ]

    def _call_tool(self, params: dict[str, Any]) -> dict[str, Any]:
        tool_name = params.get("name")
        arguments = params.get("arguments") or {}
        if not tool_name:
            raise ValueError("tools/call requires name")
        if tool_name == "get_bilibili_subtitle":
            return _tool_result(self._get_bilibili_subtitle(arguments))
        if tool_name == "transcribe_local_audio":
            return _tool_result(self._transcribe_local_audio(arguments))
        if tool_name == "capture_bilibili_frames":
            return _tool_result(self._capture_bilibili_frames(arguments))
        if tool_name == "render_latex_pdf":
            return _tool_result(self._render_latex_pdf(arguments))
        raise ValueError(f"Unknown tool: {tool_name}")

    def _get_bilibili_subtitle(self, arguments: dict[str, Any]) -> dict[str, Any]:
        video_url = _required_string(arguments, "video_url")
        match = VIDEO_URL_PATTERN.match(video_url.strip())
        if match is None:
            raise ValueError("video_url must be a bilibili.com/video/BV... or b23.tv link")

        preferred_languages = arguments.get("prefer_languages") or ["zh-Hans", "zh-CN", "zh", "ai-zh"]
        normalized_url = video_url if video_url.startswith("http") else f"https://{video_url}"
        allow_auto_subtitles = bool(arguments.get("allow_auto_subtitles", True))
        fallback_to_transcription = bool(arguments.get("fallback_to_transcription", True))
        transcription_model = str(arguments.get("transcription_model") or "small")
        language = str(arguments.get("language") or "zh")
        cookies_path = self._resolve_optional_input_file(
            str(arguments.get("cookies_file") or ""), "cookies_file"
        )
        cookies_file = str(cookies_path) if cookies_path else ""
        output_dir = self._resolve_output_dir(
            str(arguments.get("output_dir") or ""),
            bucket="subtitles",
            stem=match.group("bv") or match.group("short") or "bilibili-video",
        )
        self._ensure_yt_dlp_available()

        metadata = self._fetch_video_metadata(normalized_url, cookies_file=cookies_file)
        metadata_path = output_dir / "metadata.json"
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        manual_subtitles = self._download_subtitles(
            normalized_url,
            output_dir=output_dir,
            preferred_languages=preferred_languages,
            cookies_file=cookies_file,
            include_auto_subtitles=False,
        )
        if manual_subtitles["subtitle_files"]:
            return self._build_subtitle_fetch_result(
                normalized_url=normalized_url,
                normalized_video_id=match.group("bv") or match.group("short") or "",
                metadata=metadata,
                metadata_path=metadata_path,
                output_dir=output_dir,
                preferred_languages=preferred_languages,
                execution_mode="remote_cc_subtitle_fetch",
                subtitle_fetch=manual_subtitles,
                notes=[
                    "Fetched subtitle artifacts directly through the MCP server.",
                    "Manual subtitles were prioritized before any fallback path.",
                ],
            )

        auto_subtitles = {
            "subtitle_files": [],
            "selected_subtitle_path": "",
            "selected_language": "",
            "fetch_stdout": "",
            "fetch_stderr": "",
            "fetch_command": [],
        }
        if allow_auto_subtitles:
            auto_subtitles = self._download_subtitles(
                normalized_url,
                output_dir=output_dir,
                preferred_languages=preferred_languages,
                cookies_file=cookies_file,
                include_auto_subtitles=True,
            )
            if auto_subtitles["subtitle_files"]:
                return self._build_subtitle_fetch_result(
                    normalized_url=normalized_url,
                    normalized_video_id=match.group("bv") or match.group("short") or "",
                    metadata=metadata,
                    metadata_path=metadata_path,
                    output_dir=output_dir,
                    preferred_languages=preferred_languages,
                    execution_mode="remote_auto_subtitle_fetch",
                    subtitle_fetch=auto_subtitles,
                    notes=[
                        "Fetched subtitle artifacts directly through the MCP server.",
                        "Automatic subtitles were used because manual subtitles were unavailable.",
                    ],
                )

        if not fallback_to_transcription:
            return {
                "video_url": normalized_url,
                "normalized_video_id": match.group("bv") or match.group("short") or "",
                "source_platform": "bilibili",
                "acquisition_mode": "no_subtitle_available",
                "metadata": metadata,
                "metadata_path": str(metadata_path),
                "artifact_dir": str(output_dir),
                "preferred_languages": preferred_languages,
                "subtitle_files": [],
                "selected_subtitle_path": "",
                "selected_language": "",
                "notes": [
                    "No remote subtitle files were available.",
                    "fallback_to_transcription was disabled, so the MCP server did not download audio.",
                ],
                "manual_fetch": manual_subtitles,
                "auto_fetch": auto_subtitles,
            }

        audio_path, audio_fetch = self._download_audio_for_transcription(
            normalized_url,
            output_dir=output_dir,
            cookies_file=cookies_file,
        )
        transcription_output_dir = output_dir / "transcripts"
        transcription_result = self._run_transcription(
            input_path=audio_path,
            output_dir=transcription_output_dir,
            model=transcription_model,
            language=language,
            device="cpu",
            compute_type="int8",
            beam_size=5,
            skip_existing=False,
            json_output=True,
        )
        srt_files = transcription_result["artifacts"]["srt_files"]
        selected_srt = srt_files[0] if srt_files else ""
        return {
            "video_url": normalized_url,
            "normalized_video_id": match.group("bv") or match.group("short") or "",
            "source_platform": "bilibili",
            "acquisition_mode": "remote_audio_transcription_fallback",
            "metadata": metadata,
            "metadata_path": str(metadata_path),
            "artifact_dir": str(output_dir),
            "preferred_languages": preferred_languages,
            "subtitle_files": srt_files,
            "selected_subtitle_path": selected_srt,
            "selected_language": language,
            "subtitle_preview": self._read_subtitle_preview(Path(selected_srt)) if selected_srt else [],
            "audio_artifact_path": str(audio_path),
            "manual_fetch": manual_subtitles,
            "auto_fetch": auto_subtitles,
            "audio_fetch": audio_fetch,
            "transcription": transcription_result,
            "notes": [
                "No remote subtitle files were available, so the MCP server downloaded remote audio and transcribed it locally.",
            ],
        }

    def _capture_bilibili_frames(self, arguments: dict[str, Any]) -> dict[str, Any]:
        raw_url = _required_string(arguments, "video_url")
        parsed = urlparse(raw_url)
        match = re.fullmatch(r"/video/(BV[0-9A-Za-z]{10})/?", parsed.path)
        parts = parse_qs(parsed.query).get("p", ["1"])
        if (parsed.scheme not in {"https", "http"}
                or parsed.hostname not in {"bilibili.com", "www.bilibili.com"}
                or match is None or len(parts) != 1 or not parts[0].isdigit()
                or int(parts[0]) < 1 or int(parts[0]) > 1000):
            raise ValueError("capture requires one Bilibili BV video and part")
        part = int(parts[0])
        bvid = match.group(1)
        interval_ms = arguments.get("interval_ms", 30_000)
        max_frames = arguments.get("max_frames", 32)
        if isinstance(interval_ms, bool) or not isinstance(interval_ms, int) \
                or isinstance(max_frames, bool) or not isinstance(max_frames, int):
            raise ValueError("frame capture policy must use integers")
        cookies = self._resolve_optional_input_file(
            str(arguments.get("cookies_file") or ""), "cookies_file")
        cookies_file = str(cookies) if cookies else ""
        output_dir = self._resolve_output_dir(
            str(arguments.get("output_dir") or ""), bucket="frames", stem=f"{bvid}-p{part}")
        self._ensure_yt_dlp_available()
        metadata = self._fetch_video_metadata(raw_url, cookies_file=cookies_file)
        observed = urlparse(str(metadata.get("webpage_url", "")))
        observed_parts = parse_qs(observed.query).get("p", ["1"])
        duration = metadata.get("duration")
        if (metadata.get("id") != bvid or observed.hostname not in {
                "bilibili.com", "www.bilibili.com"}
                or not re.fullmatch(rf"/video/{bvid}/?", observed.path)
                or observed_parts != [str(part)]
                or isinstance(duration, bool) or not isinstance(duration, (int, float))
                or not (0 < duration <= 86_400)):
            raise ValueError("video metadata does not match the requested part and duration")
        duration_ms = round(duration * 1000)
        video_dir = output_dir / "video"
        video_dir.mkdir(parents=True, exist_ok=True)
        command = self._build_yt_dlp_command(
            "--no-playlist", "--max-filesize", "1G", "--socket-timeout", "20",
            "--retries", "2", "-f", "bestvideo[height<=1080]/best[height<=1080]",
            "-o", str(video_dir / "video.%(ext)s"), raw_url,
            cookies_file=cookies_file,
        )
        completed = subprocess.run(
            command, cwd=str(video_dir), capture_output=True, text=True,
            timeout=600, check=False,
        )
        videos = [path for path in video_dir.iterdir()
                  if path.is_file() and not path.is_symlink()
                  and path.name.startswith("video.") and path.suffix.lower() in {
                      ".mp4", ".webm", ".mkv"}]
        if completed.returncode != 0 or len(videos) != 1 \
                or not 0 < videos[0].stat().st_size <= 1_000_000_000:
            raise ValueError("video stream download failed or exceeded the capture limit")
        captured = capture_local_video(
            video_path=videos[0], output_dir=output_dir / "images",
            sandbox_root=self.sandbox_root, part=part, duration_ms=duration_ms,
            interval_ms=interval_ms, max_frames=max_frames,
        )
        files = {frame.file_id: {
            "file_id": frame.file_id, "role": "VIDEO_FRAME", "media_type": "image/png",
            "size_bytes": frame.size_bytes, "checksum_sha256": frame.checksum_sha256,
            "path": str(frame.path),
        } for frame in captured.frames}
        return {
            "normalized_video_id": bvid, "part": part, "duration_ms": duration_ms,
            "metadata": metadata,
            "frames": [{
                "frame_id": frame.frame_id, "part": frame.part, "at_ms": frame.at_ms,
                "file_id": frame.file_id, "checksum_sha256": frame.checksum_sha256,
                "dedupe_of": frame.dedupe_of,
            } for frame in captured.frames],
            "files": list(files.values()),
            "coverage_gaps": (["NO_FRAMES"] if not captured.frames else [])
                + (["FRAME_CAPTURE_PARTIAL"] if captured.missing_requested_ms else []),
            "missing_requested_ms": list(captured.missing_requested_ms),
        }

    def _transcribe_local_audio(self, arguments: dict[str, Any]) -> dict[str, Any]:
        input_path = self._resolve_input_path(_required_string(arguments, "input_path"), "input_path")
        if not input_path.exists():
            raise ValueError(f"input_path does not exist: {input_path}")
        if importlib.util.find_spec("faster_whisper") is None and not self.transcribe_script.exists():
            raise ValueError(f"transcribe script not found: {self.transcribe_script}")

        output_dir = (
            self._resolve_output_path(str(arguments.get("output_dir")), "output_dir")
            if arguments.get("output_dir")
            else self.output_root / "transcripts"
        )
        output_dir.mkdir(parents=True, exist_ok=True)
        model = str(arguments.get("model") or "small")
        language = str(arguments.get("language") or "zh")
        device = str(arguments.get("device") or "cpu")
        compute_type = str(arguments.get("compute_type") or "int8")
        beam_size = int(arguments.get("beam_size") or 5)
        skip_existing = bool(arguments.get("skip_existing", True))
        json_output = bool(arguments.get("json_output", True))
        return self._run_transcription(
            input_path=input_path,
            output_dir=output_dir,
            model=model,
            language=language,
            device=device,
            compute_type=compute_type,
            beam_size=beam_size,
            skip_existing=skip_existing,
            json_output=json_output,
        )

    def _render_latex_pdf(self, arguments: dict[str, Any]) -> dict[str, Any]:
        title = _required_string(arguments, "title")
        sections = arguments.get("sections")
        if not isinstance(sections, list) or not sections:
            raise ValueError("sections is required")
        sections = self._validated_pdf_sections(
            sections, arguments.get("video_part"), arguments.get("video_duration_ms"))

        output_dir = (
            self._resolve_output_path(str(arguments.get("output_dir")), "output_dir")
            if arguments.get("output_dir")
            else self.output_root / "exports"
        )
        output_dir.mkdir(parents=True, exist_ok=True)
        output_stem = _sanitize_stem(str(arguments.get("output_stem") or title))
        tex_path = output_dir / f"{output_stem}.tex"
        pdf_path = output_dir / f"{output_stem}.pdf"
        evidence_dir = output_dir / "evidence"
        for section in sections:
            for ref in section["image_refs"]:
                with Image.open(ref["path"]) as image:
                    suffix = ".png" if image.format == "PNG" else ".jpg"
                staged = evidence_dir / f"{ref['file_id']}{suffix}"
                evidence_dir.mkdir(parents=True, exist_ok=True)
                if staged.is_symlink() or (staged.exists() and
                        hashlib.sha256(staged.read_bytes()).hexdigest() != ref["checksum_sha256"]):
                    raise ValueError("PDF evidence staging conflicts with a different image")
                if not staged.exists():
                    shutil.copyfile(ref["path"], staged)
                if hashlib.sha256(staged.read_bytes()).hexdigest() != ref["checksum_sha256"]:
                    raise ValueError("PDF staged evidence digest differs from frozen image")
                ref["path"] = str(staged)
                ref["tex_path"] = f"evidence/{staged.name}"

        latex = self._build_latex_document(
            title=title,
            video_url=str(arguments.get("video_url") or ""),
            video_channel=str(arguments.get("video_channel") or ""),
            video_publish_date=str(arguments.get("video_publish_date") or ""),
            video_duration=str(arguments.get("video_duration") or ""),
            cover_image_path=str(self._resolve_optional_input_file(
                str(arguments.get("cover_image_path") or ""), "cover_image_path"
            ) or ""),
            sections=sections,
        )
        tex_path.write_text(latex, encoding="utf-8")

        xelatex = shutil.which("xelatex")
        pdf_path.unlink(missing_ok=True)
        pdf_status = "COMPILED"
        compile_message = "portable CJK PDF renderer used because xelatex is not installed"
        execution_mode = "portable_cjk_pdf_fallback"
        xelatex_succeeded = False
        if xelatex:
            completed = None
            for _ in range(2):
                completed = subprocess.run(
                    [xelatex, "-interaction=nonstopmode", "-halt-on-error", tex_path.name],
                    cwd=str(output_dir),
                    text=True,
                    capture_output=True,
                    check=False,
                )
                if completed.returncode != 0:
                    break
            xelatex_succeeded = (
                completed is not None and completed.returncode == 0 and pdf_path.exists()
            )
            if completed is not None:
                compile_message = (completed.stderr or completed.stdout)[-2000:]
            execution_mode = (
                "controlled_xelatex_render"
                if xelatex_succeeded
                else "portable_cjk_pdf_fallback"
            )
        if not xelatex_succeeded:
            if not self.allow_portable_pdf_fallback:
                raise RuntimeError(
                    "controlled XeLaTeX rendering is unavailable or failed; "
                    "portable PDF fallback is disabled"
                )
            _render_portable_cjk_pdf(
                pdf_path=pdf_path,
                title=title,
                sections=sections,
                video_url=str(arguments.get("video_url") or ""),
            )

        notes = ["The system MCP emitted a deterministic LaTeX source package."]
        if execution_mode == "controlled_xelatex_render":
            notes.append("The PDF was compiled by the controlled XeLaTeX runtime.")
        else:
            notes.append("Portable CJK fallback was explicitly enabled for this runtime.")

        return {
            "title": title,
            "output_dir": str(output_dir),
            "tex_path": str(tex_path),
            "pdf_path": str(pdf_path),
            "pdf_status": pdf_status,
            "execution_mode": execution_mode,
            "compile_message": compile_message,
            "notes": notes,
        }

    def _validated_pdf_sections(
        self, sections: list[dict[str, Any]], video_part: object,
        duration_ms: object,
    ) -> list[dict[str, Any]]:
        checked: list[dict[str, Any]] = []
        total_refs = 0
        for section in sections:
            if not isinstance(section, dict) or not isinstance(section.get("heading"), str) \
                    or not isinstance(section.get("body"), str):
                raise ValueError("PDF section heading and body are required")
            raw_refs = section.get("image_refs", [])
            if not isinstance(raw_refs, list) or len(raw_refs) > 16:
                raise ValueError("PDF image references exceed section limit")
            total_refs += len(raw_refs)
            if total_refs > 32:
                raise ValueError("PDF image references exceed document limit")
            refs = []
            for raw in raw_refs:
                if not isinstance(raw, dict) or set(raw) != {
                    "file_id", "path", "checksum_sha256", "part", "at_ms"
                } or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", str(raw.get("file_id", ""))) \
                        or isinstance(video_part, bool) or not isinstance(video_part, int) \
                        or not 1 <= video_part <= 1000 \
                        or isinstance(duration_ms, bool) or not isinstance(duration_ms, int) \
                        or not 1 <= duration_ms <= 86_400_000 \
                        or isinstance(raw.get("part"), bool) \
                        or not isinstance(raw.get("part"), int) \
                        or raw.get("part") != video_part \
                        or not isinstance(raw.get("at_ms"), int) \
                        or isinstance(raw.get("at_ms"), bool) \
                        or not 0 <= raw["at_ms"] < duration_ms \
                        or not re.fullmatch(r"[0-9a-f]{64}", str(raw.get("checksum_sha256", ""))):
                    raise ValueError("PDF image reference has invalid identity, part or time")
                path = self._resolve_sandbox_image(str(raw["path"]))
                content = path.read_bytes()
                if len(content) < 1 or len(content) > 16_000_000 \
                        or hashlib.sha256(content).hexdigest() != raw["checksum_sha256"]:
                    raise ValueError("PDF image bytes do not match frozen evidence")
                try:
                    with Image.open(path) as image:
                        if image.format not in {"PNG", "JPEG"} \
                                or image.width * image.height > 50_000_000:
                            raise ValueError("PDF image is not a bounded PNG or JPEG")
                        image.verify()
                except OSError as exc:
                    raise ValueError("PDF image is not decodable") from exc
                refs.append({**raw, "path": str(path)})
            checked.append({"heading": section["heading"], "body": section["body"],
                            "image_refs": refs})
        return checked

    def _resolve_sandbox_image(self, value: str) -> Path:
        path = Path(value).expanduser()
        if not path.is_absolute() or ".." in path.parts:
            raise ValueError("PDF image path must be absolute inside the MCP sandbox")
        self._reject_symlink_components(path, "PDF image")
        resolved = path.resolve()
        if not resolved.is_relative_to(self.sandbox_root) or not resolved.is_file():
            raise ValueError("PDF image must be a sandbox file")
        return resolved

    def _build_latex_document(
        self,
        *,
        title: str,
        video_url: str,
        video_channel: str,
        video_publish_date: str,
        video_duration: str,
        cover_image_path: str,
        sections: list[dict[str, Any]],
    ) -> str:
        if self.template_path.exists():
            template = self.template_path.read_text(encoding="utf-8")
        else:
            template = _fallback_template()

        body = []
        for item in sections:
            heading = _latex_escape(str(item.get("heading") or "Untitled Section"))
            section_body = _latex_escape(str(item.get("body") or "")).replace("\n", "\n\n")
            figures = []
            for ref in item.get("image_refs", []):
                figures.append(
                    "\\begin{figure}[htbp]\n\\centering\n"
                    f"\\includegraphics[width=0.85\\linewidth,height=0.35\\textheight,keepaspectratio]{{{ref['tex_path']}}}\n"
                    f"\\caption{{Frame {_latex_escape(ref['file_id'])} at {ref['at_ms']} ms}}\n"
                    "\\end{figure}\n"
                )
            body.append(f"\\section{{{heading}}}\n{section_body}\n" + "\n".join(figures))
        body_text = "\n".join(body).strip() + "\n"

        replacements = {
            "notetitle": _latex_escape(title),
            "videochannel": _latex_escape(video_channel or "[待补充]"),
            "videopublishdate": _latex_escape(video_publish_date or "[待补充]"),
            "videoduration": _latex_escape(video_duration or "[待补充]"),
            "videourl": _latex_escape(video_url),
            "videocoverpath": _latex_escape(cover_image_path),
        }
        for command, value in replacements.items():
            pattern = rf"\\newcommand\{{\\{command}\}}\{{[^\n]*"
            replacement = f"\\newcommand{{\\{command}}}{{{value}}}"
            template = re.sub(pattern, lambda _: replacement, template, count=1)

        marker_start = "%% ---"
        marker_end = r"\end{document}"
        marker_index = template.find(marker_start)
        end_index = template.rfind(marker_end)
        if marker_index != -1 and end_index != -1 and marker_index < end_index:
            prefix = template[:marker_index]
            suffix = template[end_index:]
            if any(item.get("image_refs") for item in sections) and "\\usepackage{graphicx}" not in prefix:
                prefix = prefix.replace("\\begin{document}",
                                        "\\usepackage{graphicx}\n\\begin{document}", 1)
            return prefix + body_text + "\n" + suffix
        return template + "\n" + body_text

    def _result(self, request_id: Any, result: Any) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _error(self, request_id: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    def _resolve_transcription_python(self) -> tuple[Path, str, list[str]]:
        current_python = Path(sys.executable).resolve()
        notes = [
            "The MCP server prefers its own Python environment so the capability stays independent from the original skill directory.",
        ]
        if self._looks_like_conda_env(current_python):
            notes.append("Using the MCP server's active conda environment.")
            return current_python, "delegated_skill_script_in_mcp_conda_env", notes
        if self.transcribe_venv_python.exists():
            notes.append("Falling back to the skill-bundled virtual environment because the MCP server is not running inside conda.")
            return self.transcribe_venv_python, "delegated_skill_script_in_skill_venv", notes
        raise ValueError(
            "No usable Python runtime found for transcription. "
            "Create the dedicated conda environment with workers/artifact-worker/mcp/setup_bilibili_render_pdf_mcp_env.ps1 "
            "or run the skill's setup_audio_env.ps1 first."
        )

    def _looks_like_conda_env(self, python_path: Path) -> bool:
        conda_prefix = os.environ.get("CONDA_PREFIX", "").strip()
        if conda_prefix:
            try:
                return python_path.is_relative_to(Path(conda_prefix).resolve())
            except ValueError:
                return False
        return ".conda" in str(python_path).lower() or "miniforge" in str(python_path).lower()

    def _ensure_yt_dlp_available(self) -> None:
        if importlib.util.find_spec("yt_dlp") is None:
            raise ValueError(
                "yt-dlp is not available in the MCP environment. "
                "Run workers/artifact-worker/mcp/setup_bilibili_render_pdf_mcp_env.ps1 to install remote fetch dependencies."
            )

    def _resolve_output_dir(self, requested_output_dir: str, *, bucket: str, stem: str) -> Path:
        if requested_output_dir.strip():
            output_dir = self._resolve_output_path(requested_output_dir, "output_dir")
        else:
            output_dir = self.output_root / bucket / _sanitize_stem(stem)
        output_dir.mkdir(parents=True, exist_ok=True)
        return output_dir

    def _resolve_input_path(self, value: str, field_name: str) -> Path:
        path = Path(value).expanduser()
        if not path.is_absolute() or ".." in path.parts:
            raise ValueError(f"{field_name} must be an absolute path inside the MCP sandbox")
        self._reject_symlink_components(path, field_name)
        resolved = path.resolve()
        if not resolved.is_relative_to(self.input_root):
            raise ValueError(f"{field_name} must stay inside {self.input_root}")
        return resolved

    def _resolve_optional_input_file(self, value: str, field_name: str) -> Path | None:
        if not value.strip():
            return None
        path = self._resolve_input_path(value, field_name)
        if not path.is_file():
            raise ValueError(f"{field_name} does not exist: {path}")
        return path

    def _resolve_output_path(self, value: str, field_name: str) -> Path:
        path = Path(value).expanduser()
        if not path.is_absolute() or ".." in path.parts:
            raise ValueError(f"{field_name} must be an absolute path inside the MCP sandbox")
        self._reject_symlink_components(path, field_name)
        resolved = path.resolve()
        if not resolved.is_relative_to(self.output_root):
            raise ValueError(f"{field_name} must stay inside {self.output_root}")
        return resolved

    @staticmethod
    def _reject_symlink_components(path: Path, field_name: str) -> None:
        """Reject existing symlink components to prevent sandbox escapes."""
        current = path.anchor and Path(path.anchor) or Path()
        for component in path.parts[1:] if path.is_absolute() else path.parts:
            current = current / component
            if current.exists() and current.is_symlink():
                raise ValueError(f"{field_name} cannot traverse symlink components")

    def _fetch_video_metadata(self, video_url: str, *, cookies_file: str = "") -> dict[str, Any]:
        command = self._build_yt_dlp_command(
            "--dump-single-json",
            "--no-warnings",
            "--skip-download",
            video_url,
            cookies_file=cookies_file,
        )
        completed = self._run_subprocess(command, cwd=self.output_root)
        if completed.returncode != 0:
            raise ValueError(f"failed to inspect bilibili metadata: {completed.stderr or completed.stdout}")
        try:
            payload = json.loads(completed.stdout)
        except json.JSONDecodeError as exc:
            raise ValueError("failed to parse yt-dlp metadata output") from exc
        subtitles = payload.get("subtitles") or {}
        automatic_captions = payload.get("automatic_captions") or {}
        return {
            "id": str(payload.get("id") or ""),
            "title": str(payload.get("title") or ""),
            "uploader": str(payload.get("uploader") or payload.get("channel") or ""),
            "duration": payload.get("duration"),
            "upload_date": str(payload.get("upload_date") or ""),
            "thumbnail": str(payload.get("thumbnail") or ""),
            "webpage_url": str(payload.get("webpage_url") or video_url),
            "subtitle_languages": sorted(subtitles.keys()),
            "automatic_caption_languages": sorted(automatic_captions.keys()),
        }

    def _download_subtitles(
        self,
        video_url: str,
        *,
        output_dir: Path,
        preferred_languages: list[str],
        cookies_file: str = "",
        include_auto_subtitles: bool,
    ) -> dict[str, Any]:
        suffix = "auto" if include_auto_subtitles else "manual"
        target_dir = output_dir / suffix
        target_dir.mkdir(parents=True, exist_ok=True)
        command_parts = [
            "--write-subs",
            "--sub-langs",
            ",".join(preferred_languages),
            "--convert-subs",
            "srt",
            "--skip-download",
        ]
        if include_auto_subtitles:
            command_parts.insert(1, "--write-auto-subs")
        command = self._build_yt_dlp_command(
            *command_parts,
            "-o",
            str(target_dir / "%(id)s.%(ext)s"),
            video_url,
            cookies_file=cookies_file,
        )
        completed = self._run_subprocess(command, cwd=target_dir)
        subtitle_files = self._collect_subtitle_files(target_dir)
        selected_subtitle = self._select_preferred_subtitle(subtitle_files, preferred_languages)
        return {
            "subtitle_files": [str(path) for path in subtitle_files],
            "selected_subtitle_path": str(selected_subtitle) if selected_subtitle else "",
            "selected_language": self._infer_subtitle_language(selected_subtitle, preferred_languages),
            "fetch_command": command,
            "fetch_stdout": completed.stdout,
            "fetch_stderr": completed.stderr,
            "fetch_exit_code": completed.returncode,
        }

    def _download_audio_for_transcription(
        self,
        video_url: str,
        *,
        output_dir: Path,
        cookies_file: str = "",
    ) -> tuple[Path, dict[str, Any]]:
        target_dir = output_dir / "audio"
        target_dir.mkdir(parents=True, exist_ok=True)
        command = self._build_yt_dlp_command(
            "-f",
            "bestaudio/best",
            "-o",
            str(target_dir / "audio.%(ext)s"),
            video_url,
            cookies_file=cookies_file,
        )
        completed = self._run_subprocess(command, cwd=target_dir)
        audio_files = sorted(
            [
                path
                for path in target_dir.rglob("*")
                if path.is_file() and path.suffix.lower() in {".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".webm"}
            ]
        )
        if completed.returncode != 0 or not audio_files:
            raise ValueError(
                "failed to download remote audio stream for transcription fallback: "
                f"{completed.stderr or completed.stdout}"
            )
        return audio_files[0], {
            "fetch_command": command,
            "fetch_stdout": completed.stdout,
            "fetch_stderr": completed.stderr,
            "fetch_exit_code": completed.returncode,
            "audio_files": [str(path) for path in audio_files],
        }

    def _run_transcription(
        self,
        *,
        input_path: Path,
        output_dir: Path,
        model: str,
        language: str,
        device: str,
        compute_type: str,
        beam_size: int,
        skip_existing: bool,
        json_output: bool,
    ) -> dict[str, Any]:
        if importlib.util.find_spec("faster_whisper") is not None:
            return self._run_bundled_faster_whisper(
                input_path=input_path,
                output_dir=output_dir,
                model=model,
                language=language,
                device=device,
                compute_type=compute_type,
                beam_size=beam_size,
                skip_existing=skip_existing,
                json_output=json_output,
            )
        python_executable, execution_mode, environment_notes = self._resolve_transcription_python()
        command = [
            str(python_executable),
            str(self.transcribe_script),
            "--input",
            str(input_path),
            "--output-dir",
            str(output_dir),
            "--model",
            model,
            "--language",
            language,
            "--device",
            device,
            "--compute-type",
            compute_type,
            "--beam-size",
            str(beam_size),
            "--model-cache-dir",
            str(self.model_cache_dir),
        ]
        if skip_existing:
            command.append("--skip-existing")
        if json_output:
            command.append("--json")

        completed = self._run_subprocess(command, cwd=self.skill_root)
        if completed.returncode != 0:
            detail = completed.stderr or completed.stdout or "no subprocess output"
            raise ValueError(f"audio transcription failed: {detail}")
        transcript_files = sorted(output_dir.rglob("*.srt"))
        text_files = sorted(output_dir.rglob("*.txt"))
        json_files = sorted(output_dir.rglob("*.json"))
        return {
            "input_path": str(input_path),
            "output_dir": str(output_dir),
            "execution_mode": execution_mode,
            "command": command,
            "exit_code": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "artifacts": {
                "srt_files": [str(path) for path in transcript_files],
                "txt_files": [str(path) for path in text_files],
                "json_files": [str(path) for path in json_files],
            },
            "environment_notes": environment_notes,
            "ok": True,
        }

    def _run_bundled_faster_whisper(
        self,
        *,
        input_path: Path,
        output_dir: Path,
        model: str,
        language: str,
        device: str,
        compute_type: str,
        beam_size: int,
        skip_existing: bool,
        json_output: bool,
    ) -> dict[str, Any]:
        from faster_whisper import WhisperModel

        output_dir.mkdir(parents=True, exist_ok=True)
        media_extensions = {".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".webm", ".mp4", ".mkv"}
        media_files = (
            [input_path]
            if input_path.is_file()
            else sorted(path for path in input_path.rglob("*") if path.is_file() and path.suffix.lower() in media_extensions)
        )
        if not media_files:
            raise ValueError(f"no transcribable media files found: {input_path}")
        self.model_cache_dir.mkdir(parents=True, exist_ok=True)
        whisper_model = WhisperModel(
            model,
            device=device,
            compute_type=compute_type,
            download_root=str(self.model_cache_dir),
        )
        srt_files: list[str] = []
        text_files: list[str] = []
        json_files: list[str] = []
        for media_file in media_files:
            stem = _sanitize_stem(media_file.stem)
            srt_path = output_dir / f"{stem}.srt"
            text_path = output_dir / f"{stem}.txt"
            json_path = output_dir / f"{stem}.json"
            if skip_existing and srt_path.exists() and text_path.exists():
                srt_files.append(str(srt_path))
                text_files.append(str(text_path))
                if json_path.exists():
                    json_files.append(str(json_path))
                continue
            segments, info = whisper_model.transcribe(
                str(media_file),
                language=language or None,
                beam_size=beam_size,
            )
            normalized_segments = [
                {
                    "start": float(segment.start),
                    "end": float(segment.end),
                    "text": str(segment.text).strip(),
                }
                for segment in segments
                if str(segment.text).strip()
            ]
            text_path.write_text(
                "\n".join(segment["text"] for segment in normalized_segments),
                encoding="utf-8",
            )
            srt_path.write_text(
                "\n\n".join(
                    f"{index}\n{_format_srt_timestamp(segment['start'])} --> {_format_srt_timestamp(segment['end'])}\n{segment['text']}"
                    for index, segment in enumerate(normalized_segments, start=1)
                ) + "\n",
                encoding="utf-8",
            )
            srt_files.append(str(srt_path))
            text_files.append(str(text_path))
            if json_output:
                json_path.write_text(
                    json.dumps(
                        {
                            "input_path": str(media_file),
                            "language": str(getattr(info, "language", language)),
                            "duration": float(getattr(info, "duration", 0.0)),
                            "segments": normalized_segments,
                        },
                        ensure_ascii=False,
                        indent=2,
                    ),
                    encoding="utf-8",
                )
                json_files.append(str(json_path))
        return {
            "input_path": str(input_path),
            "output_dir": str(output_dir),
            "execution_mode": "bundled_faster_whisper",
            "command": [],
            "exit_code": 0,
            "stdout": "",
            "stderr": "",
            "artifacts": {
                "srt_files": srt_files,
                "txt_files": text_files,
                "json_files": json_files,
            },
            "environment_notes": ["Used the Artifact Worker bundled faster-whisper runtime."],
            "ok": True,
        }

    def _build_subtitle_fetch_result(
        self,
        *,
        normalized_url: str,
        normalized_video_id: str,
        metadata: dict[str, Any],
        metadata_path: Path,
        output_dir: Path,
        preferred_languages: list[str],
        execution_mode: str,
        subtitle_fetch: dict[str, Any],
        notes: list[str],
    ) -> dict[str, Any]:
        selected_subtitle_path = subtitle_fetch["selected_subtitle_path"]
        selected_subtitle = Path(selected_subtitle_path) if selected_subtitle_path else None
        return {
            "video_url": normalized_url,
            "normalized_video_id": normalized_video_id,
            "source_platform": "bilibili",
            "acquisition_mode": execution_mode,
            "metadata": metadata,
            "metadata_path": str(metadata_path),
            "artifact_dir": str(output_dir),
            "preferred_languages": preferred_languages,
            "subtitle_files": subtitle_fetch["subtitle_files"],
            "selected_subtitle_path": selected_subtitle_path,
            "selected_language": subtitle_fetch["selected_language"],
            "subtitle_preview": self._read_subtitle_preview(selected_subtitle) if selected_subtitle else [],
            "subtitle_fetch": subtitle_fetch,
            "notes": notes,
        }

    def _build_yt_dlp_command(self, *args: str, cookies_file: str = "") -> list[str]:
        command = [str(Path(sys.executable).resolve()), "-m", "yt_dlp"]
        if cookies_file:
            command.extend(["--cookies", cookies_file])
        command.extend(args)
        return command

    def _run_subprocess(self, command: list[str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            check=False,
        )

    def _collect_subtitle_files(self, root_dir: Path) -> list[Path]:
        return sorted(
            [
                path
                for path in root_dir.rglob("*")
                if path.is_file() and path.suffix.lower() in SUBTITLE_EXTENSIONS
            ]
        )

    def _select_preferred_subtitle(
        self,
        subtitle_files: list[Path],
        preferred_languages: list[str],
    ) -> Path | None:
        if not subtitle_files:
            return None
        for language in preferred_languages:
            normalized_language = language.lower()
            for path in subtitle_files:
                if normalized_language in path.name.lower():
                    return path
        return subtitle_files[0]

    def _infer_subtitle_language(
        self,
        subtitle_path: Path | None,
        preferred_languages: list[str],
    ) -> str:
        if subtitle_path is None:
            return ""
        lowered_name = subtitle_path.name.lower()
        for language in preferred_languages:
            if language.lower() in lowered_name:
                return language
        return subtitle_path.suffix.lstrip(".")

    def _read_subtitle_preview(self, subtitle_path: Path, limit: int = 6) -> list[str]:
        if not subtitle_path.exists():
            return []
        lines = []
        for raw_line in subtitle_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            stripped = raw_line.strip()
            if not stripped:
                continue
            if stripped.isdigit():
                continue
            if "-->" in stripped:
                continue
            lines.append(stripped)
            if len(lines) >= limit:
                break
        return lines


def _tool_result(output: Any) -> dict[str, Any]:
    text = json.dumps(output, ensure_ascii=False)
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": output,
        "isError": False,
    }


def _required_string(arguments: dict[str, Any], key: str) -> str:
    value = arguments.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} is required")
    return value.strip()


def _object_schema(properties: dict[str, Any], required: list[str] | None = None) -> dict[str, Any]:
    schema: dict[str, Any] = {
        "type": "object",
        "properties": properties,
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return schema


def _string_schema(description: str) -> dict[str, Any]:
    return {"type": "string", "description": description}


def _sanitize_stem(raw: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "_", raw).strip("._") or "course_notes"


def _latex_escape(text: str) -> str:
    replacements = {
        "\\": r"\textbackslash{}",
        "&": r"\&",
        "%": r"\%",
        "$": r"\$",
        "#": r"\#",
        "_": r"\_",
        "{": r"\{",
        "}": r"\}",
        "~": r"\textasciitilde{}",
        "^": r"\textasciicircum{}",
    }
    return "".join(replacements.get(ch, ch) for ch in text)


def _fallback_template() -> str:
    return (
        "\\documentclass[a4paper]{article}\n"
        "\\usepackage[fontset=fandol]{ctex}\n"
        "\\usepackage[margin=2.5cm]{geometry}\n"
        "\\usepackage{hyperref}\n"
        "\\newcommand{\\notetitle}{[Title]}\n"
        "\\newcommand{\\videochannel}{[Channel]}\n"
        "\\newcommand{\\videopublishdate}{[Date]}\n"
        "\\newcommand{\\videoduration}{[Duration]}\n"
        "\\newcommand{\\videourl}{}\n"
        "\\newcommand{\\videocoverpath}{}\n"
        "\\begin{document}\n"
        "\\title{\\notetitle}\n"
        "\\author{\\videochannel}\n"
        "\\date{\\videopublishdate}\n"
        "\\maketitle\n"
        "\\tableofcontents\n"
        "\\newpage\n"
        "%% --- NOTE CONTENT START --- %%\n"
        "\\end{document}\n"
    )


def _format_srt_timestamp(seconds: float) -> str:
    total_milliseconds = max(0, round(seconds * 1000))
    hours, remainder = divmod(total_milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, milliseconds = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{milliseconds:03d}"


def _render_portable_cjk_pdf(
    *,
    pdf_path: Path,
    title: str,
    sections: list[dict[str, Any]],
    video_url: str,
) -> None:
    try:
        _render_reportlab_cjk_pdf(
            pdf_path=pdf_path,
            title=title,
            sections=sections,
            video_url=video_url,
        )
    except ModuleNotFoundError:
        if any(section.get("image_refs") for section in sections):
            raise RuntimeError("ReportLab is required to render PDF frame evidence")
        # Development-only structural fallback; deployment installs ReportLab and a CJK font.
        pass
    else:
        return

    page_lines: list[list[tuple[str, int]]] = [[]]

    def append_line(text: str, font_size: int = 11) -> None:
        normalized = re.sub(r"\s+", " ", text).strip()
        if not normalized:
            page_lines[-1].append(("", font_size))
            return
        for wrapped in _wrap_pdf_text(normalized, 42 if font_size <= 11 else 30):
            if len(page_lines[-1]) >= 42:
                page_lines.append([])
            page_lines[-1].append((wrapped, font_size))

    append_line(title, 20)
    if video_url:
        append_line(f"Source: {video_url}", 9)
    append_line("", 8)
    for section in sections:
        append_line(str(section.get("heading") or "Untitled Section"), 15)
        body = str(section.get("body") or "")
        for paragraph in body.splitlines():
            append_line(paragraph, 11)
        append_line("", 8)

    content_streams: list[bytes] = []
    for lines in page_lines:
        commands: list[str] = []
        y = 800
        for line, font_size in lines:
            if not line:
                y -= 10
                continue
            safe_text = "".join(character if ord(character) <= 0xFFFF else "?" for character in line)
            encoded_text = safe_text.encode("utf-16-be").hex().upper()
            commands.append(f"BT /F1 {font_size} Tf 50 {y} Td <{encoded_text}> Tj ET")
            y -= max(14, font_size + 6)
        content_streams.append(("\n".join(commands) + "\n").encode("ascii"))

    page_object_ids = [5 + index * 2 for index in range(len(content_streams))]
    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        f"<< /Type /Pages /Count {len(page_object_ids)} /Kids [{' '.join(f'{item} 0 R' for item in page_object_ids)}] >>".encode("ascii"),
        b"<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [4 0 R] >>",
        b"<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> >>",
    ]
    for index, stream in enumerate(content_streams):
        content_id = 6 + index * 2
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>".encode("ascii")
        )
        objects.append(
            f"<< /Length {len(stream)} >>\nstream\n".encode("ascii")
            + stream
            + b"endstream"
        )

    output = bytearray(b"%PDF-1.4\n%\xE2\xE3\xCF\xD3\n")
    offsets = [0]
    for object_id, object_body in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{object_id} 0 obj\n".encode("ascii"))
        output.extend(object_body)
        output.extend(b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    pdf_path.write_bytes(bytes(output))


def _wrap_pdf_text(text: str, limit: int) -> list[str]:
    if len(text) <= limit:
        return [text]
    return [text[index:index + limit] for index in range(0, len(text), limit)]


def _render_reportlab_cjk_pdf(
    *,
    pdf_path: Path,
    title: str,
    sections: list[dict[str, Any]],
    video_url: str,
) -> None:
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen import canvas
    from reportlab.lib.utils import ImageReader

    font_path = _resolve_cjk_font_path()
    font_name = "NoteWeaveCJK"
    if font_name not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(font_name, str(font_path)))

    pdf_path.parent.mkdir(parents=True, exist_ok=True)
    page_width, page_height = A4
    document = canvas.Canvas(str(pdf_path), pagesize=A4, pageCompression=1)
    document.setTitle(title)
    document.setAuthor("NoteWeave Artifact Agent")
    page_no = 1
    y = page_height - 54

    def draw_footer() -> None:
        document.setFont("Helvetica", 8)
        document.setFillColorRGB(0.45, 0.45, 0.45)
        document.drawCentredString(page_width / 2, 24, f"NoteWeave Artifact · {page_no}")
        document.setFillColorRGB(0, 0, 0)

    def new_page() -> None:
        nonlocal page_no, y
        draw_footer()
        document.showPage()
        page_no += 1
        y = page_height - 54

    def draw_text(text: str, font_size: int, *, leading: int | None = None) -> None:
        nonlocal y
        normalized = re.sub(r"\s+", " ", text).strip()
        line_height = leading or max(15, font_size + 6)
        if not normalized:
            y -= max(8, line_height // 2)
            return
        for line in _wrap_reportlab_text(
            normalized,
            font_name=font_name,
            font_size=font_size,
            max_width=page_width - 100,
            pdfmetrics=pdfmetrics,
        ):
            if y < 58 + line_height:
                new_page()
            document.setFont(font_name, font_size)
            document.drawString(50, y, line)
            y -= line_height

    draw_text(title, 20, leading=28)
    if video_url:
        draw_text(f"来源：{video_url}", 9, leading=14)
    y -= 8
    for section in sections:
        draw_text(str(section.get("heading") or "未命名章节"), 15, leading=23)
        body = str(section.get("body") or "")
        for paragraph in body.splitlines() or [""]:
            draw_text(paragraph, 11, leading=18)
        for ref in section.get("image_refs", []):
            with Image.open(ref["path"]) as image:
                width, height = image.size
            scale = min((page_width - 100) / width, 300 / height)
            rendered_width, rendered_height = width * scale, height * scale
            if y < rendered_height + 75:
                new_page()
            document.drawImage(ImageReader(ref["path"]), 50, y - rendered_height,
                               width=rendered_width, height=rendered_height,
                               preserveAspectRatio=True)
            y -= rendered_height + 8
            draw_text(f"Frame {ref['file_id']} · {ref['at_ms']} ms", 9, leading=14)
        y -= 8
    draw_footer()
    document.save()


def _resolve_cjk_font_path() -> Path:
    candidates = [
        os.environ.get("NOTEWEAVE_PDF_FONT_PATH", ""),
        "/usr/share/fonts/truetype/arphic-gbsn00lp/gbsn00lp.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        r"C:\Windows\Fonts\simsun.ttc",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    raise ValueError(
        "No CJK PDF font found. Install fonts-arphic-gbsn00lp or set NOTEWEAVE_PDF_FONT_PATH."
    )


def _wrap_reportlab_text(
    text: str,
    *,
    font_name: str,
    font_size: int,
    max_width: float,
    pdfmetrics: Any,
) -> list[str]:
    lines: list[str] = []
    current = ""
    for character in text:
        candidate = current + character
        if current and pdfmetrics.stringWidth(candidate, font_name, font_size) > max_width:
            lines.append(current)
            current = character
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines or [""]


def main() -> int:
    parser = argparse.ArgumentParser(description="Bilibili Render PDF MCP server")
    parser.add_argument("--transport", default="stdio")
    args = parser.parse_args()
    if args.transport != "stdio":
        raise SystemExit("Only stdio transport is supported in this independent module.")
    return BilibiliRenderPdfServer().serve_stdio()


if __name__ == "__main__":
    raise SystemExit(main())
