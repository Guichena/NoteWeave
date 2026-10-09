import sys
from pathlib import Path
from types import SimpleNamespace

from app import agent_kafka_consumer as consumer


def test_child_environments_should_share_group_but_own_instance_and_health_file(tmp_path: Path) -> None:
    envs = consumer.consumer_child_environments(
        {"NOTEWEAVE_KAFKA_RESEARCH_AGENT_GROUP_ID": "group-a"}, 3, "research-agent-worker-1", tmp_path)

    assert [env["NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID"] for env in envs] == [
        "research-agent-worker-1-1", "research-agent-worker-1-2", "research-agent-worker-1-3"]
    assert len({env["NOTEWEAVE_RESEARCH_AGENT_HEALTH_FILE"] for env in envs}) == 3
    assert all(env["NOTEWEAVE_KAFKA_RESEARCH_AGENT_GROUP_ID"] == "group-a" for env in envs)
    assert all(env["NOTEWEAVE_RESEARCH_AGENT_CONSUMER_CHILD"] == "1" for env in envs)


def test_supervisor_should_stop_all_children_when_one_exits(monkeypatch, tmp_path: Path) -> None:
    started = []

    class FakeChild:
        def __init__(self, args, env):
            self.index = len(started)
            self.returncode = None
            self.terminated = False
            started.append(self)

        def poll(self):
            # 第一个子进程立即退出，其余一直运行直到被终止
            if self.index == 0:
                self.returncode = 3
            return self.returncode

        def send_signal(self, signum):
            pass

        def terminate(self):
            self.terminated = True
            self.returncode = -15

        def wait(self, timeout=None):
            return self.returncode

    monkeypatch.setattr(consumer.subprocess, "Popen", FakeChild)
    monkeypatch.setattr(consumer, "load_settings", lambda: SimpleNamespace(
        research_agent_worker_instance_id="worker-x"))
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_HEALTH_FILE", str(tmp_path / "ready"))
    monkeypatch.setattr(consumer.signal, "signal", lambda *args: None)

    exit_code = consumer.run_consumer_supervisor(3)

    assert exit_code == 3
    assert len(started) == 3
    assert all(child.terminated for child in started[1:])
    assert not (tmp_path / "ready").exists()


def test_main_should_supervise_only_for_incremental_mode_with_concurrency(monkeypatch) -> None:
    calls = []
    monkeypatch.delenv("NOTEWEAVE_RESEARCH_AGENT_CONSUMER_CHILD", raising=False)
    monkeypatch.setattr(sys, "argv", ["consumer"])
    monkeypatch.setattr(consumer, "run_consumer_supervisor", lambda n: calls.append(n) or 0)
    monkeypatch.setattr(consumer, "run_research_agent_kafka_consumer_forever", lambda *a: calls.append("single"))

    monkeypatch.setattr(consumer, "load_settings", lambda: SimpleNamespace(
        research_agent_max_concurrency=3, research_agent_consumer_enabled=True,
        research_agent_execution_mode="INCREMENTAL_V1"))
    try:
        consumer.main()
    except SystemExit as exit_signal:
        assert exit_signal.code == 0
    monkeypatch.setattr(consumer, "load_settings", lambda: SimpleNamespace(
        research_agent_max_concurrency=1, research_agent_consumer_enabled=True,
        research_agent_execution_mode="INCREMENTAL_V1"))
    consumer.main()

    assert calls == [3, "single"]
