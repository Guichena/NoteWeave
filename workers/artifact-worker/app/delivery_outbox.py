"""已完成生成的本地投递箱（NR09）。

生成成功但进度/完成回调持续失败时，Kafka 不提交、消息重投。此前每次重投都
重新取输入并完整执行生成（可能重复调用模型、阻塞分区）。本模块把「生成已完成
的事件与结果」按 task_id 持久到本地文件：重投时跳过生成、只重发相同载荷的回调；
预算（投递尝试次数）耗尽后由调用方按宿主终态协议上报失败。

存储为单文件 JSON、原子替换写入；目录来自 settings 的 runtime 目录。
仅使用标准库，便于在无第三方依赖的环境验证。
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path

_SCHEMA_VERSION = 1
_TASK_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


class DeliveryOutbox:
    def __init__(self, directory: str | Path) -> None:
        self._directory = Path(directory)
        self._directory.mkdir(parents=True, exist_ok=True)

    def _path(self, task_id: str) -> Path:
        if not _TASK_ID_PATTERN.match(task_id or ""):
            raise ValueError("invalid task id for delivery outbox")
        return self._directory / f"delivery-{task_id}.json"

    def load(self, task_id: str) -> dict | None:
        """读取已保存的生成结果（events/result JSON 字典）；无记录返回 None。"""
        path = self._path(task_id)
        if not path.exists():
            return None
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if record.get("schema_version") != _SCHEMA_VERSION:
            return None
        return record

    def save(self, task_id: str, events: list[dict], result: dict, attempts: int) -> None:
        """保存生成结果与当前投递尝试计数（原子替换）。"""
        record = {
            "schema_version": _SCHEMA_VERSION,
            "task_id": task_id,
            "attempts": max(0, int(attempts)),
            "events": list(events),
            "result": result,
        }
        self._atomic_write(self._path(task_id), json.dumps(record, ensure_ascii=False))

    def bump_attempts(self, task_id: str) -> int:
        """递增并返回本任务已用的投递尝试次数（从 1 开始）。"""
        record = self.load(task_id) or {"schema_version": _SCHEMA_VERSION, "task_id": task_id,
                                        "attempts": 0, "events": [], "result": {}}
        attempts = int(record.get("attempts") or 0) + 1
        record["attempts"] = attempts
        self._atomic_write(self._path(task_id), json.dumps(record, ensure_ascii=False))
        return attempts

    def remove(self, task_id: str) -> None:
        """投递成功（或任务被接管/终态）后清除记录。"""
        try:
            self._path(task_id).unlink(missing_ok=True)
        except OSError:
            pass

    @staticmethod
    def _atomic_write(path: Path, text: str) -> None:
        fd, temp_name = tempfile.mkstemp(dir=str(path.parent), prefix=".delivery-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(text)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, path)
        except OSError:
            try:
                os.unlink(temp_name)
            except OSError:
                pass
            raise


def default_delivery_outbox(settings) -> DeliveryOutbox:
    """按 settings 的 runtime 目录派生投递箱目录（与 repository 文件同根）。"""
    repository_dir = Path(settings.artifact_repository_file_path).expanduser().parent
    return DeliveryOutbox(repository_dir / "delivery-outbox")
