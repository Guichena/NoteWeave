"""产物任务失败时，失败说明里带上未通过的检查，便于排查。"""

from app.callback import failure_detail


def test_failed_checks_and_validation_errors_are_summarized():
    trace = {
        "node_id": "quiz_writer",
        "validation_errors": ["questions 至少需要 5 道题"],
        "verification": {"failed_checks": ["章节 题目 的陈述 statement-1 缺少充分证据：UNSUPPORTED"]},
    }

    detail = failure_detail(trace)

    assert "缺少充分证据" in detail
    assert "quiz_writer questions 至少需要 5 道题" in detail


def test_missing_or_unknown_trace_gives_no_detail():
    assert failure_detail(None) == ""
    assert failure_detail({"status": "FAILED"}) == ""
    assert len(failure_detail({"failed_checks": ["x" * 1000]})) <= 600
