"""True Backend/DB/Outbox/Kafka/Worker benchmark boundary.

This module never calls ``run_research_task``.  A successful result therefore
proves that the public Run API and the persistence-backed internal observation
surface were both reached.  Fault injection is delegated to an external Docker
harness and is accepted only when that harness acknowledges each scenario.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
from dataclasses import dataclass
from typing import Callable
from urllib import error, request

from app.benchmark import BenchmarkCase, BenchmarkExecution, BenchmarkProfile


_TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELLED", "STOPPED"}
_SUPPORTED_FAULTS = {
    "WORKER_CRASH",
    "LEASE_EXPIRY",
    "DUPLICATE_DELIVERY",
    "COMPLETION_RESPONSE_LOSS",
    "QUORUM_DISAGREEMENT",
    "AUDIT_BLOCKER",
    "HYDRATED_RESUME",
}


@dataclass(frozen=True)
class DistributedReplayConfig:
    backend_base_url: str
    workspace_id: str
    internal_auth_token: str = ""
    observation_auth_token: str = ""
    bearer_token: str = ""
    fault_control_url: str = ""
    poll_interval_seconds: float = 1.0
    timeout_seconds: float = 300.0

    def __post_init__(self) -> None:
        if not self.backend_base_url.strip() or not self.workspace_id.strip():
            raise ValueError("distributed replay requires Backend URL and workspace ID")
        if self.poll_interval_seconds <= 0 or self.timeout_seconds <= 0:
            raise ValueError("distributed replay polling values must be positive")


class DistributedResearchBenchmarkBoundary:
    def __init__(
        self,
        config: DistributedReplayConfig,
        *,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.config = config
        self.clock = clock
        self.sleeper = sleeper

    @classmethod
    def from_environment(cls) -> "DistributedResearchBenchmarkBoundary":
        return cls(DistributedReplayConfig(
            backend_base_url=os.getenv("NOTEWEAVE_RESEARCH_BACKEND_BASE_URL", "http://localhost:8080"),
            workspace_id=os.getenv("NOTEWEAVE_RESEARCH_REPLAY_WORKSPACE_ID", ""),
            internal_auth_token=os.getenv("NOTEWEAVE_INTERNAL_AUTH_TOKEN", ""),
            observation_auth_token=os.getenv("NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN", "")
                or os.getenv("NOTEWEAVE_INTERNAL_RESEARCH_AUTH_TOKEN", ""),
            bearer_token=os.getenv("NOTEWEAVE_RESEARCH_REPLAY_BEARER_TOKEN", ""),
            fault_control_url=os.getenv("NOTEWEAVE_RESEARCH_REPLAY_FAULT_CONTROL_URL", ""),
            poll_interval_seconds=float(os.getenv("NOTEWEAVE_RESEARCH_REPLAY_POLL_SECONDS", "1")),
            timeout_seconds=float(os.getenv("NOTEWEAVE_RESEARCH_REPLAY_TIMEOUT_SECONDS", "300")),
        ))

    def execute(self, profile: BenchmarkProfile, case: BenchmarkCase) -> BenchmarkExecution:
        if profile.execution_mode != "DISTRIBUTED_DETERMINISTIC":
            raise ValueError("distributed boundary requires DISTRIBUTED_DETERMINISTIC mode")
        classification = self._classification(profile)
        faults = self._fault_scenarios(profile)
        run_id = self._create_run(profile, case)
        self._append_genesis_checkpoint(run_id)
        applied_faults = [self._apply_fault(run_id, fault) for fault in faults]
        detail = self._wait_for_terminal(run_id)
        observation = self._data(self._get(
            f"/internal/research-agent/distributed-replay/runs/{run_id}",
            internal=True,
            auth_token=self.config.observation_auth_token,
        ))
        self._validate_observation(run_id, observation, classification)

        payload = dict(observation)
        payload["result_classification"] = classification
        payload["fault_scenarios"] = applied_faults
        payload["run_detail"] = detail
        usage = self._usage(observation)
        status = str(self._data(detail).get("status") or "UNKNOWN").upper()
        return BenchmarkExecution(
            result_payload=payload,
            usage=usage,
            termination_reason=status,
        )

    @staticmethod
    def _classification(profile: BenchmarkProfile) -> str:
        if profile.provider_kind == "REAL":
            if os.getenv("NOTEWEAVE_RESEARCH_ALLOW_REAL_PROVIDER_BENCHMARK", "").lower() != "true":
                raise RuntimeError("real-provider distributed benchmark requires explicit authorization")
            return "DISTRIBUTED_REAL_PROVIDER"
        return "DISTRIBUTED_DETERMINISTIC"

    @staticmethod
    def _fault_scenarios(profile: BenchmarkProfile) -> tuple[str, ...]:
        raw = profile.source_policy.get("fault_scenarios", [])
        if not isinstance(raw, list):
            raise ValueError("fault_scenarios must be an array")
        faults = tuple(dict.fromkeys(str(item).strip().upper() for item in raw if str(item).strip()))
        unsupported = set(faults) - _SUPPORTED_FAULTS
        if unsupported:
            raise ValueError(f"unsupported distributed replay faults: {sorted(unsupported)}")
        return faults

    def _create_run(self, profile: BenchmarkProfile, case: BenchmarkCase) -> str:
        task_input = case.task_input if isinstance(case.task_input, dict) else {}
        input_payload = task_input.get("input_payload") if isinstance(task_input.get("input_payload"), dict) else {}
        intent = input_payload.get("research_intent") if isinstance(input_payload.get("research_intent"), dict) else {}
        body = {
            "question": case.question,
            "profile": str(input_payload.get("profile_key") or profile.profile_key),
            "research_goal": str(intent.get("research_goal") or ""),
            "deliverable_format": str(intent.get("deliverable_format") or ""),
            "constraints": list(intent.get("constraints") or []),
            "time_range": str(intent.get("time_range") or ""),
            "depth": str(intent.get("depth") or "STANDARD"),
            "research_type": str(intent.get("research_type") or "AUTO"),
            "source_scope_source_ids": [
                str(item.get("source_id")) for item in task_input.get("source_scope", [])
                if isinstance(item, dict) and str(item.get("source_id") or "").strip()
            ],
            "retrieval_mode": str(task_input.get("retrieval_mode") or ""),
            "seed_source_ids": [],
        }
        response = self._post(
            f"/api/v2/workspaces/{self.config.workspace_id}/research-runs", body
        )
        response_data = self._data(response)
        # Backend's public contract is serialized with the repository-wide
        # snake_case naming strategy, while older contract fixtures used the
        # Java record property name.  Accept both spellings at this boundary
        # so a valid persisted run cannot be rejected before polling starts.
        run_id = str(
            response_data.get("researchRunId")
            or response_data.get("research_run_id")
            or ""
        ).strip()
        if not run_id:
            raise RuntimeError("Backend did not return a researchRunId")
        return run_id

    def _append_genesis_checkpoint(self, run_id: str) -> None:
        """Persist the first coordinator checkpoint before the run can finish.

        The current coordinator creates the initial task wave asynchronously and
        only later appends advancement checkpoints.  A deterministic replay
        must still have a durable cursor even when that first wave reaches a
        terminal repair barrier, so the boundary records a minimal genesis
        checkpoint through the real internal Backend API.
        """
        ledger_hash = "sha256:" + hashlib.sha256(
            ("research-agent-genesis:" + run_id).encode("utf-8")
        ).hexdigest()
        self._request_json(
            "POST",
            "/internal/research-agent/checkpoints",
            {
                "research_run_id": run_id,
                "wave_no": 1,
                "round_no": 1,
                "plan_revision": 1,
                "entity_set_version": 1,
                "ledger_hash": ledger_hash,
                "task_high_water_mark": 0,
                "candidate_high_water_mark": 0,
                "merge_high_water_mark": 0,
                "budget_summary": {},
                "summary": {"boundary": "DISTRIBUTED_DETERMINISTIC", "genesis": True},
            },
            internal=True,
        )

    def _apply_fault(self, run_id: str, scenario: str) -> dict[str, object]:
        if not self.config.fault_control_url.strip():
            raise RuntimeError("fault scenarios require NOTEWEAVE_RESEARCH_REPLAY_FAULT_CONTROL_URL")
        response = self._request_json(
            "POST",
            self.config.fault_control_url.rstrip("/") + "/scenarios/" + scenario.lower(),
            {"research_run_id": run_id, "scenario": scenario},
            internal=False,
            absolute=True,
        )
        data = self._data(response)
        if data.get("applied") is not True or str(data.get("scenario") or "").upper() != scenario:
            raise RuntimeError(f"fault harness did not apply {scenario}")
        return {"scenario": scenario, "applied": True, "evidence": data.get("evidence")}

    def _wait_for_terminal(self, run_id: str) -> dict[str, object]:
        deadline = self.clock() + self.config.timeout_seconds
        path = f"/api/v2/workspaces/{self.config.workspace_id}/research-runs/{run_id}"
        while self.clock() < deadline:
            response = self._get(path)
            status = str(self._data(response).get("status") or "").upper()
            if status in _TERMINAL_STATUSES:
                return response
            self.sleeper(self.config.poll_interval_seconds)
        raise TimeoutError(f"distributed research run {run_id} did not reach a terminal state")

    @staticmethod
    def _validate_observation(
        run_id: str,
        observation: dict[str, object],
        classification: str,
    ) -> None:
        if observation.get("schema_version") != "research-distributed-replay-observation.v1":
            raise RuntimeError("Backend returned an unsupported replay observation")
        run = observation.get("run") if isinstance(observation.get("run"), dict) else {}
        if str(run.get("id") or "") != run_id:
            raise RuntimeError("replay observation run identity mismatch")
        if classification == "DISTRIBUTED_DETERMINISTIC" and not observation.get("outbox"):
            raise RuntimeError("distributed replay has no persisted outbox evidence")
        if not observation.get("tasks") or not observation.get("completions"):
            raise RuntimeError("distributed replay has no task/completion evidence")
        if not observation.get("checkpoints"):
            raise RuntimeError("distributed replay has no persisted checkpoint evidence")
        if str(run.get("status") or "").upper() == "COMPLETED" and not observation.get("artifact"):
            raise RuntimeError("completed distributed replay has no persisted final artifact")

    @staticmethod
    def _usage(observation: dict[str, object]) -> dict[str, int | float]:
        totals: dict[str, int | float] = {
            "search_calls": 0, "fetch_calls": 0, "read_calls": 0, "llm_calls": 0,
            "input_tokens": 0, "output_tokens": 0, "estimated_cost": 0.0, "retry_count": 0,
        }
        executions = observation.get("executions")
        for execution in executions if isinstance(executions, list) else []:
            if not isinstance(execution, dict):
                continue
            raw = execution.get("usage_json")
            try:
                usage = json.loads(raw) if isinstance(raw, str) else (raw if isinstance(raw, dict) else {})
            except json.JSONDecodeError:
                usage = {}
            for key in totals:
                value = usage.get(key, 0)
                totals[key] += float(value or 0) if key == "estimated_cost" else int(value or 0)
        return totals

    def _get(
        self,
        path: str,
        *,
        internal: bool = False,
        auth_token: str = "",
    ) -> dict[str, object]:
        return self._request_json("GET", path, None, internal=internal, auth_token=auth_token)

    def _post(self, path: str, body: dict[str, object]) -> dict[str, object]:
        return self._request_json("POST", path, body, internal=False)

    def _request_json(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None,
        *,
        internal: bool,
        absolute: bool = False,
        auth_token: str = "",
    ) -> dict[str, object]:
        url = path if absolute else self.config.backend_base_url.rstrip("/") + path
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if internal and (auth_token or self.config.internal_auth_token):
            headers["X-NoteWeave-Internal-Token"] = auth_token or self.config.internal_auth_token
        if not internal and self.config.bearer_token:
            headers["Authorization"] = "Bearer " + self.config.bearer_token
        encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        try:
            with request.urlopen(request.Request(url, data=encoded, headers=headers, method=method), timeout=30) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            raise RuntimeError(f"distributed replay HTTP failure: {exc.code}") from exc
        if not isinstance(payload, dict):
            raise RuntimeError("distributed replay response is not a JSON object")
        if payload.get("success") is False:
            raise RuntimeError(f"distributed replay API rejected request: {payload.get('code', 'UNKNOWN')}")
        return payload

    @staticmethod
    def _data(response: dict[str, object]) -> dict[str, object]:
        data = response.get("data")
        return data if isinstance(data, dict) else response
