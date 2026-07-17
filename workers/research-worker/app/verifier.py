from __future__ import annotations

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchIntentCompletionContract,
    ResearchIntentAlignment,
    ResearchIntentRequirement,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchTaskInput,
    ResearchVerifierDecision,
)


def run_local_verifier(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    llm_client: LlmClient | None = None,
) -> LocalVerifierResult:
    passed_checks: list[str] = []
    warnings: list[str] = []
    recovery_actions: list[str] = []
    decision_records: list[ResearchVerifierDecision] = []
    recovery_mode = _recovery_mode(plan)
    previous_read_window_count = _resume_metric(task_input, "read_windows")
    previous_evidence_card_count = _resume_metric(task_input, "evidence_cards")
    intent_completion_contract = _build_research_intent_completion_contract(
        task_input,
        plan,
        ledger,
        search_hits,
        evidence_cards,
        branch_decisions=[],
    )
    intent_alignment = _build_research_intent_alignment(
        task_input,
        intent_completion_contract,
    )

    if plan.normalized_question:
        passed_checks.append("question normalized")
    else:
        warnings.append("question is empty after normalization")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="WARN",
                reason_code="EMPTY_NORMALIZED_QUESTION",
                action="rebuild research intent before continuing",
                status="WARN",
            )
        )

    if plan.query_set:
        passed_checks.append("query bundle compiled")
    else:
        warnings.append("query bundle is empty")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="WARN",
                reason_code="EMPTY_QUERY_BUNDLE",
                action="compile direct, scoped and counterfactual queries",
                status="WARN",
            )
        )

    if search_hits:
        passed_checks.append("workspace search hits resolved")
    else:
        warnings.append("no search hit resolved from workspace scope")
        recovery_actions.append("attach at least one workspace source before final export")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="NO_SEARCH_HITS",
                action="attach at least one workspace source before final export",
                status="WARN",
            )
        )

    if read_windows:
        passed_checks.append("goal-conditioned read windows opened")
        injection_windows = [window for window in read_windows if window.prompt_injection_detected]
        if injection_windows:
            signals = list(dict.fromkeys(
                signal
                for window in injection_windows
                for signal in window.prompt_injection_signals
            ))
            warnings.append("untrusted web content contains prompt-injection signals")
            recovery_actions.append("exclude injection-bearing windows from final findings or obtain a clean independent source")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="PROMPT_INJECTION_DETECTED",
                    action="quarantine injection-bearing external windows",
                    status="WARN",
                    notes=signals[:5],
                )
            )
    elif search_hits:
        warnings.append("search hits exist but no read window was opened")
        recovery_actions.append("increase read-window retention budget")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="NO_READ_WINDOWS",
                action="increase read-window retention budget",
                status="WARN",
            )
        )

    if evidence_cards:
        passed_checks.append("evidence cards extracted")
        forbidden_hits = _forbidden_pattern_hits(
            evidence_cards,
            task_input.control_pack.forbidden_patterns,
        )
        if forbidden_hits:
            warnings.append("evidence text contains forbidden control-pack patterns")
            recovery_actions.append("remove or rewrite findings that match forbidden control-pack patterns")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="FORBIDDEN_PATTERN_FOUND",
                    action="remove or rewrite findings that match forbidden control-pack patterns",
                    status="WARN",
                    notes=forbidden_hits[:5],
                )
            )
    elif read_windows:
        warnings.append("read windows exist but no evidence card was extracted")
        recovery_actions.append("rerun evidence extraction before synthesis")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="NO_EVIDENCE_CARDS",
                action="rerun evidence extraction before synthesis",
                status="WARN",
            )
        )

    if ledger.rows:
        passed_checks.append("state ledger populated")
    else:
        warnings.append("state ledger has no evidence rows")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="EMPTY_STATE_LEDGER",
                action="write at least one evidence-backed ledger row before synthesis",
                status="WARN",
            )
        )

    if ledger.required_finding_progress:
        if all(item.status == "READY" for item in ledger.required_finding_progress):
            passed_checks.append("required findings are backed by requirement-ready rows")
        else:
            warnings.append("required findings are still missing requirement-ready rows")
            recovery_actions.append("complete the missing required finding rows before final synthesis")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="REQUIRED_FINDINGS_PARTIAL",
                    action="complete the missing required finding rows before final synthesis",
                    status="WARN",
                    notes=[
                        f"{item.requirement_id}:{item.status}"
                        for item in ledger.required_finding_progress
                        if item.status != "READY"
                    ][:4],
                )
            )

    if task_input.control_pack.evidence_policy:
        passed_checks.append("evidence policy acknowledged")
    else:
        warnings.append("no evidence policy supplied by control pack")

    if len(ledger.rows) < int(plan.stop_contract["min_sources"]):
        warnings.append("source coverage is below the preferred threshold")
        recovery_actions.append("expand the source scope or lower the source threshold")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="SOURCE_COVERAGE_LOW",
                action="expand the source scope or lower the source threshold",
                status="WARN",
                notes=[f"coverage={ledger.coverage_score:.2f}"],
            )
        )

    verified_rows = [row for row in ledger.rows if row.row_status == "VERIFIED"]
    low_trust_verified_rows = [
        row
        for row in verified_rows
        if row.source_quality in {"GENERAL_WEB", "SECONDARY_SOURCE", "REFERENCE_SOURCE"}
    ]
    if verified_rows and len(low_trust_verified_rows) == len(verified_rows):
        warnings.append("low-trust source foundation is carrying the current verified answer")
        recovery_actions.append("add at least one higher-trust source window before final synthesis")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="LOW_TRUST_SOURCE_FOUNDATION",
                action="add at least one higher-trust source window before final synthesis",
                status="WARN",
                notes=[
                    "verified_source_qualities="
                    + ",".join(sorted({row.source_quality for row in verified_rows}))
                ],
            )
        )

    dominant_source = _dominant_verified_source(verified_rows)
    if dominant_source is not None:
        source_id, count, total = dominant_source
        warnings.append("verified findings are dominated by a single source")
        recovery_actions.append("cross-check the dominant source with an independent source before final synthesis")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="SINGLE_SOURCE_DOMINANCE",
                action="cross-check the dominant source with an independent source before final synthesis",
                status="WARN",
                notes=[f"source_id={source_id}", f"dominance={count}/{total}"],
            )
        )

    if low_trust_verified_rows and any(
        window.read_strategy == "WIDE_COVERAGE_READ"
        for window in read_windows
    ) and not any(
        window.read_strategy in {"DEEP_EVIDENCE_READ", "COUNTERFACTUAL_DEEP_READ"}
        for window in read_windows
    ):
        warnings.append("wide coverage reads are present without a deep evidence read anchor")
        recovery_actions.append("open one deep evidence window before treating the answer as stable")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="DEEP_READ_ANCHOR_MISSING",
                action="open one deep evidence window before treating the answer as stable",
                status="WARN",
            )
        )

    external_read_windows = [
        window
        for window in read_windows
        if window.adapter == "external_url" or bool(window.url.strip())
    ]
    fetched_external_windows = [
        window
        for window in external_read_windows
        if window.fetch_status == "FETCHED" or window.content_origin == "FETCHED_SNAPSHOT"
    ]
    fallback_external_windows = [
        window
        for window in external_read_windows
        if window.fetch_status == "FALLBACK_USED" or window.content_origin == "SEARCH_SNIPPET_FALLBACK"
    ]
    if (
        low_trust_verified_rows
        and external_read_windows
        and fallback_external_windows
        and not fetched_external_windows
    ):
        warnings.append("external fallback-only windows are carrying the current low-trust answer path")
        recovery_actions.append("fetch at least one readable external page body before final synthesis")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="EXTERNAL_FETCH_FALLBACK_HEAVY",
                action="fetch at least one readable external page body before final synthesis",
                status="WARN",
                notes=[
                    f"external_windows={len(external_read_windows)}",
                    f"fallback_windows={len(fallback_external_windows)}",
                ],
            )
        )

    external_search_hits = [
        hit
        for hit in search_hits
        if hit.adapter == "external" or bool(hit.url.strip())
    ]
    provider_fallback_hits = [
        hit
        for hit in external_search_hits
        if len([item for item in hit.provider_attempts if str(item).strip()]) > 1
    ]
    if (
        low_trust_verified_rows
        and external_search_hits
        and provider_fallback_hits
        and len(provider_fallback_hits) == len(external_search_hits)
    ):
        warnings.append("provider fallback chains are carrying the current low-trust external answer path")
        recovery_actions.append("stabilize the answer with at least one primary-provider or workspace corroboration")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="EXTERNAL_PROVIDER_FALLBACK_HEAVY",
                action="stabilize the answer with at least one primary-provider or workspace corroboration",
                status="WARN",
                notes=[
                    f"external_hits={len(external_search_hits)}",
                    f"provider_fallback_hits={len(provider_fallback_hits)}",
                ],
            )
        )

    fetched_transport_windows = [
        window
        for window in external_read_windows
        if window.fetch_status == "FETCHED" or window.content_origin == "FETCHED_SNAPSHOT"
    ]
    fallback_transport_windows = [
        window
        for window in fetched_transport_windows
        if window.transport_attempt_count > 1
        or len([item for item in window.transport_chain if str(item).strip()]) > 1
    ]
    if (
        low_trust_verified_rows
        and fetched_transport_windows
        and fallback_transport_windows
        and len(fallback_transport_windows) == len(fetched_transport_windows)
    ):
        warnings.append("transport fallback chains are carrying the current low-trust fetched answer path")
        recovery_actions.append("stabilize the answer with at least one primary transport fetch path")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="EXTERNAL_TRANSPORT_FALLBACK_HEAVY",
                action="stabilize the answer with at least one primary transport fetch path",
                status="WARN",
                notes=[
                    f"fetched_external_windows={len(fetched_transport_windows)}",
                    f"transport_fallback_windows={len(fallback_transport_windows)}",
                ],
            )
        )

    archive_unready_fetched_windows = [
        window
        for window in fetched_transport_windows
        if not window.snapshot_archive_ready
    ]
    if (
        low_trust_verified_rows
        and fetched_transport_windows
        and archive_unready_fetched_windows
        and len(archive_unready_fetched_windows) == len(fetched_transport_windows)
    ):
        warnings.append("fetched external windows are not archive-ready for stable checkpoint replay")
        recovery_actions.append("stabilize the answer with at least one archive-ready fetched window")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code="EXTERNAL_SNAPSHOT_ARCHIVE_NOT_READY",
                action="stabilize the answer with at least one archive-ready fetched window",
                status="WARN",
                notes=[
                    f"fetched_external_windows={len(fetched_transport_windows)}",
                    f"archive_unready_windows={len(archive_unready_fetched_windows)}",
                ],
            )
        )

    if any(card.relation_type == "CONFLICTS" for card in evidence_cards):
        warnings.append("conflicting evidence card requires a branch recheck")
        recovery_actions.append("open an alternative source window for conflicting evidence")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="BRANCH",
                reason_code="CONFLICTING_EVIDENCE",
                target_id="branch-main",
                evidence_ids=[
                    card.evidence_id
                    for card in evidence_cards
                    if card.relation_type == "CONFLICTS"
                ],
                action="open an alternative source window for conflicting evidence",
                status="WARN",
            )
        )

    if recovery_mode == "READ_MORE":
        if len(read_windows) > previous_read_window_count:
            passed_checks.append("recovery read expansion increased retained windows")
        else:
            warnings.append("recovery read expansion did not increase retained windows")
            recovery_actions.append("open one more targeted source window before synthesis")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="RECOVERY_READ_EXPANSION_INSUFFICIENT",
                    action="open one more targeted source window before synthesis",
                    status="WARN",
                    notes=[
                        f"previous_read_windows={previous_read_window_count}",
                        f"current_read_windows={len(read_windows)}",
                    ],
                )
            )
    elif recovery_mode == "EXTRACT_AGAIN":
        if len(evidence_cards) > previous_evidence_card_count:
            passed_checks.append("recovery extraction produced incremental evidence")
        else:
            warnings.append("recovery extraction did not produce incremental evidence")
            recovery_actions.append("tighten extraction policy or open a new high-signal window")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="RECOVERY_EXTRACTION_NO_GAIN",
                    action="tighten extraction policy or open a new high-signal window",
                    status="WARN",
                    notes=[
                        f"previous_evidence_cards={previous_evidence_card_count}",
                        f"current_evidence_cards={len(evidence_cards)}",
                    ],
                )
            )
    elif recovery_mode == "COUNTERFACTUAL_RECHECK":
        if (
            any(hit.search_angle == "counterfactual" for hit in search_hits)
            or any("counterfactual" in window.read_focus.lower() for window in read_windows)
            or any(card.relation_type == "CONFLICTS" for card in evidence_cards)
        ):
            passed_checks.append("counterfactual recovery produced verifier-visible conflict evidence")
        else:
            warnings.append("counterfactual recovery did not surface explicit conflict recheck evidence")
            recovery_actions.append("target a conflicting source window before synthesis")
            decision_records.append(
                _decision(
                    scope="LOCAL",
                    decision_type="RECOVER",
                    reason_code="COUNTERFACTUAL_SIGNAL_MISSING",
                    action="target a conflicting source window before synthesis",
                    status="WARN",
                )
            )

    if intent_alignment.status == "PASS":
        passed_checks.append("research intent is aligned with the current closed-loop state")
    else:
        warnings.append("research intent still has unmet alignment requirements")
        recovery_actions.append("close the missing research-intent requirements before final synthesis")
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="RECOVER",
                reason_code=intent_alignment.reason_code,
                action="close the missing research-intent requirements before final synthesis",
                status="WARN",
                notes=intent_alignment.missing_requirements[:4],
            )
        )

    if llm_client is not None and evidence_cards:
        llm_warning, llm_actions, llm_decisions = _run_llm_local_judge(
            llm_client,
            plan,
            ledger,
            evidence_cards,
            recovery_mode=recovery_mode,
        )
        warnings.extend(llm_warning)
        recovery_actions.extend(llm_actions)
        decision_records.extend(llm_decisions)

    status = "PASS" if not warnings else "WARN"
    if status == "PASS":
        decision_records.append(
            _decision(
                scope="LOCAL",
                decision_type="PASS",
                reason_code="VERIFIED_PATH",
                action="allow verifier-gated synthesis",
                status="PASS",
                notes=[f"verified_rows={ledger.verified_row_count}"],
            )
        )
    return LocalVerifierResult(
        status=status,
        passed_checks=passed_checks,
        warnings=warnings,
        recovery_actions=recovery_actions,
        decision_records=decision_records,
        intent_completion_contract=intent_completion_contract,
        research_intent_alignment=intent_alignment,
    )


def _run_llm_local_judge(
    llm_client: LlmClient,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    evidence_cards: list[ResearchEvidenceCard],
    recovery_mode: str,
) -> tuple[list[str], list[str], list[ResearchVerifierDecision]]:
    response = llm_client.complete_json(
        "research.verify.local",
        {
            "question": plan.normalized_question,
            "recovery_mode": recovery_mode or "DEFAULT",
            "ledger_rows": [
                {
                    "evidence_id": row.evidence_id,
                    "claim_text": row.claim_text,
                    "support_level": row.support_level,
                    "relation_type": row.relation_type,
                    "support_score": row.support_score,
                    "conflict_score": row.conflict_score,
                }
                for row in ledger.rows
            ],
            "evidence_ids": [card.evidence_id for card in evidence_cards],
            "schema": {
                "status": "PASS | WARN",
                "warnings": ["short warning"],
                "recovery_actions": ["short recovery action"],
            },
        },
    )
    payload = parse_json_payload(response)
    if not isinstance(payload, dict):
        return [], [], []

    warnings = _string_list(payload.get("warnings"))
    recovery_actions = _string_list(payload.get("recovery_actions"))
    status = str(payload.get("status") or "").strip().upper()
    if status not in {"", "PASS", "WARN"}:
        warnings.append(f"llm verifier returned unsupported status: {status}")
    decision_type = "PASS" if status == "PASS" and not warnings else "WARN"
    return warnings, recovery_actions, [
        _decision(
            scope="LOCAL_LLM",
            decision_type=decision_type,
            reason_code="LLM_JUDGE_FEEDBACK",
            action="; ".join(recovery_actions[:2]),
            status=status or "WARN",
            notes=warnings[:3],
        )
    ]


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def run_global_verifier(
    local_result: LocalVerifierResult,
    ledger: ResearchStateLedger,
    branch_decisions: list[ResearchBranchDecision],
) -> GlobalVerifierResult:
    counterfactual_checks = [
        "Would the conclusion change if the strongest source were removed?",
        "Is every key finding backed by an opened source window rather than style memory?",
        "Did the branch controller avoid synthesis when search/read/extract objects are missing?",
    ]
    recovery_actions: list[str] = []
    for branch_decision in branch_decisions:
        recovery_actions.extend(branch_decision.recovery_actions)

    has_recovery_branch = any(
        branch_decision.decision != "NO_BRANCH"
        and branch_decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
        for branch_decision in branch_decisions
    )
    unresolved_penalty = min(0.5, len(ledger.unresolved_questions) * 0.15)
    conflict_penalty = min(0.35, ledger.conflicted_row_count * 0.2)
    fetch_penalty = 0.0
    if any(
        record.reason_code == "EXTERNAL_FETCH_FALLBACK_HEAVY"
        for record in local_result.decision_records
    ):
        fetch_penalty = 0.25
    orchestration_penalty = 0.0
    if any(
        record.reason_code == "EXTERNAL_PROVIDER_FALLBACK_HEAVY"
        for record in local_result.decision_records
    ):
        orchestration_penalty += 0.1
    if any(
        record.reason_code == "EXTERNAL_TRANSPORT_FALLBACK_HEAVY"
        for record in local_result.decision_records
    ):
        orchestration_penalty += 0.1
    archive_penalty = 0.0
    if any(
        record.reason_code == "EXTERNAL_SNAPSHOT_ARCHIVE_NOT_READY"
        for record in local_result.decision_records
    ):
        archive_penalty += 0.1
    completion_score = round(
        max(
            0.0,
            min(
                1.0,
                ledger.coverage_score - unresolved_penalty - conflict_penalty - fetch_penalty - orchestration_penalty - archive_penalty,
            ),
        ),
        4,
    )
    decision_records: list[ResearchVerifierDecision] = []
    intent_completion_contract, research_intent_alignment = _build_global_intent_state(ledger)
    quality_reason_codes = {
        record.reason_code
        for record in local_result.decision_records
    }
    global_blockers = quality_reason_codes.intersection(
        {
            "FORBIDDEN_PATTERN_FOUND",
            "PROMPT_INJECTION_DETECTED",
            "SINGLE_SOURCE_DOMINANCE",
            "LOW_TRUST_SOURCE_FOUNDATION",
            "EXTERNAL_FETCH_FALLBACK_HEAVY",
            "EXTERNAL_PROVIDER_FALLBACK_HEAVY",
            "EXTERNAL_TRANSPORT_FALLBACK_HEAVY",
            "EXTERNAL_SNAPSHOT_ARCHIVE_NOT_READY",
            "REQUIRED_FINDINGS_PARTIAL",
        }
    )
    has_verified_coverage = ledger.verified_row_count > 0 or any(
        row.row_status == "VERIFIED" for row in ledger.rows
    )
    if (
        has_verified_coverage
        and research_intent_alignment.status == "PASS"
        and not ledger.unresolved_questions
        and not has_recovery_branch
        and not global_blockers
        and ledger.conflicted_row_count == 0
    ):
        decision_records.append(
            _decision(
                scope="GLOBAL",
                decision_type="READY_TO_WRITE",
                reason_code="STOP_CONTRACT_SATISFIED",
                action="allow verifier-gated synthesis",
                status="PASS",
                notes=[f"completion_score={completion_score:.2f}"],
            )
        )
        return GlobalVerifierResult(
            status="PASS",
            decision="READY_TO_WRITE",
            summary=(
                "The research loop has search hits, read windows, evidence cards, "
                "and a populated Table-as-State ledger."
            ),
            counterfactual_checks=counterfactual_checks,
            recovery_actions=recovery_actions,
            completion_score=completion_score,
            decision_records=decision_records,
            intent_completion_contract=intent_completion_contract,
            research_intent_alignment=research_intent_alignment,
        )

    if ledger.unresolved_questions:
        recovery_actions.append("mark unresolved questions explicitly in the report")
    if research_intent_alignment.status != "PASS":
        recovery_actions.append("keep missing research-intent requirements explicit in the report")
    if global_blockers:
        recovery_actions.append(
            "resolve global quality blockers before unguarded synthesis: "
            + ", ".join(sorted(global_blockers))
        )
    decision_records.append(
        _decision(
            scope="GLOBAL",
            decision_type="WRITE_WITH_GUARDRAILS",
            reason_code=(
                "RESEARCH_INTENT_PARTIAL"
                if research_intent_alignment.status != "PASS"
                else
                "UNRESOLVED_QUESTIONS"
                if ledger.unresolved_questions
                else "COUNTERFACTUAL_OR_LOW_CONFIDENCE"
            ),
            action=(
                "keep missing research-intent requirements explicit in the report"
                if research_intent_alignment.status != "PASS"
                else "mark unresolved questions explicitly in the report"
            ),
            status="WARN",
            notes=[f"completion_score={completion_score:.2f}"]
            + research_intent_alignment.missing_requirements[:3]
            + sorted(global_blockers)[:3],
        )
    )

    return GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary=(
            "The report can be written, but uncertainty and missing evidence must stay visible."
        ),
        counterfactual_checks=counterfactual_checks,
        recovery_actions=recovery_actions,
        completion_score=completion_score,
        decision_records=decision_records,
        intent_completion_contract=intent_completion_contract,
        research_intent_alignment=research_intent_alignment,
    )


def _build_global_intent_state(
    ledger: ResearchStateLedger,
) -> tuple[ResearchIntentCompletionContract, ResearchIntentAlignment]:
    requirements: list[ResearchIntentRequirement] = []
    missing: list[str] = []
    covered: list[str] = []
    for progress in ledger.required_finding_progress:
        ready = progress.status == "READY"
        if ready:
            covered.append(progress.label)
        else:
            missing.append(progress.label)
        requirements.append(
            ResearchIntentRequirement(
                requirement_id=progress.requirement_id,
                requirement_type=progress.requirement_type,
                label=progress.label,
                status="PASS" if ready else "WARN",
                evidence_anchor="state_ledger.required_finding_progress",
                evidence_refs=list(progress.ready_row_ids),
                required_columns=list(progress.required_columns),
                accepted_row_statuses=list(progress.accepted_row_statuses),
                target_row_count=progress.target_row_count,
                ready_row_ids=list(progress.ready_row_ids),
                coverage_note="requirement-ready rows exist" if ready else "",
                missing_reason="" if ready else "no requirement-ready row exists",
            )
        )
    status = "PASS" if not missing else "WARN"
    contract = ResearchIntentCompletionContract(
        status=status,
        reason_code=(
            "INTENT_REQUIREMENTS_COMPLETE"
            if status == "PASS"
            else "INTENT_REQUIREMENTS_PARTIAL"
        ),
        total_requirement_count=len(requirements),
        satisfied_requirement_count=len(requirements) - len(missing),
        pending_requirement_count=len(missing),
        missing_requirement_labels=missing,
        requirements=requirements,
    )
    alignment = ResearchIntentAlignment(
        status=status,
        reason_code="INTENT_ALIGNED" if status == "PASS" else "INTENT_PARTIAL",
        goal_status=(
            "PASS"
            if any(item.requirement_type == "GOAL_FINDING" and item.status == "PASS" for item in requirements)
            else "NOT_REQUESTED"
            if not any(item.requirement_type == "GOAL_FINDING" for item in requirements)
            else "WARN"
        ),
        covered_requirements=covered,
        missing_requirements=missing,
    )
    return contract, alignment


def _forbidden_pattern_hits(
    evidence_cards: list[ResearchEvidenceCard],
    forbidden_patterns: list[str],
) -> list[str]:
    patterns = [pattern.strip().lower() for pattern in forbidden_patterns if pattern.strip()]
    if not patterns:
        return []
    hits: list[str] = []
    for card in evidence_cards:
        text = f"{card.claim_text} {card.quote_text}".lower()
        for pattern in patterns:
            if pattern in text:
                hits.append(f"{card.evidence_id}:{pattern}")
    return list(dict.fromkeys(hits))


def _dominant_verified_source(rows) -> tuple[str, int, int] | None:
    if len(rows) < 3:
        return None
    counts: dict[str, int] = {}
    for row in rows:
        source_id = (row.source_id or row.source_title or "unknown-source").strip()
        counts[source_id] = counts.get(source_id, 0) + 1
    source_id, count = max(counts.items(), key=lambda item: item[1])
    if count / len(rows) >= 0.8:
        return source_id, count, len(rows)
    return None


def _build_research_intent_completion_contract(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    evidence_cards: list[ResearchEvidenceCard],
    branch_decisions: list[ResearchBranchDecision],
) -> ResearchIntentCompletionContract:
    intent = task_input.input_payload.research_intent
    constraints = [item.strip() for item in intent.constraints if item.strip()]
    requested_depth = (intent.depth or "STANDARD").strip().upper() or "STANDARD"
    planned_depth = str(plan.stop_contract.get("depth", "STANDARD")).strip().upper() or "STANDARD"
    requirements: list[ResearchIntentRequirement] = []
    finding_progress_by_id = {
        item.requirement_id: item
        for item in ledger.required_finding_progress
    }

    if intent.research_goal.strip():
        goal_progress = finding_progress_by_id.get("goal_finding")
        goal_ready = bool(goal_progress and goal_progress.ready_row_ids)
        requirements.append(
            _requirement(
                requirement_id="goal",
                requirement_type="GOAL",
                label=f"Research goal: {intent.research_goal.strip()}",
                status="PASS" if goal_ready else "WARN",
                evidence_anchor="state_ledger.rows",
                evidence_refs=list(goal_progress.ready_row_ids[:3]) if goal_progress else [],
                required_columns=list(goal_progress.required_columns) if goal_progress else [],
                accepted_row_statuses=list(goal_progress.accepted_row_statuses) if goal_progress else ["VERIFIED"],
                target_row_count=goal_progress.target_row_count if goal_progress else 1,
                ready_row_ids=list(goal_progress.ready_row_ids[:3]) if goal_progress else [],
                coverage_note=(
                    "research goal is anchored to requirement-ready ledger rows"
                    if goal_ready
                    else ""
                ),
                missing_reason=(
                    ""
                    if goal_ready
                    else "research goal was provided but no requirement-ready verified row exists yet"
                ),
            )
        )

    if intent.deliverable_format.strip():
        requirements.append(
            _requirement(
                requirement_id="deliverable",
                requirement_type="DELIVERABLE",
                label=f"Deliverable format: {intent.deliverable_format.strip()}",
                status="PASS" if bool(plan.report_sections) else "WARN",
                evidence_anchor="report_sections",
                evidence_refs=list(plan.report_sections[:3]),
                required_columns=[],
                accepted_row_statuses=[],
                target_row_count=0,
                ready_row_ids=[],
                coverage_note=(
                    "deliverable format was compiled into the report plan"
                    if plan.report_sections
                    else ""
                ),
                missing_reason=(
                    ""
                    if plan.report_sections
                    else "deliverable format was provided but no report structure is ready"
                ),
            )
        )

    if intent.time_range.strip():
        time_range_compiled = any(intent.time_range.lower() in query.lower() for query in plan.query_set)
        requirements.append(
            _requirement(
                requirement_id="time_range",
                requirement_type="TIME_RANGE",
                label=f"Time range: {intent.time_range.strip()}",
                status="PASS" if time_range_compiled else "WARN",
                evidence_anchor="query_set",
                evidence_refs=[
                    query
                    for query in plan.query_set
                    if intent.time_range.lower() in query.lower()
                ][:3],
                required_columns=[],
                accepted_row_statuses=[],
                target_row_count=0,
                ready_row_ids=[],
                coverage_note=(
                    "time range was compiled into the research query bundle"
                    if time_range_compiled
                    else ""
                ),
                missing_reason=(
                    ""
                    if time_range_compiled
                    else "time range was provided but is missing from the planned query bundle"
                ),
            )
        )

    depth_matches = requested_depth == planned_depth
    requirements.append(
        _requirement(
            requirement_id="depth",
            requirement_type="DEPTH",
            label=f"Depth tier: {requested_depth}",
            status="PASS" if depth_matches else "WARN",
            evidence_anchor="stop_contract.depth",
            evidence_refs=[planned_depth],
            required_columns=[],
            accepted_row_statuses=[],
            target_row_count=0,
            ready_row_ids=[],
            coverage_note=(
                f"depth tier {planned_depth} is applied to the stop contract"
                if depth_matches
                else ""
            ),
            missing_reason=(
                ""
                if depth_matches
                else f"requested depth {requested_depth} does not match planned depth {planned_depth}"
            ),
        )
    )

    for index, constraint in enumerate(constraints, start=1):
        requirements.append(
            _evaluate_constraint_requirement(
                constraint=constraint,
                requirement_id=f"constraint-{index}",
                plan=plan,
                ledger=ledger,
                search_hits=search_hits,
                evidence_cards=evidence_cards,
                branch_decisions=branch_decisions,
            )
        )

    missing_requirements = [item.label for item in requirements if item.status != "PASS"]
    status = "PASS" if not missing_requirements else "WARN"
    reason_code = "INTENT_REQUIREMENTS_COMPLETE" if status == "PASS" else "INTENT_REQUIREMENTS_PARTIAL"
    return ResearchIntentCompletionContract(
        status=status,
        reason_code=reason_code,
        total_requirement_count=len(requirements),
        satisfied_requirement_count=sum(1 for item in requirements if item.status == "PASS"),
        pending_requirement_count=sum(1 for item in requirements if item.status != "PASS"),
        missing_requirement_labels=missing_requirements,
        requirements=requirements,
    )


def _build_research_intent_alignment(
    task_input: ResearchTaskInput,
    contract: ResearchIntentCompletionContract,
) -> ResearchIntentAlignment:
    intent = task_input.input_payload.research_intent
    constraints = [item.strip() for item in intent.constraints if item.strip()]
    goal_status = _requirement_status(contract, "GOAL")
    deliverable_status = _requirement_status(contract, "DELIVERABLE")
    time_range_status = _requirement_status(contract, "TIME_RANGE")
    depth_status = _requirement_status(contract, "DEPTH", default="PASS")

    covered_requirements = [
        requirement.coverage_note
        for requirement in contract.requirements
        if requirement.status == "PASS" and requirement.coverage_note
    ]
    missing_requirements = [
        requirement.missing_reason or requirement.label
        for requirement in contract.requirements
        if requirement.status != "PASS"
    ]
    satisfied_constraint_count = sum(
        1
        for requirement in contract.requirements
        if requirement.requirement_type == "CONSTRAINT" and requirement.status == "PASS"
    )
    reason_code = "INTENT_ALIGNED" if contract.status == "PASS" else "INTENT_REQUIREMENTS_PARTIAL"
    return ResearchIntentAlignment(
        status=contract.status,
        reason_code=reason_code,
        goal_status=goal_status,
        deliverable_status=deliverable_status,
        time_range_status=time_range_status,
        depth_status=depth_status,
        satisfied_constraint_count=satisfied_constraint_count,
        total_constraint_count=len(constraints),
        covered_requirements=covered_requirements,
        missing_requirements=missing_requirements,
    )


def _evaluate_constraint_requirement(
    *,
    constraint: str,
    requirement_id: str,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    evidence_cards: list[ResearchEvidenceCard],
    branch_decisions: list[ResearchBranchDecision],
) -> ResearchIntentRequirement:
    normalized = constraint.lower()
    if any(token in normalized for token in ["verified", "evidence", "已验证", "证据"]):
        progress = _find_requirement_progress(ledger, "CONSTRAINT_FINDING")
        satisfied = bool(progress and progress.ready_row_ids)
        return _requirement(
            requirement_id=requirement_id,
            requirement_type="CONSTRAINT",
            label=f"Constraint: {constraint}",
            status="PASS" if satisfied else "WARN",
            evidence_anchor="state_ledger.verified_row_count",
            evidence_refs=list(progress.ready_row_ids[:3]) if progress else [],
            required_columns=list(progress.required_columns) if progress else [],
            accepted_row_statuses=list(progress.accepted_row_statuses) if progress else ["VERIFIED"],
            target_row_count=progress.target_row_count if progress else 1,
            ready_row_ids=list(progress.ready_row_ids[:3]) if progress else [],
            coverage_note=f"constraint covered: {constraint}" if satisfied else "",
            missing_reason="" if satisfied else f"constraint still unmet: {constraint}",
        )
    if any(token in normalized for token in ["conflict", "counterfactual", "冲突", "反证"]):
        progress = _find_requirement_progress(ledger, "CONFLICT_FINDING")
        satisfied = (
            bool(progress and progress.ready_row_ids)
            or
            ledger.conflicted_row_count > 0
            or any(hit.search_angle == "counterfactual" for hit in search_hits)
            or any(decision.decision == "COUNTERFACTUAL_RECHECK" for decision in branch_decisions)
            or "Conflicts And Uncertainty" in plan.report_sections
        )
        evidence_refs = [
            row.row_id
            for row in ledger.rows
            if row.row_status == "CONFLICTED"
        ][:3]
        if not evidence_refs:
            evidence_refs = [
                hit.hit_id
                for hit in search_hits
                if hit.search_angle == "counterfactual"
            ][:3]
        return _requirement(
            requirement_id=requirement_id,
            requirement_type="CONSTRAINT",
            label=f"Constraint: {constraint}",
            status="PASS" if satisfied else "WARN",
            evidence_anchor="counterfactual_branch",
            evidence_refs=evidence_refs,
            required_columns=list(progress.required_columns) if progress else [],
            accepted_row_statuses=list(progress.accepted_row_statuses) if progress else ["CONFLICTED"],
            target_row_count=progress.target_row_count if progress else 1,
            ready_row_ids=list(progress.ready_row_ids[:3]) if progress else [],
            coverage_note=f"constraint covered: {constraint}" if satisfied else "",
            missing_reason="" if satisfied else f"constraint still unmet: {constraint}",
        )
    if any(token in normalized for token in ["next action", "recovery", "恢复", "后续"]):
        return _requirement(
            requirement_id=requirement_id,
            requirement_type="CONSTRAINT",
            label=f"Constraint: {constraint}",
            status="PASS",
            evidence_anchor="report.next_actions",
            evidence_refs=[str(plan.stop_contract.get("recovery_mode", "")).strip().upper() or "NEXT_ACTIONS_VISIBLE"],
            required_columns=[],
            accepted_row_statuses=[],
            target_row_count=0,
            ready_row_ids=[],
            coverage_note=f"constraint covered: {constraint}",
            missing_reason="",
        )
    planner_refs = [
        item
        for item in plan.notes + plan.query_set
        if constraint.lower() in item.lower()
    ][:3]
    satisfied = bool(planner_refs)
    return _requirement(
        requirement_id=requirement_id,
        requirement_type="CONSTRAINT",
        label=f"Constraint: {constraint}",
        status="PASS" if satisfied else "WARN",
        evidence_anchor="planner.outputs",
        evidence_refs=planner_refs,
        required_columns=[],
        accepted_row_statuses=[],
        target_row_count=0,
        ready_row_ids=[],
        coverage_note=(
            f"constraint carried into the planner: {constraint}"
            if satisfied
            else ""
        ),
        missing_reason=(
            ""
            if satisfied
            else f"constraint is not visible in the planner outputs yet: {constraint}"
        ),
    )


def _requirement(
    *,
    requirement_id: str,
    requirement_type: str,
    label: str,
    status: str,
    evidence_anchor: str,
    evidence_refs: list[str],
    required_columns: list[str],
    accepted_row_statuses: list[str],
    target_row_count: int,
    ready_row_ids: list[str],
    coverage_note: str,
    missing_reason: str,
) -> ResearchIntentRequirement:
    return ResearchIntentRequirement(
        requirement_id=requirement_id,
        requirement_type=requirement_type,
        label=label,
        status=status,
        evidence_anchor=evidence_anchor,
        evidence_refs=evidence_refs,
        required_columns=required_columns,
        accepted_row_statuses=accepted_row_statuses,
        target_row_count=target_row_count,
        ready_row_ids=ready_row_ids,
        coverage_note=coverage_note,
        missing_reason=missing_reason,
    )


def _find_requirement_progress(
    ledger: ResearchStateLedger,
    requirement_type: str,
):
    for item in ledger.required_finding_progress:
        if item.requirement_type == requirement_type:
            return item
    return None


def _requirement_status(
    contract: ResearchIntentCompletionContract,
    requirement_type: str,
    *,
    default: str = "NOT_REQUESTED",
) -> str:
    for requirement in contract.requirements:
        if requirement.requirement_type == requirement_type:
            return requirement.status
    return default


def _decision(
    scope: str,
    decision_type: str,
    reason_code: str,
    action: str,
    status: str,
    target_id: str = "",
    evidence_ids: list[str] | None = None,
    notes: list[str] | None = None,
) -> ResearchVerifierDecision:
    return ResearchVerifierDecision(
        decision_scope=scope,
        decision_type=decision_type,
        reason_code=reason_code,
        target_id=target_id,
        evidence_ids=evidence_ids or [],
        action=action,
        status=status,
        notes=notes or [],
    )


def _resume_metric(task_input: ResearchTaskInput, key: str) -> int:
    resume_checkpoint = task_input.input_payload.resume_checkpoint
    if resume_checkpoint is None:
        return 0
    value = resume_checkpoint.payload.get(key)
    if isinstance(value, list):
        return len(value)
    return 0


def _recovery_mode(plan: ResearchPlan) -> str:
    return str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
