"""研究任务内各环节的耗时统计。

只在 Worker 本地累计并写日志，不进入完成信封：信封摘要要求同样的输入得到同样的内容，
耗时每次都不同，放进信封会让重放和幂等比对失效。
"""

from __future__ import annotations

import json
import logging
import threading
import time
from contextlib import contextmanager
from typing import Iterator

_LOG = logging.getLogger("app.research_timing")

# 单个环节超过这个耗时就单独打一条告警日志，方便直接定位卡住的环节
SLOW_PHASE_WARN_MS = 60_000


class PhaseTimer:
    """按环节累计调用次数、总耗时和单次最长耗时；多线程并发记录时加锁。"""

    def __init__(self, *, clock=time.monotonic) -> None:
        self._clock = clock
        self._lock = threading.Lock()
        self._started = clock()
        self._phases: dict[str, dict[str, int]] = {}

    @contextmanager
    def phase(self, name: str) -> Iterator[None]:
        started = self._clock()
        failed = False
        try:
            yield
        except BaseException:
            failed = True
            raise
        finally:
            self.record(name, int((self._clock() - started) * 1000), failed=failed)

    def record(self, name: str, elapsed_ms: int, *, failed: bool = False) -> None:
        elapsed_ms = max(0, int(elapsed_ms))
        with self._lock:
            item = self._phases.setdefault(name, {"count": 0, "total_ms": 0, "max_ms": 0, "failed": 0})
            item["count"] += 1
            item["total_ms"] += elapsed_ms
            item["max_ms"] = max(item["max_ms"], elapsed_ms)
            if failed:
                item["failed"] += 1

    def elapsed_ms(self) -> int:
        return max(0, int((self._clock() - self._started) * 1000))

    def snapshot(self) -> dict[str, dict[str, int]]:
        with self._lock:
            return {name: dict(values) for name, values in sorted(self._phases.items())}


class TimedToolchain:
    """包一层深挖工具链，search、fetch、read、extract 每次调用都计时；其余属性原样转给内部工具链。"""

    def __init__(self, inner: object, timer: PhaseTimer) -> None:
        self._inner = inner
        self._timer = timer

    def search(self, *args, **kwargs):
        with self._timer.phase("search"):
            return self._inner.search(*args, **kwargs)

    def fetch(self, *args, **kwargs):
        with self._timer.phase("fetch"):
            return self._inner.fetch(*args, **kwargs)

    def read(self, *args, **kwargs):
        with self._timer.phase("read"):
            return self._inner.read(*args, **kwargs)

    def extract(self, *args, **kwargs):
        with self._timer.phase("extract"):
            return self._inner.extract(*args, **kwargs)

    def __getattr__(self, name: str):
        # executed_queries 等由内部工具链在调用时写入，读取时直接取内部对象上的值
        return getattr(self._inner, name)


class TimedPermitClient:
    """包一层许可客户端：向 Java 申请许可、归档网页、检索工作台资料各自计时。"""

    _TIMED = {
        "require_permit": "permit",
        "archive_external_snapshot": "archive",
        "archived_external_snapshot_inventory": "archive_inventory",
        "search_workspace_windows": "workspace_search",
    }

    def __init__(self, inner: object, timer: PhaseTimer) -> None:
        self._inner = inner
        self._timer = timer

    def __getattr__(self, name: str):
        target = getattr(self._inner, name)
        phase = self._TIMED.get(name)
        if phase is None or not callable(target):
            return target

        def timed(*args, **kwargs):
            with self._timer.phase(phase):
                return target(*args, **kwargs)

        return timed


def log_task_timing(timer: PhaseTimer, *, task_id: str, research_run_id: str, role: str, outcome: str) -> dict[str, object]:
    """任务结束时写一条结构化日志，按环节列出次数与耗时；返回写出的内容便于测试。"""
    phases = timer.snapshot()
    payload: dict[str, object] = {
        "task_id": task_id,
        "research_run_id": research_run_id,
        "role": role,
        "outcome": outcome,
        "total_ms": timer.elapsed_ms(),
        "phases": phases,
    }
    _LOG.info("research_task_timing %s", json.dumps(payload, ensure_ascii=False, sort_keys=True))
    slow = {name: values["max_ms"] for name, values in phases.items() if values["max_ms"] >= SLOW_PHASE_WARN_MS}
    if slow:
        _LOG.warning("research_task_slow_phase task_id=%s role=%s phases=%s",
                     task_id, role, json.dumps(slow, sort_keys=True))
    return payload
