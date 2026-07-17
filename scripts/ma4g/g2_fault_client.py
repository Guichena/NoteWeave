"""Prepare one real MA4G completion and submit it once into the G2 DB kill."""

from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path

from app.agent_command_contract import ResearchAgentCommand
from app.agent_task_client import AgentTaskApiError, JavaResearchAgentTaskClient
from app.config import load_settings
from app.deep_cell_executor import DeepCellExecutor
from app.deterministic_fake_toolchain import DeterministicFakeToolchain
from app.research_agent_completion_contract import serialize_completion_envelope


_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def _atomic_json(path: Path, payload: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.replace(temporary, path)


def main() -> None:
    settings = load_settings()
    task_id = os.environ.get("MA4G_TASK_ID", "").strip()
    run_id = os.environ.get("MA4G_RUN_ID", "").strip()
    if not _UUID.fullmatch(task_id) or not _UUID.fullmatch(run_id):
        raise RuntimeError("G2 fixture requires canonical MA4G_TASK_ID and MA4G_RUN_ID")
    worker_id = settings.research_agent_worker_instance_id.strip()
    control = Path("/control")
    control.mkdir(parents=True, exist_ok=True)
    for marker in ("g2-ready.json", "g2-go", "g2-failure.json"):
        try:
            (control / marker).unlink()
        except FileNotFoundError:
            pass

    client = JavaResearchAgentTaskClient(
        settings.java_base_url,
        settings.internal_auth_token,
        worker_id,
        completion_max_attempts=1,
    )
    claim = client.claim(task_id, lease_seconds=settings.research_agent_lease_seconds)
    command = ResearchAgentCommand(
        schema_version="research-agent-command.v1",
        command_id="ma4g-g2-direct-fault-client",
        research_run_id=run_id,
        agent_task_id=task_id,
        idempotency_key=f"ma4g-g2:{task_id}:direct",
        delivery_attempt=1,
    )
    completion = DeepCellExecutor(
        client,
        worker_id,
        toolchain=DeterministicFakeToolchain(),
        enable_llm=False,
    )(command, claim)
    serialized = serialize_completion_envelope(completion)
    (control / "g2-envelope.json").write_bytes(serialized)
    _atomic_json(control / "g2-ready.json", {
        "event": "MA4G_G2_READY",
        "task_id": task_id,
        "worker_instance_id": worker_id,
        "lease_epoch": claim.lease_epoch,
        "fencing_token": claim.fencing_token,
        "envelope_digest": completion.envelope_digest,
        "envelope_size_bytes": len(serialized),
    })
    print("MA4G_G2_COMPLETION_READY", flush=True)

    deadline = time.monotonic() + 60
    while not (control / "g2-go").exists():
        if time.monotonic() >= deadline:
            raise RuntimeError("G2 fixture timed out waiting for the submit barrier")
        time.sleep(0.1)

    try:
        client.complete(completion)
    except AgentTaskApiError as exc:
        if exc.status_code < 500:
            raise RuntimeError(f"G2 expected connection/5xx failure, received {exc.status_code}") from exc
        _atomic_json(control / "g2-failure.json", {
            "event": "MA4G_G2_EXPECTED_COMPLETION_FAILURE",
            "status_code": exc.status_code,
            "error_code": exc.error_code,
            "envelope_digest": completion.envelope_digest,
        })
        print(f"MA4G_G2_EXPECTED_COMPLETION_FAILURE status={exc.status_code}", flush=True)
        return
    raise RuntimeError("G2 completion unexpectedly returned success before the DB connection kill")


if __name__ == "__main__":
    main()

