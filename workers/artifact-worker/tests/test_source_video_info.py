"""链接预览失败要把上游真实原因带回 Host，而不是只留一条通用错误。"""

from __future__ import annotations

from app.main import _upstream_failure_message


def test_message_comes_from_the_nested_tool_error() -> None:
    inner = ValueError(
        "Error executing tool get_video_info: B 站拒绝了当前服务器出口的访问（HTTP 412 风控），需要配置 B 站 Cookie 或代理"
    )
    grouped = ExceptionGroup("unhandled errors in a TaskGroup", [inner])

    message = _upstream_failure_message(grouped)

    assert "HTTP 412" in message
    assert "Cookie 或代理" in message
    assert "TaskGroup" not in message


def test_plain_exception_text_is_used() -> None:
    assert _upstream_failure_message(RuntimeError("媒体解析服务不可达")) == "媒体解析服务不可达"


def test_empty_exception_falls_back_to_a_readable_message() -> None:
    message = _upstream_failure_message(RuntimeError(""))

    assert message == "媒体解析服务调用失败"
