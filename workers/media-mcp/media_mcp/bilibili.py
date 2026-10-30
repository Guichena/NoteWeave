from __future__ import annotations

import json
import re
import sys
import threading
import time
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import httpx

from .config import Settings
from .process import remaining, run


def video_url(raw: str, part: int | None = None) -> str:
    """仅接受 B 站标准视频地址，拒绝用户信息、其他端口和外部域名。"""
    parsed = urlsplit(raw.strip())
    match = re.fullmatch(r"/video/(BV[0-9A-Za-z]{10}|av[0-9]+)/?", parsed.path)
    if (parsed.scheme not in {"http", "https"} or parsed.hostname not in {"bilibili.com", "www.bilibili.com", "m.bilibili.com"}
            or parsed.username or parsed.password or parsed.port not in (None, 80, 443) or not match):
        raise ValueError("只支持 bilibili.com 的标准视频链接")
    query = parse_qs(parsed.query)
    chosen = part if part is not None else int(query.get("p", ["1"])[0])
    if type(chosen) is not int or not 1 <= chosen <= 1000:
        raise ValueError("视频分 P 必须在 1 到 1000 之间")
    return f"https://www.bilibili.com/video/{match.group(1)}?p={chosen}"


def command(settings: Settings, *arguments: str) -> list[str]:
    result = [sys.executable, "-m", "yt_dlp", "--no-warnings", "--socket-timeout", "20", "--retries", "1"]
    if settings.proxy:
        result += ["--proxy", settings.proxy]
    if settings.cookies_file:
        result += ["--cookies", settings.cookies_file]
    return result + list(arguments)


def metadata(url: str, directory: Path, deadline: float, settings: Settings, *, playlist=False) -> dict:
    # 复制旧 B 站服务的 yt-dlp 元数据获取方式，取消对仓库外脚本的依赖。
    output = run(command(settings, "--dump-single-json", "--skip-download",
                         "--yes-playlist" if playlist else "--no-playlist", url), directory, deadline)
    if len(output) > 16_000_000:
        raise ValueError("视频元数据超过大小上限")
    return json.loads(output)


def video_info(raw: str, directory: Path, deadline: float, settings: Settings) -> dict:
    url = video_url(raw)
    info = metadata(url.split("?")[0], directory, deadline, settings, playlist=True)
    entries = info.get("entries") or [info]
    if not entries or len(entries) > 1000:
        raise ValueError("视频分 P 数量无效或超过上限")
    parts = [{"part": index, "title": str(entry.get("title") or f"P{index}")[:300],
              "duration_ms": round(float(entry.get("duration") or 0) * 1000)}
             for index, entry in enumerate(entries, 1)]
    return {"title": str(info.get("title") or parts[0]["title"])[:300],
            "source_url": url.split("?")[0], "duration_ms": sum(part["duration_ms"] for part in parts), "parts": parts}


_download_lock = threading.Lock()
_last_download_at = 0.0

# playurl 会把同一路流分发到多个 CDN 镜像，主镜像对部分出口 IP 返回 567，
# 而备用镜像（如 Akamai）正常。这里只接受 B 站自己的媒体域名。
_STREAM_HOSTS = ("bilivideo.com", "bilivideo.cn", "akamaized.net", "hdslb.com", "bilibili.com")
_CODEC_PRIORITY = {7: 0, 13: 1, 12: 2}
_BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")


def _to_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _stream_url(stream) -> str:
    return (stream.get("baseUrl") or stream.get("base_url") or "") if isinstance(stream, dict) else ""


def _backup_urls(stream) -> list[str]:
    if not isinstance(stream, dict):
        return []
    return [url for url in (stream.get("backupUrl") or stream.get("backup_url") or [])
            if isinstance(url, str) and url]


def _pick_video(streams: list[dict]) -> dict:
    """优先 1080p 以内、编码兼容性最好的那一档。"""
    ordered = sorted(streams, key=lambda stream: (
        _to_int(stream.get("id")),
        -_CODEC_PRIORITY.get(_to_int(stream.get("codecid"), -1), 3),
        _to_int(stream.get("bandwidth"))))
    within = [stream for stream in ordered if _to_int(stream.get("id")) <= 80]
    return (within or ordered)[-1]


def _download_once(url: str, directory: Path, deadline: float, settings: Settings) -> None:
    run(command(settings, "--no-playlist", "--max-filesize", "1G", "--merge-output-format", "mp4",
                "-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
                "-o", str(directory / "video.%(ext)s"), url), directory, deadline)


def _playurl(url: str, directory: Path, deadline: float, settings: Settings) -> dict:
    """借 yt-dlp 的签名能力取一份完整的 playurl 响应。"""
    pages = directory / "pages"
    pages.mkdir(exist_ok=True)
    run(command(settings, "--write-pages", "--skip-download", "--no-playlist", url), pages, deadline)
    for dump in sorted(pages.glob("*playurl*")):
        raw = dump.read_text(encoding="utf-8", errors="replace")
        start = raw.find("{")
        if start < 0:
            continue
        try:
            document = json.loads(raw[start:])
        except json.JSONDecodeError:
            continue
        data = document.get("data") or {}
        if data.get("dash"):
            return data
    raise ValueError("未取到视频播放信息")


def _fetch(urls: list[str], target: Path, deadline: float, limit: int) -> None:
    """按主地址、备用地址的顺序逐个尝试。"""
    failure = "没有可用地址"
    for url in urls:
        parsed = urlsplit(url)
        host = parsed.hostname or ""
        if parsed.scheme != "https" or not any(host == suffix or host.endswith("." + suffix)
                                               for suffix in _STREAM_HOSTS):
            continue
        try:
            written = 0
            with httpx.Client(timeout=min(60, remaining(deadline)), follow_redirects=True) as client:
                with client.stream("GET", url, headers={"Referer": "https://www.bilibili.com/",
                                                        "User-Agent": _BROWSER_UA}) as response:
                    response.raise_for_status()
                    with target.open("wb") as handle:
                        for block in response.iter_bytes():
                            remaining(deadline)
                            written += len(block)
                            if written > limit:
                                raise ValueError("媒体超过大小上限")
                            handle.write(block)
            if written:
                return
            failure = "响应为空"
        except (httpx.HTTPError, ValueError) as error:
            failure = f"{type(error).__name__}: {error}"[:200]
            target.unlink(missing_ok=True)
    raise ValueError(f"媒体下载失败（{failure}）")


def _download_via_mirrors(url: str, directory: Path, deadline: float, settings: Settings) -> None:
    pages = directory / "pages"
    try:
        dash = _playurl(url, directory, deadline, settings).get("dash") or {}
        videos = [stream for stream in (dash.get("video") or []) if _stream_url(stream)]
        audios = [stream for stream in (dash.get("audio") or []) if _stream_url(stream)]
        if not videos or not audios:
            raise ValueError("播放信息缺少可下载的音视频流")
        video = _pick_video(videos)
        audio = max(audios, key=lambda stream: (_to_int(stream.get("id")), _to_int(stream.get("bandwidth"))))
        video_path = directory / "_v.mp4"
        audio_path = directory / "_a.m4a"
        _fetch([_stream_url(video)] + _backup_urls(video), video_path, deadline, 1_073_741_824)
        _fetch([_stream_url(audio)] + _backup_urls(audio), audio_path, deadline, 268_435_456)
        run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
             "-i", str(video_path), "-i", str(audio_path), "-c", "copy",
             "-movflags", "+faststart", str(directory / "video.mp4")], directory, deadline)
        video_path.unlink(missing_ok=True)
        audio_path.unlink(missing_ok=True)
    finally:
        if pages.exists():
            for dump in pages.iterdir():
                dump.unlink(missing_ok=True)
            pages.rmdir()


def download(url: str, directory: Path, deadline: float, settings: Settings) -> Path:
    """串行下载并强制最小间隔；主地址被 CDN 拒绝时改走备用镜像。"""
    global _last_download_at
    with _download_lock:
        if _last_download_at:
            wait = settings.download_interval - (time.monotonic() - _last_download_at)
            if wait > 0:
                time.sleep(min(wait, remaining(deadline)))
        attempt = 0
        while True:
            try:
                _download_once(url, directory, deadline, settings)
                break
            except ValueError:
                attempt += 1
                for stale in directory.glob("video.*"):
                    stale.unlink(missing_ok=True)
                if attempt == 1:
                    # yt-dlp 只认 playurl 的主地址，主地址被拒时重试没有意义，直接换镜像。
                    try:
                        _download_via_mirrors(url, directory, deadline, settings)
                        break
                    except ValueError:
                        pass
                if attempt > settings.download_retries:
                    raise
                time.sleep(min(60 * attempt, remaining(deadline)))
        _last_download_at = time.monotonic()
    files = [path for path in directory.glob("video.*") if path.suffix in {".mp4", ".mkv", ".webm", ".flv"}]
    if len(files) != 1 or not 0 < files[0].stat().st_size <= 1_073_741_824:
        raise ValueError("下载的视频缺失或超过 1 GiB 上限")
    return files[0]


def subtitles(info: dict, deadline: float) -> list[dict]:
    """优先中文官方字幕；没有可用字幕时由调用方启动本地 ASR。"""
    languages = info.get("subtitles") or {}
    for language in sorted(languages, key=lambda name: (not name.startswith(("zh", "ai-zh")), name)):
        for item in languages[language]:
            url = item.get("url", "")
            if url.startswith("//"):
                url = "https:" + url
            parsed = urlsplit(url)
            host = parsed.hostname or ""
            if parsed.scheme != "https" or not any(host == domain or host.endswith("." + domain)
                                                   for domain in ("hdslb.com", "bilibili.com")):
                continue
            try:
                with httpx.Client(timeout=min(30, remaining(deadline)), follow_redirects=False) as client:
                    with client.stream("GET", url) as response:
                        response.raise_for_status()
                        content = bytearray()
                        for block in response.iter_bytes():
                            remaining(deadline)
                            content.extend(block)
                            if len(content) > 20_000_000:
                                raise ValueError("字幕超过大小上限")
                body = json.loads(content).get("body", [])
                segments = [{"start_ms": round(float(row["from"]) * 1000),
                             "end_ms": round(float(row["to"]) * 1000), "text": str(row["content"]).strip()}
                            for row in body if str(row.get("content", "")).strip()]
                if segments:
                    return segments
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                continue
    return []
