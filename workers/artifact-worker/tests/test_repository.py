from __future__ import annotations

from app.artifact_repository import (
    clear_artifact_repository,
    commit_artifact_result,
    get_artifact_version_detail,
    get_retrieval_entry_detail,
    list_artifact_versions,
    list_retrieval_entries,
    rollback_artifact_version,
)
from app.models import RetrievalFeedback
from app.main import (
    debug_get_artifact_version_detail,
    debug_get_retrieval_entry_detail,
    debug_list_artifact_versions,
    debug_list_retrieval_entries,
    debug_rollback_artifact_version,
)
from app.runner import run_artifact_task
from tests.test_runner import _build_generic_task_input, _build_resume_task_input


def _contains_nested_key(value: object, key: str) -> bool:
    if isinstance(value, dict):
        if key in value:
            return True
        return any(_contains_nested_key(item, key) for item in value.values())
    if isinstance(value, list):
        return any(_contains_nested_key(item, key) for item in value)
    return False


def test_artifact_commit_should_persist_version_and_retrieval_entry() -> None:
    clear_artifact_repository()

    _, result = run_artifact_task(_build_generic_task_input("report"))

    versions = list_artifact_versions(target_id=result.job_snapshot.target_id)
    retrieval_entries = list_retrieval_entries(target_id=result.job_snapshot.target_id)
    version_detail = get_artifact_version_detail(
        target_id=result.job_snapshot.target_id,
        version_id=result.version_snapshot.version_id,
    )
    retrieval_detail = get_retrieval_entry_detail(
        target_id=result.job_snapshot.target_id,
        retrieval_entry_id=f"retrieval-{result.version_snapshot.version_id}",
    )

    assert len(versions) == 1
    assert versions[0]["version_id"] == result.version_snapshot.version_id
    assert versions[0]["artifact_type"] == "REPORT"
    assert versions[0]["action_key"] == "REPORT"
    assert versions[0]["verification_status"] == "PASS"
    assert version_detail["result_title"] == result.result_title
    assert version_detail["execution_plan_summary"] == {
        "skill_key": result.result_payload["execution_plan"]["skill_key"],
        "action_key": result.result_payload["execution_plan"]["action_key"],
        "style_profile_key": result.result_payload["execution_plan"]["style_profile_key"],
        "skill_graph_key": result.result_payload["execution_plan"]["skill_graph_key"],
        "prompt_recipe_id": result.result_payload["execution_plan"]["prompt_recipe"]["recipe_id"],
        "schema_gate_status": result.result_payload["execution_plan"]["schema_gate_status"],
    }
    assert version_detail["artifact_preview"] == {
        "markdown": result.result_payload["markdown"],
        "section_count": len(result.result_payload["sections"]),
        "section_headings": [
            section["heading"] for section in result.result_payload["sections"]
        ],
    }
    assert version_detail["runtime_trace"]["verification"]["status"] == "PASS"
    assert len(version_detail["runtime_trace"]["node_traces"]) == 4
    assert version_detail["runtime_trace"]["node_traces"][0]["skill_key"] == "workspace_material_digest"
    assert "source trace captured for digested materials" in version_detail["runtime_trace"]["node_traces"][0]["verification_checks"]
    assert version_detail["runtime_trace"]["node_traces"] == result.result_payload["node_traces"]
    assert version_detail["runtime_trace"]["verification"] == result.result_payload["verification"]
    assert (
        version_detail["runtime_trace"]["capability_union_trace"]
        == result.result_payload["capability_union_trace"]
    )
    assert (
        version_detail["runtime_trace"]["approval_trace"]
        == result.result_payload["approval_trace"]
    )
    assert (
        version_detail["runtime_trace"]["evidence_coverage"]
        == result.result_payload["evidence_coverage"]
    )
    assert (
        version_detail["runtime_trace"]["writeback_preview"]
        == result.result_payload["writeback_preview"]
    )
    assert (
        version_detail["runtime_trace"]["output_contract_trace"]
        == result.result_payload["output_contract_trace"]
    )
    assert (
        version_detail["runtime_trace"]["lifecycle_trace"]
        == result.result_payload["lifecycle_trace"]
    )

    assert len(retrieval_entries) == 1
    assert retrieval_entries[0]["retrieval_label"] == "ARTIFACT_DERIVED"
    assert retrieval_entries[0]["index_status"] == "DRAFT"
    assert retrieval_entries[0]["version_id"] == result.version_snapshot.version_id
    assert retrieval_entries[0]["supporting_source_ids"] == ["src-1", "src-2"]
    assert (
        retrieval_entries[0]["derived_passage_count"]
        == result.result_payload["retrieval_feedback"]["derived_passage_count"]
    )
    assert retrieval_entries[0]["promotion_candidate_count"] >= 1
    assert retrieval_detail["version_id"] == result.version_snapshot.version_id
    assert retrieval_detail["derived_passages"] == result.result_payload["retrieval_feedback"]["derived_passages"]
    assert (
        retrieval_detail["memory_promotion_preview"]
        == result.result_payload["memory_promotion_preview"]
    )
    assert retrieval_detail["derived_passages"][0]["section_heading"] == "问题定义"
    assert retrieval_detail["derived_passages"][0]["retrieval_tags"]
    assert retrieval_detail["memory_promotion_preview"]["eligible"] is True
    assert (
        retrieval_detail["memory_promotion_preview"]["candidate_memory_count"]
        == len(retrieval_detail["memory_promotion_preview"]["candidate_memories"])
    )
    assert retrieval_detail["memory_promotion_preview"]["candidate_memories"][0]["source_ids"]


def test_artifact_commit_should_infer_action_key_from_execution_plan_without_explicit_argument() -> None:
    clear_artifact_repository()

    _, result = run_artifact_task(_build_generic_task_input("report"))
    retrieval_feedback = RetrievalFeedback.model_validate(
        result.result_payload["retrieval_feedback"]
    )
    clear_artifact_repository()

    receipt = commit_artifact_result(
        task_id=result.job_snapshot.task_id,
        workspace_id=result.job_snapshot.workspace_id,
        target_id=result.job_snapshot.target_id,
        result=result,
        retrieval_feedback=retrieval_feedback,
    )
    detail = get_artifact_version_detail(
        target_id=result.job_snapshot.target_id,
        version_id=result.version_snapshot.version_id,
    )

    assert receipt.commit_status == "COMMITTED"
    assert detail["action_key"] == "REPORT"
    assert detail["execution_plan_summary"]["action_key"] == "REPORT"


def test_debug_repository_views_should_expose_committed_records() -> None:
    clear_artifact_repository()
    run_artifact_task(_build_resume_task_input())

    versions_response = debug_list_artifact_versions()
    retrieval_response = debug_list_retrieval_entries()

    assert versions_response.versions
    assert versions_response.versions[0]["skill_key"] == "resume_highlight"
    assert "action_key" not in versions_response.versions[0]
    assert retrieval_response.entries
    assert retrieval_response.entries[0]["retrieval_label"] == "ARTIFACT_DERIVED"


def test_artifact_repository_should_support_skill_key_filter_for_skill_first_versions() -> None:
    clear_artifact_repository()
    resume_input = _build_resume_task_input()
    _, resume_result = run_artifact_task(resume_input)
    run_artifact_task(_build_generic_task_input("faq"))

    filtered_versions = list_artifact_versions(
        target_id=resume_input.target_id,
        skill_key="resume_highlight",
    )
    detail = get_artifact_version_detail(
        target_id=resume_input.target_id,
        version_id=resume_result.version_snapshot.version_id,
    )

    assert len(filtered_versions) == 1
    assert filtered_versions[0]["version_id"] == resume_result.version_snapshot.version_id
    assert filtered_versions[0]["skill_key"] == "resume_highlight"
    assert detail["skill_key"] == "resume_highlight"
    assert detail["execution_plan_summary"]["skill_key"] == "resume_highlight"


def test_debug_repository_views_should_support_skill_key_filter_for_skill_first_versions() -> None:
    clear_artifact_repository()
    resume_input = _build_resume_task_input()
    _, resume_result = run_artifact_task(resume_input)
    run_artifact_task(_build_generic_task_input("faq"))

    filtered_versions = debug_list_artifact_versions(
        target_id=resume_input.target_id,
        skill_key="resume_highlight",
    )
    detail_response = debug_get_artifact_version_detail(
        target_id=resume_input.target_id,
        version_id=resume_result.version_snapshot.version_id,
    )

    assert len(filtered_versions.versions) == 1
    assert filtered_versions.versions[0]["version_id"] == resume_result.version_snapshot.version_id
    assert filtered_versions.versions[0]["skill_key"] == "resume_highlight"
    assert "action_key" not in filtered_versions.versions[0]
    assert not _contains_nested_key(filtered_versions.versions[0], "action_resolution")
    assert not _contains_nested_key(filtered_versions.versions[0]["runtime_trace"], "action_key")
    assert not _contains_nested_key(detail_response.version, "action_resolution")
    assert not _contains_nested_key(detail_response.version["runtime_trace"], "action_key")
    assert "contract_checks" in detail_response.version["runtime_trace"]["output_contract_trace"]
    assert "action_checks" not in detail_response.version["runtime_trace"]["output_contract_trace"]


def test_artifact_repository_should_prefer_skill_key_over_conflicting_action_key_filter() -> None:
    clear_artifact_repository()
    resume_input = _build_resume_task_input()
    _, resume_result = run_artifact_task(resume_input)
    run_artifact_task(_build_generic_task_input("faq"))

    filtered_versions = list_artifact_versions(
        target_id=resume_input.target_id,
        skill_key="resume_highlight",
        action_key="FAQ",
    )
    debug_filtered_versions = debug_list_artifact_versions(
        target_id=resume_input.target_id,
        skill_key="resume_highlight",
        action_key="FAQ",
    )

    assert len(filtered_versions) == 1
    assert filtered_versions[0]["version_id"] == resume_result.version_snapshot.version_id
    assert filtered_versions[0]["skill_key"] == "resume_highlight"
    assert debug_filtered_versions.versions[0]["version_id"] == resume_result.version_snapshot.version_id
    assert debug_filtered_versions.versions[0]["skill_key"] == "resume_highlight"
    assert "action_key" not in debug_filtered_versions.versions[0]


def test_artifact_versions_should_increment_for_same_target() -> None:
    clear_artifact_repository()
    task_input = _build_generic_task_input("report")

    _, first_result = run_artifact_task(task_input)
    _, second_result = run_artifact_task(task_input)

    versions = list_artifact_versions(target_id=task_input.target_id)

    assert first_result.version_snapshot.version_id.endswith("-v1")
    assert second_result.version_snapshot.version_id.endswith("-v2")
    assert versions[-1]["parent_version_id"] == first_result.version_snapshot.version_id


def test_artifact_repository_should_filter_and_support_rollback() -> None:
    clear_artifact_repository()
    report_input = _build_generic_task_input("report")
    faq_input = _build_generic_task_input("faq")

    _, report_result = run_artifact_task(report_input)
    run_artifact_task(faq_input)
    rollback_receipt = rollback_artifact_version(
        target_id=report_input.target_id,
        version_id=report_result.version_snapshot.version_id,
    )

    report_versions = list_artifact_versions(target_id=report_input.target_id, action_key="REPORT")
    faq_versions = list_artifact_versions(target_id=faq_input.target_id, action_key="FAQ")
    original_version = get_artifact_version_detail(
        target_id=report_input.target_id,
        version_id=report_result.version_snapshot.version_id,
    )
    rolled_back_version = get_artifact_version_detail(
        target_id=report_input.target_id,
        version_id=rollback_receipt["version_id"],
    )
    original_retrieval = get_retrieval_entry_detail(
        target_id=report_input.target_id,
        retrieval_entry_id=f"retrieval-{report_result.version_snapshot.version_id}",
    )
    rolled_back_retrieval = get_retrieval_entry_detail(
        target_id=report_input.target_id,
        retrieval_entry_id=rollback_receipt["retrieval_entry_id"],
    )

    assert rollback_receipt["commit_status"] == "ROLLED_BACK"
    assert rollback_receipt["version_id"].endswith("-v2")
    assert report_versions[-1]["parent_version_id"] == report_result.version_snapshot.version_id
    assert report_versions[-1]["rollback_of_version_id"] == report_result.version_snapshot.version_id
    assert rolled_back_version["status"] == "ROLLED_BACK"
    assert rolled_back_version["artifact_preview"] == original_version["artifact_preview"]
    assert rolled_back_version["runtime_trace"] == original_version["runtime_trace"]
    assert rolled_back_retrieval["derived_passages"] == original_retrieval["derived_passages"]
    assert (
        rolled_back_retrieval["memory_promotion_preview"]
        == original_retrieval["memory_promotion_preview"]
    )
    assert f"rollback_of:{report_result.version_snapshot.version_id}" in rolled_back_retrieval["notes"]
    assert faq_versions[0]["action_key"] == "FAQ"


def test_debug_repository_views_should_support_filters_and_rollback() -> None:
    clear_artifact_repository()
    report_input = _build_generic_task_input("report")
    _, report_result = run_artifact_task(report_input)
    run_artifact_task(_build_generic_task_input("faq"))

    filtered_versions = debug_list_artifact_versions(target_id=report_input.target_id, action_key="REPORT")
    detail_response = debug_get_artifact_version_detail(
        target_id=report_input.target_id,
        version_id=report_result.version_snapshot.version_id,
    )
    retrieval_detail_response = debug_get_retrieval_entry_detail(
        target_id=report_input.target_id,
        retrieval_entry_id=f"retrieval-{report_result.version_snapshot.version_id}",
    )
    rollback_response = debug_rollback_artifact_version(
        target_id=report_input.target_id,
        version_id=report_result.version_snapshot.version_id,
    )

    assert len(filtered_versions.versions) == 1
    assert "action_key" not in filtered_versions.versions[0]
    assert not _contains_nested_key(filtered_versions.versions[0], "action_resolution")
    assert detail_response.version["version_id"] == report_result.version_snapshot.version_id
    assert "action_key" not in detail_response.version
    assert not _contains_nested_key(detail_response.version, "action_resolution")
    assert detail_response.version["artifact_preview"]["section_count"] == 3
    assert "action_key" not in detail_response.version["execution_plan_summary"]
    assert detail_response.version["runtime_trace"]["verification"]["status"] == "PASS"
    assert detail_response.version["runtime_trace"]["node_traces"][0]["skill_key"] == "workspace_material_digest"
    assert retrieval_detail_response.entry["memory_promotion_preview"]["candidate_memory_count"] >= 1
    assert rollback_response.receipt["commit_status"] == "ROLLED_BACK"
    rolled_back_detail = debug_get_artifact_version_detail(
        target_id=report_input.target_id,
        version_id=rollback_response.receipt["version_id"],
    )
    rolled_back_retrieval_detail = debug_get_retrieval_entry_detail(
        target_id=report_input.target_id,
        retrieval_entry_id=rollback_response.receipt["retrieval_entry_id"],
    )
    assert rolled_back_detail.version["status"] == "ROLLED_BACK"
    assert rolled_back_detail.version["artifact_preview"] == detail_response.version["artifact_preview"]
    assert rolled_back_detail.version["runtime_trace"] == detail_response.version["runtime_trace"]
    assert (
        rolled_back_retrieval_detail.entry["memory_promotion_preview"]
        == retrieval_detail_response.entry["memory_promotion_preview"]
    )
