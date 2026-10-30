from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def integer(name: str, default: int, low: int, high: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if not low <= value <= high:
        raise ValueError(f"{name} 必须在 {low} 到 {high} 之间")
    return value


@dataclass(frozen=True)
class Settings:
    input_root: Path
    token: str
    timeout: int
    max_duration_ms: int
    max_chars: int
    max_frames: int
    max_candidates: int
    phash_threshold: int
    scene_threshold: float
    model: str
    language: str
    device: str
    model_cache: str
    vision_enabled: bool
    vision_endpoint: str
    vision_model: str
    vision_api_key: str
    vision_concurrency: int
    cookies_file: str
    proxy: str
    download_interval: int
    download_retries: int

    @classmethod
    def load(cls) -> "Settings":
        scene = float(os.environ.get("MEDIA_MCP_SCENE_THRESHOLD", "0.3"))
        if not 0 < scene < 1:
            raise ValueError("场景检测阈值必须在 0 到 1 之间")
        return cls(
            Path(os.environ.get("MEDIA_MCP_INPUT_ROOT", "../artifact-worker/runtime/inputs")).resolve(),
            os.environ.get("MEDIA_MCP_AUTH_TOKEN", ""),
            integer("MEDIA_MCP_TIMEOUT_SECONDS", 3600, 30, 7200),
            integer("MEDIA_MCP_MAX_DURATION_MS", 7_200_000, 1000, 86_400_000),
            integer("MEDIA_MCP_MAX_CHARS", 2_000_000, 1000, 20_000_000),
            integer("MEDIA_MCP_MAX_FRAMES", 128, 1, 512),
            integer("MEDIA_MCP_MAX_CANDIDATES", 4096, 1, 16384),
            integer("MEDIA_MCP_PHASH_THRESHOLD", 8, 0, 64), scene,
            os.environ.get("MEDIA_MCP_ASR_MODEL", "small"),
            os.environ.get("MEDIA_MCP_ASR_LANGUAGE", "zh"),
            os.environ.get("MEDIA_MCP_ASR_DEVICE", "cpu"),
            os.environ.get("MEDIA_MCP_MODEL_CACHE", "/model-cache"),
            os.environ.get("MEDIA_MCP_VISION_ENABLED", "false").lower() == "true",
            os.environ.get("MEDIA_MCP_VISION_ENDPOINT", "https://vision.example.invalid/v1/chat/completions"),
            os.environ.get("MEDIA_MCP_VISION_MODEL", "placeholder"),
            os.environ.get("MEDIA_MCP_VISION_API_KEY", ""),
            integer("MEDIA_MCP_VISION_CONCURRENCY", 8, 1, 16),
            os.environ.get("MEDIA_MCP_BILIBILI_COOKIES_FILE", ""),
            os.environ.get("MEDIA_MCP_PROXY", ""),
            integer("MEDIA_MCP_DOWNLOAD_INTERVAL_SECONDS", 120, 0, 3600),
            integer("MEDIA_MCP_DOWNLOAD_RETRIES", 2, 0, 5),
        )
