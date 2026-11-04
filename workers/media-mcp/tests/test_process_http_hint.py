"""验收回归：B 站对服务器出口返回 412 时，错误里要能看出原因，且不回传原始输出。"""

import sys
import time

import pytest

from media_mcp.process import run


def test_failed_command_reports_http_status_without_raw_output(tmp_path):
    script = "import sys; sys.stderr.write('ERROR: Unable to download webpage: HTTP Error 412: Precondition Failed https://x/?token=secret'); sys.exit(1)"
    with pytest.raises(ValueError) as raised:
        run([sys.executable, "-c", script], tmp_path, time.monotonic() + 30)
    message = str(raised.value)
    assert "退出码 1" in message
    assert "HTTP 412" in message
    assert "secret" not in message


def test_failed_command_without_http_status_keeps_generic_message(tmp_path):
    with pytest.raises(ValueError, match=r"执行失败，退出码 2$"):
        run([sys.executable, "-c", "import sys; sys.exit(2)"], tmp_path, time.monotonic() + 30)
