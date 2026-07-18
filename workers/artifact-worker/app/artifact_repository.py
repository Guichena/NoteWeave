from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock
from typing import Protocol

from app.models import ArtifactCommitReceipt, ArtifactTaskResult, RetrievalFeedback


class ArtifactRepositoryBackend(Protocol):
    backend_type: str

    def clear(self) -> None: ...
    def reserve_next_version(self, target_id: str) -> tuple[str, str]: ...
    def commit_result(
        self,
        version_record: dict[str, object],
        retrieval_record: dict[str, object],
    ) -> tuple[int, int]: ...
    def update_version_runtime_trace(
        self,
        *,
        target_id: str,
        version_id: str,
        runtime_trace: dict[str, object],
    ) -> None: ...
    def list_versions(
        self,
        *,
        target_id: str | None = None,
        skill_key: str | None = None,
        action_key: str | None = None,
        status: str | None = None,
    ) -> list[dict[str, object]]: ...
    def list_retrieval_entries(
        self,
        *,
        target_id: str | None = None,
        version_id: str | None = None,
    ) -> list[dict[str, object]]: ...
    def get_version_detail(self, *, target_id: str, version_id: str) -> dict[str, object]: ...
    def get_retrieval_detail(
        self,
        *,
        target_id: str,
        retrieval_entry_id: str,
    ) -> dict[str, object]: ...
    def rollback_version(self, *, target_id: str, version_id: str) -> dict[str, object]: ...
    def info(self) -> dict[str, object]: ...


def _filter_version_records(
    records: list[dict[str, object]],
    *,
    target_id: str | None = None,
    skill_key: str | None = None,
    action_key: str | None = None,
    status: str | None = None,
) -> list[dict[str, object]]:
    filtered_records = records
    if target_id is not None:
        filtered_records = [
            record for record in filtered_records if record["target_id"] == target_id
        ]
    if skill_key is not None:
        filtered_records = [
            record for record in filtered_records if record.get("skill_key", "") == skill_key
        ]
    elif action_key is not None:
        filtered_records = [
            record for record in filtered_records if record["action_key"] == action_key
        ]
    if status is not None:
        filtered_records = [
            record for record in filtered_records if record["status"] == status
        ]
    return filtered_records


@dataclass
class InMemoryArtifactRepositoryBackend:
    backend_type: str = "memory"
    versions: list[dict[str, object]] = field(default_factory=list)
    retrieval_entries: list[dict[str, object]] = field(default_factory=list)
    lock: Lock = field(default_factory=Lock)

    def clear(self) -> None:
        with self.lock:
            self.versions.clear()
            self.retrieval_entries.clear()

    def reserve_next_version(self, target_id: str) -> tuple[str, str]:
        with self.lock:
            target_versions = [record for record in self.versions if record["target_id"] == target_id]
            next_index = len(target_versions) + 1
            parent_version_id = str(target_versions[-1]["version_id"]) if target_versions else ""
            return f"{target_id}-v{next_index}", parent_version_id

    def commit_result(
        self,
        version_record: dict[str, object],
        retrieval_record: dict[str, object],
    ) -> tuple[int, int]:
        with self.lock:
            self.versions.append(version_record)
            self.retrieval_entries.append(retrieval_record)
            return len(self.versions), len(self.retrieval_entries)

    def update_version_runtime_trace(
        self,
        *,
        target_id: str,
        version_id: str,
        runtime_trace: dict[str, object],
    ) -> None:
        with self.lock:
            record = next(
                (
                    item
                    for item in self.versions
                    if item["target_id"] == target_id and item["version_id"] == version_id
                ),
                None,
            )
            if record is None:
                raise ValueError(f"artifact version not found: {version_id}")
            record["runtime_trace"] = runtime_trace

    def list_versions(
        self,
        *,
        target_id: str | None = None,
        skill_key: str | None = None,
        action_key: str | None = None,
        status: str | None = None,
    ) -> list[dict[str, object]]:
        with self.lock:
            records = list(self.versions)
        return _filter_version_records(
            records,
            target_id=target_id,
            skill_key=skill_key,
            action_key=action_key,
            status=status,
        )

    def list_retrieval_entries(
        self,
        *,
        target_id: str | None = None,
        version_id: str | None = None,
    ) -> list[dict[str, object]]:
        with self.lock:
            records = list(self.retrieval_entries)
        if target_id is not None:
            records = [record for record in records if record["target_id"] == target_id]
        if version_id is not None:
            records = [record for record in records if record["version_id"] == version_id]
        return records

    def get_version_detail(self, *, target_id: str, version_id: str) -> dict[str, object]:
        with self.lock:
            record = next(
                (
                    item
                    for item in self.versions
                    if item["target_id"] == target_id and item["version_id"] == version_id
                ),
                None,
            )
        if record is None:
            raise ValueError(f"artifact version not found: {version_id}")
        return record

    def get_retrieval_detail(
        self,
        *,
        target_id: str,
        retrieval_entry_id: str,
    ) -> dict[str, object]:
        with self.lock:
            record = next(
                (
                    item
                    for item in self.retrieval_entries
                    if item["target_id"] == target_id
                    and item["retrieval_entry_id"] == retrieval_entry_id
                ),
                None,
            )
        if record is None:
            raise ValueError(f"retrieval entry not found: {retrieval_entry_id}")
        return record

    def rollback_version(self, *, target_id: str, version_id: str) -> dict[str, object]:
        with self.lock:
            target_versions = [record for record in self.versions if record["target_id"] == target_id]
            source_record = next(
                (record for record in target_versions if record["version_id"] == version_id),
                None,
            )
            if source_record is None:
                raise ValueError(f"artifact version not found for rollback: {version_id}")
            source_retrieval_record = next(
                (
                    record
                    for record in self.retrieval_entries
                    if record["target_id"] == target_id and record["version_id"] == version_id
                ),
                None,
            )

            next_index = len(target_versions) + 1
            rollback_version_id = f"{target_id}-v{next_index}"
            rollback_record = {
                **source_record,
                "version_id": rollback_version_id,
                "status": "ROLLED_BACK",
                "parent_version_id": version_id,
                "rollback_of_version_id": version_id,
            }
            self.versions.append(rollback_record)
            retrieval_record = {
                **(source_retrieval_record or {}),
                "retrieval_entry_id": f"retrieval-{rollback_version_id}",
                "version_id": rollback_version_id,
                "target_id": target_id,
                "workspace_id": source_record["workspace_id"],
                "retrieval_label": "ARTIFACT_DERIVED",
                "index_status": "DRAFT",
                "retrieval_weight_hint": "Rollback version remains a draft until manually promoted.",
                "notes": [
                    *(list(source_retrieval_record.get("notes", [])) if source_retrieval_record else []),
                    f"rollback_of:{version_id}",
                ],
            }
            self.retrieval_entries.append(retrieval_record)
            return {
                "commit_status": "ROLLED_BACK",
                "version_id": rollback_version_id,
                "retrieval_entry_id": retrieval_record["retrieval_entry_id"],
                "parent_version_id": version_id,
                "rollback_of_version_id": version_id,
            }

    def info(self) -> dict[str, object]:
        return {
            "backend_type": self.backend_type,
            "version_count": len(self.versions),
            "retrieval_count": len(self.retrieval_entries),
        }


@dataclass
class FileArtifactRepositoryBackend:
    storage_path: Path
    backend_type: str = "file"
    lock: Lock = field(default_factory=Lock)

    def __post_init__(self) -> None:
        self.storage_path.parent.mkdir(parents=True, exist_ok=True)
        if not self.storage_path.exists():
            self._write_state({"versions": [], "retrieval_entries": []})

    def clear(self) -> None:
        with self.lock:
            self._write_state({"versions": [], "retrieval_entries": []})

    def reserve_next_version(self, target_id: str) -> tuple[str, str]:
        with self.lock:
            state = self._read_state()
            target_versions = [record for record in state["versions"] if record["target_id"] == target_id]
            next_index = len(target_versions) + 1
            parent_version_id = str(target_versions[-1]["version_id"]) if target_versions else ""
            return f"{target_id}-v{next_index}", parent_version_id

    def commit_result(
        self,
        version_record: dict[str, object],
        retrieval_record: dict[str, object],
    ) -> tuple[int, int]:
        with self.lock:
            state = self._read_state()
            state["versions"].append(version_record)
            state["retrieval_entries"].append(retrieval_record)
            self._write_state(state)
            return len(state["versions"]), len(state["retrieval_entries"])

    def update_version_runtime_trace(
        self,
        *,
        target_id: str,
        version_id: str,
        runtime_trace: dict[str, object],
    ) -> None:
        with self.lock:
            state = self._read_state()
            record = next(
                (
                    item
                    for item in state["versions"]
                    if item["target_id"] == target_id and item["version_id"] == version_id
                ),
                None,
            )
            if record is None:
                raise ValueError(f"artifact version not found: {version_id}")
            record["runtime_trace"] = runtime_trace
            self._write_state(state)

    def list_versions(
        self,
        *,
        target_id: str | None = None,
        skill_key: str | None = None,
        action_key: str | None = None,
        status: str | None = None,
    ) -> list[dict[str, object]]:
        with self.lock:
            records = list(self._read_state()["versions"])
        return _filter_version_records(
            records,
            target_id=target_id,
            skill_key=skill_key,
            action_key=action_key,
            status=status,
        )

    def list_retrieval_entries(
        self,
        *,
        target_id: str | None = None,
        version_id: str | None = None,
    ) -> list[dict[str, object]]:
        with self.lock:
            records = list(self._read_state()["retrieval_entries"])
        if target_id is not None:
            records = [record for record in records if record["target_id"] == target_id]
        if version_id is not None:
            records = [record for record in records if record["version_id"] == version_id]
        return records

    def get_version_detail(self, *, target_id: str, version_id: str) -> dict[str, object]:
        with self.lock:
            state = self._read_state()
            record = next(
                (
                    item
                    for item in state["versions"]
                    if item["target_id"] == target_id and item["version_id"] == version_id
                ),
                None,
            )
        if record is None:
            raise ValueError(f"artifact version not found: {version_id}")
        return record

    def get_retrieval_detail(
        self,
        *,
        target_id: str,
        retrieval_entry_id: str,
    ) -> dict[str, object]:
        with self.lock:
            state = self._read_state()
            record = next(
                (
                    item
                    for item in state["retrieval_entries"]
                    if item["target_id"] == target_id
                    and item["retrieval_entry_id"] == retrieval_entry_id
                ),
                None,
            )
        if record is None:
            raise ValueError(f"retrieval entry not found: {retrieval_entry_id}")
        return record

    def rollback_version(self, *, target_id: str, version_id: str) -> dict[str, object]:
        with self.lock:
            state = self._read_state()
            target_versions = [record for record in state["versions"] if record["target_id"] == target_id]
            source_record = next(
                (record for record in target_versions if record["version_id"] == version_id),
                None,
            )
            if source_record is None:
                raise ValueError(f"artifact version not found for rollback: {version_id}")
            source_retrieval_record = next(
                (
                    record
                    for record in state["retrieval_entries"]
                    if record["target_id"] == target_id and record["version_id"] == version_id
                ),
                None,
            )

            next_index = len(target_versions) + 1
            rollback_version_id = f"{target_id}-v{next_index}"
            rollback_record = {
                **source_record,
                "version_id": rollback_version_id,
                "status": "ROLLED_BACK",
                "parent_version_id": version_id,
                "rollback_of_version_id": version_id,
            }
            state["versions"].append(rollback_record)
            retrieval_record = {
                **(source_retrieval_record or {}),
                "retrieval_entry_id": f"retrieval-{rollback_version_id}",
                "version_id": rollback_version_id,
                "target_id": target_id,
                "workspace_id": source_record["workspace_id"],
                "retrieval_label": "ARTIFACT_DERIVED",
                "index_status": "DRAFT",
                "retrieval_weight_hint": "Rollback version remains a draft until manually promoted.",
                "notes": [
                    *(list(source_retrieval_record.get("notes", [])) if source_retrieval_record else []),
                    f"rollback_of:{version_id}",
                ],
            }
            state["retrieval_entries"].append(retrieval_record)
            self._write_state(state)
            return {
                "commit_status": "ROLLED_BACK",
                "version_id": rollback_version_id,
                "retrieval_entry_id": retrieval_record["retrieval_entry_id"],
                "parent_version_id": version_id,
                "rollback_of_version_id": version_id,
            }

    def info(self) -> dict[str, object]:
        with self.lock:
            state = self._read_state()
        return {
            "backend_type": self.backend_type,
            "storage_path": str(self.storage_path),
            "version_count": len(state["versions"]),
            "retrieval_count": len(state["retrieval_entries"]),
        }

    def _read_state(self) -> dict[str, list[dict[str, object]]]:
        if not self.storage_path.exists():
            return {"versions": [], "retrieval_entries": []}
        try:
            state = json.loads(self.storage_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(
                f"artifact repository state is unreadable: {self.storage_path}"
            ) from exc
        if not isinstance(state, dict) or not isinstance(state.get("versions"), list) or not isinstance(
            state.get("retrieval_entries"), list
        ):
            raise ValueError(
                f"artifact repository state has an invalid schema: {self.storage_path}"
            )
        return state

    def _write_state(self, state: dict[str, list[dict[str, object]]]) -> None:
        serialized = json.dumps(state, ensure_ascii=False, indent=2)
        temporary_path = self.storage_path.with_suffix(f"{self.storage_path.suffix}.tmp")
        with temporary_path.open("w", encoding="utf-8") as handle:
            handle.write(serialized)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, self.storage_path)


_backend: ArtifactRepositoryBackend = InMemoryArtifactRepositoryBackend()


def configure_artifact_repository_backend(
    backend_type: str,
    *,
    storage_path: Path | str | None = None,
) -> None:
    global _backend
    normalized_backend_type = backend_type.strip().lower()
    if normalized_backend_type == "memory":
        _backend = InMemoryArtifactRepositoryBackend()
        return
    if normalized_backend_type == "file":
        if storage_path is None:
            raise ValueError("storage_path is required for file artifact repository backend")
        _backend = FileArtifactRepositoryBackend(Path(storage_path))
        return
    raise ValueError(f"unsupported artifact repository backend: {backend_type}")


def get_artifact_repository_backend_info() -> dict[str, object]:
    return _backend.info()


def clear_artifact_repository() -> None:
    _backend.clear()


def reserve_next_artifact_version(target_id: str) -> tuple[str, str]:
    return _backend.reserve_next_version(target_id)


def commit_artifact_result(
    *,
    task_id: str,
    workspace_id: str,
    target_id: str,
    result: ArtifactTaskResult,
    retrieval_feedback: RetrievalFeedback,
) -> ArtifactCommitReceipt:
    verification = result.result_payload.get("verification", {})
    execution_plan = result.result_payload.get("execution_plan", {})
    skill_key = str(
        execution_plan.get("skill_key")
        or execution_plan.get("execution_spec", {}).get("skill_key", "")
    )
    action_key = str(
        execution_plan.get("action_key")
        or result.job_snapshot.action_key
        or result.version_snapshot.artifact_type
    )
    sections = result.result_payload.get("sections", [])
    memory_promotion_preview = result.result_payload.get("memory_promotion_preview", {})
    version_record = {
        "version_id": result.version_snapshot.version_id,
        "target_id": target_id,
        "task_id": task_id,
        "workspace_id": workspace_id,
        "skill_key": skill_key,
        "action_key": action_key,
        "artifact_type": result.version_snapshot.artifact_type,
        "title": result.version_snapshot.title,
        "status": result.version_snapshot.status,
        "summary": result.version_snapshot.summary,
        "parent_version_id": result.version_snapshot.parent_version_id,
        "rollback_of_version_id": result.version_snapshot.rollback_of_version_id,
        "verification_status": verification.get("status", "UNKNOWN"),
        "result_type": result.result_type,
        "result_title": result.result_title,
        "trace_summary": result.trace_summary,
        "citations": result.citations,
        "execution_plan_summary": {
            "skill_key": skill_key,
            "action_key": action_key,
            "style_profile_key": execution_plan.get("style_profile_key", ""),
            "skill_graph_key": execution_plan.get("skill_graph_key", ""),
            "prompt_recipe_id": execution_plan.get("prompt_recipe", {}).get("recipe_id", ""),
            "schema_gate_status": execution_plan.get("schema_gate_status", ""),
        },
        "artifact_preview": {
            "markdown": result.result_payload.get("markdown", ""),
            "section_count": len(sections),
            "section_headings": [
                str(section.get("heading", ""))
                for section in sections
            ],
        },
        "runtime_trace": _build_runtime_trace_payload(result),
    }
    retrieval_record = {
        "retrieval_entry_id": f"retrieval-{result.version_snapshot.version_id}",
        "version_id": result.version_snapshot.version_id,
        "target_id": target_id,
        "workspace_id": workspace_id,
        "retrieval_label": retrieval_feedback.retrieval_label,
        "index_status": retrieval_feedback.index_status,
        "retrieval_weight_hint": retrieval_feedback.retrieval_weight_hint,
        "supporting_source_ids": retrieval_feedback.supporting_source_ids,
        "derived_passage_count": retrieval_feedback.derived_passage_count,
        "derived_section_headings": [
            str(item.get("section_heading", ""))
            for item in retrieval_feedback.derived_passages
        ],
        "promotion_candidate_count": retrieval_feedback.promotion_candidate_count,
        "derived_passages": retrieval_feedback.derived_passages,
        "memory_promotion_preview": {
            "eligible": bool(memory_promotion_preview.get("eligible", False)),
            "candidate_memory_count": int(memory_promotion_preview.get("candidate_memory_count", 0)),
            "novelty_gate_status": str(memory_promotion_preview.get("novelty_gate_status", "")),
            "supporting_source_ids": list(memory_promotion_preview.get("supporting_source_ids", [])),
            "candidate_memories": list(memory_promotion_preview.get("candidate_memories", [])),
            "notes": list(memory_promotion_preview.get("notes", [])),
        },
        "notes": retrieval_feedback.notes,
    }

    version_count, retrieval_count = _backend.commit_result(version_record, retrieval_record)

    return ArtifactCommitReceipt(
        commit_status="COMMITTED",
        version_id=result.version_snapshot.version_id,
        retrieval_entry_id=retrieval_record["retrieval_entry_id"],
        persisted_version_count=version_count,
        persisted_retrieval_count=retrieval_count,
    )


def sync_artifact_runtime_trace(
    *,
    target_id: str,
    version_id: str,
    result: ArtifactTaskResult,
) -> None:
    _backend.update_version_runtime_trace(
        target_id=target_id,
        version_id=version_id,
        runtime_trace=_build_runtime_trace_payload(result),
    )


def list_artifact_versions(
    target_id: str | None = None,
    skill_key: str | None = None,
    action_key: str | None = None,
    status: str | None = None,
) -> list[dict[str, object]]:
    return _backend.list_versions(
        target_id=target_id,
        skill_key=skill_key,
        action_key=action_key,
        status=status,
    )


def list_retrieval_entries(
    target_id: str | None = None,
    version_id: str | None = None,
) -> list[dict[str, object]]:
    return _backend.list_retrieval_entries(target_id=target_id, version_id=version_id)


def get_artifact_version_detail(*, target_id: str, version_id: str) -> dict[str, object]:
    return _backend.get_version_detail(target_id=target_id, version_id=version_id)


def get_retrieval_entry_detail(*, target_id: str, retrieval_entry_id: str) -> dict[str, object]:
    return _backend.get_retrieval_detail(
        target_id=target_id,
        retrieval_entry_id=retrieval_entry_id,
    )


def rollback_artifact_version(*, target_id: str, version_id: str) -> dict[str, object]:
    return _backend.rollback_version(target_id=target_id, version_id=version_id)


def _build_runtime_trace_payload(result: ArtifactTaskResult) -> dict[str, object]:
    return {
        "verification": result.result_payload.get("verification", {}),
        "node_traces": result.result_payload.get("node_traces", []),
        "capability_union_trace": result.result_payload.get("capability_union_trace", {}),
        "approval_trace": result.result_payload.get("approval_trace", {}),
        "evidence_coverage": result.result_payload.get("evidence_coverage", {}),
        "writeback_preview": result.result_payload.get("writeback_preview", {}),
        "output_contract_trace": result.result_payload.get("output_contract_trace", {}),
        "lifecycle_trace": result.result_payload.get("lifecycle_trace", {}),
        "acquisition_callback_trace": result.result_payload.get("acquisition_callback_trace", {}),
    }
