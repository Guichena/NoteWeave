"""研究任务各环节耗时统计。"""

from __future__ import annotations

import logging

from app.tool_timing import PhaseTimer, TimedPermitClient, TimedToolchain, log_task_timing


def test_phase_timer_accumulates_counts_and_durations() -> None:
    ticks = iter([0.0, 1.0, 1.5, 2.0, 4.0, 5.0, 6.0])
    timer = PhaseTimer(clock=lambda: next(ticks))
    with timer.phase("search"):
        pass
    with timer.phase("search"):
        pass
    try:
        with timer.phase("fetch"):
            raise RuntimeError("boom")
    except RuntimeError:
        pass

    assert timer.snapshot() == {
        "fetch": {"count": 1, "total_ms": 1000, "max_ms": 1000, "failed": 1},
        "search": {"count": 2, "total_ms": 2500, "max_ms": 2000, "failed": 0},
    }


def test_timed_wrappers_time_each_tool_and_forward_other_attributes(caplog) -> None:
    class Toolchain:
        executed_queries = ["q1"]

        def search(self, *args, **kwargs):
            return ["hit"]

        def fetch(self, *args, **kwargs):
            return ["doc"]

        def read(self, *args, **kwargs):
            return ["window"]

        def extract(self, *args, **kwargs):
            return "result"

    class Permits:
        def require_permit(self, snapshot, tool):
            return None

        def report_delivery_failure(self, *args):
            return "untimed"

    timer = PhaseTimer()
    toolchain = TimedToolchain(Toolchain(), timer)
    permits = TimedPermitClient(Permits(), timer)
    assert toolchain.search() == ["hit"]
    assert toolchain.fetch() == ["doc"]
    assert toolchain.read() == ["window"]
    assert toolchain.extract() == "result"
    assert toolchain.executed_queries == ["q1"]
    permits.require_permit(None, "search")
    assert permits.report_delivery_failure() == "untimed"

    phases = timer.snapshot()
    assert set(phases) == {"search", "fetch", "read", "extract", "permit"}
    assert all(values["count"] == 1 for values in phases.values())

    with caplog.at_level(logging.INFO, logger="app.research_timing"):
        payload = log_task_timing(timer, task_id="t1", research_run_id="r1", role="DEEP_CELL", outcome="OK")
    assert payload["phases"] == phases
    assert any("research_task_timing" in record.getMessage() and '"task_id": "t1"' in record.getMessage()
               for record in caplog.records)


def test_timing_log_is_visible_without_any_logging_configuration(monkeypatch, capsys) -> None:
    import app.tool_timing as tool_timing

    root = logging.getLogger()
    monkeypatch.setattr(root, "handlers", [])
    logger = logging.getLogger("app.research_timing.visibility-test")
    monkeypatch.setattr(tool_timing, "_LOG", logger)
    try:
        log_task_timing(PhaseTimer(), task_id="t2", research_run_id="r2", role="DEEP_CELL", outcome="OK")
        assert "research_task_timing" in capsys.readouterr().err
    finally:
        logger.handlers.clear()
        logger.propagate = True
