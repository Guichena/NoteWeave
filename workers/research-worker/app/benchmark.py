"""MA5 fair, provider-aware Research Agent benchmark contracts."""

from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from statistics import mean
from typing import Callable, Protocol

from app.evaluation import evaluate_research_result


@dataclass(frozen=True)
class BenchmarkCase:
    case_key: str
    question: str
    schema: dict[str, object]
    gold_version: str
    source_snapshot_digest: str
    gold: dict[str, object]
    task_input: dict[str, object] | None = None


@dataclass(frozen=True)
class BenchmarkProfile:
    profile_key: str
    execution_mode: str
    provider_kind: str
    provider: str
    model: str
    source_policy: dict[str, object]
    budget: dict[str, object]
    random_policy: dict[str, object]
    rollout_no: int

    def __post_init__(self) -> None:
        if self.execution_mode not in {"SEQUENTIAL", "PARALLEL", "SPECULATIVE", "DISTRIBUTED_DETERMINISTIC"}:
            raise ValueError("unsupported benchmark execution mode")
        if self.provider_kind not in {"REAL", "SIMULATED"}:
            raise ValueError("provider_kind must be REAL or SIMULATED")
        if self.rollout_no < 1:
            raise ValueError("rollout_no must be positive")
        if not self.provider.strip() or not self.model.strip():
            raise ValueError("benchmark provider and model must not be blank")


@dataclass(frozen=True)
class BenchmarkExecution:
    result_payload: dict[str, object]
    usage: dict[str, int | float]
    http_429_count: int = 0
    http_5xx_count: int = 0
    termination_reason: str = "COMPLETED"


@dataclass(frozen=True)
class BenchmarkRecord:
    case_key: str
    profile_key: str
    execution_mode: str
    provider_kind: str
    rollout_no: int
    manifest_digest: str
    comparison_digest: str
    observed_source_snapshot_digest: str
    quality: dict[str, object]
    wall_clock_ms: float
    calls: dict[str, int]
    input_tokens: int
    output_tokens: int
    estimated_cost: float
    retry_count: int
    http_429_count: int
    http_5xx_count: int
    termination_reason: str
    result_classification: str = "IN_PROCESS_COMPONENT"


@dataclass(frozen=True)
class BenchmarkComparison:
    comparable: bool
    real_provider_evidence: bool
    verdict: str
    quality_improvement_claim_allowed: bool
    summaries: dict[str, dict[str, float]]


class BenchmarkExecutionBoundary(Protocol):
    def execute(self, profile: BenchmarkProfile, case: BenchmarkCase) -> BenchmarkExecution:
        ...


class BenchmarkRunner:
    def __init__(
        self,
        execution_boundary: BenchmarkExecutionBoundary,
        *,
        clock: Callable[[], float] = time.perf_counter,
    ) -> None:
        self.execution_boundary = execution_boundary
        self.clock = clock

    def run(self, profile: BenchmarkProfile, case: BenchmarkCase) -> BenchmarkRecord:
        manifest = _manifest(profile, case, include_mode=True)
        comparison_manifest = _manifest(profile, case, include_mode=False)
        started = self.clock()
        execution = self.execution_boundary.execute(profile, case)
        elapsed_ms = round(max(0.0, self.clock() - started) * 1000, 3)
        usage = execution.usage
        return BenchmarkRecord(
            case_key=case.case_key,
            profile_key=profile.profile_key,
            execution_mode=profile.execution_mode,
            provider_kind=profile.provider_kind,
            rollout_no=profile.rollout_no,
            manifest_digest=_digest(manifest),
            comparison_digest=_digest(comparison_manifest),
            observed_source_snapshot_digest=_observed_source_snapshot_digest(
                profile,
                case,
                execution.result_payload,
            ),
            quality=evaluate_research_result(case.gold, execution.result_payload),
            wall_clock_ms=elapsed_ms,
            calls={
                "search": _non_negative_int(usage.get("search_calls", 0)),
                "fetch": _non_negative_int(usage.get("fetch_calls", 0)),
                "read": _non_negative_int(usage.get("read_calls", 0)),
                "llm": _non_negative_int(usage.get("llm_calls", 0)),
            },
            input_tokens=_non_negative_int(usage.get("input_tokens", 0)),
            output_tokens=_non_negative_int(usage.get("output_tokens", 0)),
            estimated_cost=_non_negative_float(usage.get("estimated_cost", 0.0)),
            retry_count=_non_negative_int(usage.get("retry_count", 0)),
            http_429_count=_non_negative_int(execution.http_429_count),
            http_5xx_count=_non_negative_int(execution.http_5xx_count),
            termination_reason=execution.termination_reason.strip() or "UNKNOWN",
            result_classification=_result_classification(profile, execution.result_payload),
        )


class BenchmarkArchive:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def write(self, record: BenchmarkRecord) -> Path:
        path = (
            self.root
            / _safe_segment(record.case_key)
            / _safe_segment(record.execution_mode.lower())
            / f"rollout-{record.rollout_no:04d}.json"
        )
        payload = {
            "schema_version": "research-agent-benchmark-record.v2",
            "record": asdict(record),
        }
        encoded = (json.dumps(payload, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            if path.read_bytes() == encoded:
                return path
            raise FileExistsError(f"immutable benchmark artifact conflict: {path}")
        with path.open("xb") as handle:
            handle.write(encoded)
        return path


class BenchmarkComparator:
    def __init__(self, *, min_rollouts_per_mode: int = 4) -> None:
        if min_rollouts_per_mode < 1:
            raise ValueError("min_rollouts_per_mode must be positive")
        self.min_rollouts_per_mode = min_rollouts_per_mode

    def compare(self, records: list[BenchmarkRecord]) -> BenchmarkComparison:
        if not records:
            raise ValueError("benchmark records must not be empty")
        if len({item.comparison_digest for item in records}) != 1:
            raise ValueError("benchmark conditions differ")
        if len({item.observed_source_snapshot_digest for item in records}) != 1:
            raise ValueError("observed source snapshots differ")
        if len({item.case_key for item in records}) != 1 or len({item.provider_kind for item in records}) != 1:
            raise ValueError("benchmark conditions differ")
        grouped: dict[str, list[BenchmarkRecord]] = {}
        for item in records:
            grouped.setdefault(item.execution_mode, []).append(item)
        if "SEQUENTIAL" not in grouped or len(grouped) < 2:
            raise ValueError("benchmark comparison requires SEQUENTIAL and at least one candidate mode")
        if any(len(items) < self.min_rollouts_per_mode for items in grouped.values()):
            raise ValueError("benchmark comparison has insufficient rollouts")

        summaries = {mode: _summary(items) for mode, items in sorted(grouped.items())}
        real = records[0].provider_kind == "REAL"
        if not real:
            return BenchmarkComparison(True, False, "EXTERNAL_EVIDENCE_PENDING", False, summaries)

        baseline = summaries["SEQUENTIAL"]
        candidates = [summary for mode, summary in summaries.items() if mode != "SEQUENTIAL"]
        critical = ("exact_table_success_rate", "cell_value_accuracy", "citation_support_accuracy")
        non_inferior = all(
            candidate[key] >= baseline[key] - 0.02
            for candidate in candidates
            for key in critical
        )
        strict_gain = any(
            candidate[key] > baseline[key]
            for candidate in candidates
            for key in critical
        )
        reliable = all(candidate["http_error_rate"] <= baseline["http_error_rate"] for candidate in candidates)
        verdict = "QUALITY_NON_INFERIOR" if non_inferior and reliable else "REJECTED"
        return BenchmarkComparison(
            comparable=True,
            real_provider_evidence=True,
            verdict=verdict,
            quality_improvement_claim_allowed=non_inferior and reliable and strict_gain,
            summaries=summaries,
        )


class BenchmarkSuiteRunner:
    """Runs a fair immutable A/B suite instead of relying on hand-authored rollout files."""

    def __init__(
        self,
        execution_boundary: BenchmarkExecutionBoundary,
        archive_root: Path,
        *,
        comparator: BenchmarkComparator | None = None,
    ) -> None:
        self.runner = BenchmarkRunner(execution_boundary)
        self.archive = BenchmarkArchive(archive_root)
        self.comparator = comparator or BenchmarkComparator(min_rollouts_per_mode=4)

    def run(
        self,
        profile: BenchmarkProfile,
        case: BenchmarkCase,
        *,
        modes: tuple[str, ...] = ("SEQUENTIAL", "PARALLEL"),
        rollouts_per_mode: int = 4,
    ) -> tuple[tuple[BenchmarkRecord, ...], BenchmarkComparison]:
        if rollouts_per_mode < 4:
            raise ValueError("benchmark suite requires at least four rollouts per mode")
        normalized_modes = tuple(dict.fromkeys(str(mode).strip().upper() for mode in modes if str(mode).strip()))
        if "SEQUENTIAL" not in normalized_modes or len(normalized_modes) < 2:
            raise ValueError("benchmark suite requires SEQUENTIAL and at least one candidate mode")
        if any(mode not in {"SEQUENTIAL", "PARALLEL", "SPECULATIVE", "DISTRIBUTED_DETERMINISTIC"} for mode in normalized_modes):
            raise ValueError("benchmark suite contains an unsupported execution mode")

        records: list[BenchmarkRecord] = []
        for mode in normalized_modes:
            for rollout_no in range(1, rollouts_per_mode + 1):
                rollout_profile = replace(
                    profile,
                    profile_key=f"{profile.profile_key}-{mode.lower()}",
                    execution_mode=mode,
                    rollout_no=rollout_no,
                )
                record = self.runner.run(rollout_profile, case)
                self.archive.write(record)
                records.append(record)
        frozen_records = tuple(records)
        return frozen_records, self.comparator.compare(list(frozen_records))


def _manifest(profile: BenchmarkProfile, case: BenchmarkCase, *, include_mode: bool) -> dict[str, object]:
    manifest: dict[str, object] = {
        "schema_version": "research-agent-benchmark-manifest.v1",
        "case_key": case.case_key,
        "question": case.question,
        "schema": case.schema,
        "gold_version": case.gold_version,
        "source_snapshot_digest": case.source_snapshot_digest,
        "task_input_digest": _digest(case.task_input or {}),
        "provider_kind": profile.provider_kind,
        "provider": profile.provider,
        "model": profile.model,
        "source_policy": profile.source_policy,
        "budget": profile.budget,
        "random_policy": profile.random_policy,
    }
    if include_mode:
        manifest["execution_mode"] = profile.execution_mode
        manifest["profile_key"] = profile.profile_key
    return manifest


def _result_classification(
    profile: BenchmarkProfile,
    result_payload: dict[str, object],
) -> str:
    declared = str(result_payload.get("result_classification") or "").strip().upper()
    allowed = {
        "IN_PROCESS_COMPONENT",
        "DISTRIBUTED_DETERMINISTIC",
        "DISTRIBUTED_REAL_PROVIDER",
    }
    if declared:
        if declared not in allowed:
            raise ValueError("unsupported benchmark result classification")
        return declared
    if profile.execution_mode == "DISTRIBUTED_DETERMINISTIC":
        raise ValueError("distributed benchmark did not return a result classification")
    return "IN_PROCESS_COMPONENT"


def _digest(value: dict[str, object]) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _observed_source_snapshot_digest(
    profile: BenchmarkProfile,
    case: BenchmarkCase,
    result_payload: dict[str, object],
) -> str:
    if not bool(profile.source_policy.get("allow_external")):
        return case.source_snapshot_digest
    documents = result_payload.get("fetched_documents")
    observations = [
        {
            "source_id": str(item.get("source_id") or ""),
            "url": str(item.get("url") or ""),
            "snapshot_key": str(item.get("snapshot_key") or ""),
            "content_sha256": str(item.get("content_sha256") or ""),
        }
        for item in documents
        if isinstance(item, dict) and str(item.get("adapter") or "") == "external_url"
    ] if isinstance(documents, list) else []
    if not observations:
        return case.source_snapshot_digest
    observations.sort(key=lambda item: (
        item["source_id"], item["url"], item["snapshot_key"], item["content_sha256"]
    ))
    return "sha256:" + hashlib.sha256(
        json.dumps(observations, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _non_negative_int(value: object) -> int:
    if isinstance(value, bool):
        raise ValueError("benchmark integer metric must not be boolean")
    result = int(value)
    if result < 0:
        raise ValueError("benchmark integer metric must be non-negative")
    return result


def _non_negative_float(value: object) -> float:
    if isinstance(value, bool):
        raise ValueError("benchmark numeric metric must not be boolean")
    result = float(value)
    if result < 0:
        raise ValueError("benchmark numeric metric must be non-negative")
    return result


def _safe_segment(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip()).strip(".-")
    if not normalized:
        raise ValueError("benchmark artifact path segment must not be blank")
    return normalized


def _summary(records: list[BenchmarkRecord]) -> dict[str, float]:
    return {
        "rollout_count": float(len(records)),
        "exact_table_success_rate": round(mean(
            1.0 if item.quality.get("exact_table_success") is True else 0.0 for item in records
        ), 4),
        "cell_value_accuracy": round(mean(
            float(item.quality.get("cell_value_accuracy") or 0.0) for item in records
        ), 4),
        "citation_support_accuracy": round(mean(
            float(item.quality.get("citation_support_accuracy") or 0.0) for item in records
        ), 4),
        "avg_wall_clock_ms": round(mean(item.wall_clock_ms for item in records), 3),
        "avg_estimated_cost": round(mean(item.estimated_cost for item in records), 8),
        "http_error_rate": round(mean(
            1.0 if item.http_429_count + item.http_5xx_count > 0 else 0.0 for item in records
        ), 4),
    }
