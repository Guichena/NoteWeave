from __future__ import annotations

import subprocess
import time
import os
import re
import signal
from pathlib import Path


def remaining(deadline: float) -> float:
    seconds = deadline - time.monotonic()
    if seconds <= 0:
        raise TimeoutError("音视频解析超过总时限")
    return seconds


def run(command: list[str], directory: Path, deadline: float) -> str:
    """所有外部程序共用本次解析的截止时间，超时由 subprocess 终止进程。"""
    timeout = remaining(deadline)
    with subprocess.Popen(command, cwd=directory, text=True, encoding="utf-8", errors="replace",
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          start_new_session=os.name != "nt") as process:
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            # 容器中终止整个进程组，避免 yt-dlp 启动的 ffmpeg 在解析超时后继续下载或合并。
            if os.name != "nt":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.communicate()
            raise TimeoutError("音视频解析超过总时限") from exc
        code = process.returncode
    if code:
        # 外部程序可能输出字幕地址或凭据，错误中只给程序名、退出码和识别出的 HTTP 状态。
        raise ValueError(f"{Path(command[0]).name} 执行失败，退出码 {code}{_http_hint(stderr)}")
    return stdout if stdout else stderr


_HTTP_HINTS = {
    "412": "B 站拒绝了当前服务器出口的访问（HTTP 412 风控），需要配置 B 站 Cookie 或代理",
    "403": "目标站点拒绝访问（HTTP 403）",
    "404": "视频不存在或已下架（HTTP 404）",
}


def _http_hint(stderr: str | None) -> str:
    """只从错误输出里取 HTTP 状态码，不回传原始输出。"""
    match = re.search(r"HTTP Error (\d{3})", stderr or "")
    if not match:
        return ""
    return "：" + _HTTP_HINTS.get(match.group(1), f"HTTP {match.group(1)}")
