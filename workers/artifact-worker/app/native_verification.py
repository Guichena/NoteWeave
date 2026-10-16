"""冻结视频的陈述校验只读取已有证据，不改知识计划、原帧或派生正文。"""

import json

from app.node_executor import NodeExecutionError
from app.statement_verification import verify_statement_support


def verify_frozen_result(result, bundle):
    # 沿用统一模型工厂和预算；模型不可用的陈述保留 NOT_RUN。
    from app.runner import build_default_llm_client
    llm_client = build_default_llm_client()
    sections = result.result_payload["sections"]
    used = {ref for section in sections for ref in section["source_refs"]}
    passages = []
    for segment in bundle.transcript_segments:
        key = f"segment:{segment.segment_id}"
        if key in used and segment.corrected_text.strip():
            passages.append({"chunk_id": key, "title": key, "content": segment.corrected_text})
    observations = {item.file_id: item for item in (bundle.frame_observations or [])}
    for frame in bundle.frames:
        key = f"frame:{frame.frame_id}"
        if key not in used:
            continue
        observation = observations.get(frame.file_id)
        content = {"frame_id": frame.frame_id, "part": frame.part, "at_ms": frame.at_ms,
            "observations": [item.model_dump(mode="json") for item in observation.observations] if observation else [],
            "coverage_gaps": observation.coverage_gaps if observation else ["没有可读取的画面文字"]}
        passages.append({"chunk_id": key, "title": key, "content": json.dumps(content, ensure_ascii=False)})
    evidence = verify_statement_support(sections, passages, llm_client=llm_client, citation_density="PER_SECTION")
    old = result.result_payload.get("verification", {})
    contract = result.result_payload["output_contract_trace"]
    failed = [*old.get("failed_checks", []), *evidence["failed_checks"]]
    warnings = list(dict.fromkeys([*old.get("warnings", []), *evidence["warnings"]]))
    verification = {**old, "status": "FAIL" if failed else "WARN" if warnings else "PASS",
        "contract_status": "FAIL" if contract["status"] == "FAIL" else "PASS",
        "evidence_status": evidence["status"], "repair_rounds": [],
        "failed_checks": failed, "warnings": warnings,
        **{key: evidence[key] for key in ("sections", "citation_recall", "citation_precision", "llm_calls")},
        "verification_mode": "FROZEN_VIDEO_STATEMENTS"}
    verification["statement_verification_version"] = "artifact-statement-verification.v1"
    result.result_payload["verification"] = verification
    result.result_payload["statement_verification_trace"] = evidence["execution_traces"]
    result.result_payload["llm_usage"] = llm_client.usage_summary() if callable(getattr(llm_client, "usage_summary", None)) else {}
    if failed:
        raise NodeExecutionError("冻结视频正文缺少充分证据，原知识计划和原帧未改写", {
            "status": "FAILED", "reason_code": "ARTIFACT_FROZEN_STATEMENT_VERIFICATION_FAILED",
            "verification": verification, "llm_calls": evidence["llm_calls"],
            "execution_traces": evidence["execution_traces"]})
