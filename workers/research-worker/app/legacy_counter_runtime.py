"""旧循环的原子事实反证，复用线上判定契约并隔离原始证据。"""

from app.counter_checks import MAX_FACTS, atomic_queries, judge_fact
from app.models import ResearchQuerySpec
from app.query_specs import with_query_specs
from app.query_execution import capture_queries
from app.search_adapters import run_research_search
from app.fetch_adapters import run_research_fetch
from app.read_adapters import run_research_read


def _global_counter_required(plan):
    return (str(plan.stop_contract.get("recovery_mode", "")) == "COUNTERFACTUAL_RECHECK"
            or any(token in str(plan.stop_contract.get("constraints", [])).lower()
                   for token in ("counterfactual", "conflict", "反证", "冲突")))


def requires_counter(plan):
    return _global_counter_required(plan) or any(c.high_risk for c in plan.research_schema.columns)


def counter_targets(plan, cards):
    high_risk = {c.key for c in plan.research_schema.columns if c.high_risk}
    target_all = _global_counter_required(plan)
    return [c for c in cards if target_all or c.column_key in high_risk or c.relation_type == "CONFLICTS"]


def run_counter_checks(task_input, plan, cards, original_windows, llm, check_cancelled):
    if not requires_counter(plan) and not any(c.relation_type == "CONFLICTS" for c in cards):
        plan.counter_checks = []
        return
    targets = counter_targets(plan, cards)
    previous = {(r.get("evidence_id"), r.get("claim_text")): r for r in plan.counter_checks}
    results = []
    checked_claims = {}
    excluded_sources = {w.source_id for w in original_windows}
    original_quotes = [c.quote_text for c in cards if c.quote_text]
    spent = int(plan.stop_contract.get("counter_search_calls", 0))
    cap = max(0, min(36, int(plan.stop_contract.get("max_counter_search_calls", MAX_FACTS))))
    for card in targets:
        check_cancelled()
        target_identity = (card.entity_id, card.column_key, card.claim_text)
        if target_identity in checked_claims:
            results.append({**checked_claims[target_identity], "evidence_id": card.evidence_id})
            continue
        identity = (card.evidence_id, card.claim_text)
        old = previous.get(identity)
        if old and old.get("verdict") != "NOT_RUN":
            results.append(old)
            checked_claims[target_identity] = old
            continue
        record = {"evidence_id": card.evidence_id, "claim_text": card.claim_text,
                  "verdict": "NOT_RUN", "facts": [], "reason_code": "COUNTER_NOT_RUN"}
        results.append(record)
        checked_claims[target_identity] = record
        if llm is None or len(checked_claims) > MAX_FACTS or spent >= cap:
            record["reason_code"] = "COUNTER_LLM_UNAVAILABLE" if llm is None else "COUNTER_BUDGET_EXHAUSTED"
            continue
        facts = atomic_queries(card.claim_text, llm, plan.executed_queries)
        if not facts:
            record["reason_code"] = "COUNTER_FACTS_INVALID_OR_UNAVAILABLE"
            continue
        for fact_index, fact in enumerate(facts):
            check_cancelled()
            result = {"claim_text": fact["claim_text"], "query": fact["query"], "verdict": "NOT_RUN",
                      "search_executed": False, "read_window_ids": [], "citations": []}
            record["facts"].append(result)
            if spent >= cap:
                continue
            spent += 1
            plan.stop_contract["counter_search_calls"] = spent
            isolated = with_query_specs(plan, [ResearchQuerySpec(
                query=fact["query"], family="counterfactual", search_angle="counterfactual", search_lane="COUNTERFACTUAL")])
            isolated.stop_contract = {**plan.stop_contract, "global_search_limit": 3, "search_query_budget": 1,
                                      "tool_response_retention_budget": 3, "recovery_mode": "COUNTERFACTUAL_RECHECK"}
            try:
                with capture_queries() as queries:
                    try:
                        hits = run_research_search(task_input, isolated)
                    finally:
                        result["search_executed"] = fact["query"] in queries
                        plan.executed_queries = list(dict.fromkeys([*plan.executed_queries, *queries]))
                hits = [h for h in hits if h.source_id not in excluded_sources]
                check_cancelled()
                documents = run_research_fetch(task_input, isolated, hits)
                windows = run_research_read(task_input, isolated, documents)
                windows = [w for w in windows if w.source_id not in excluded_sources
                           and w.window_text.strip() and not w.fetch_error_reason and not w.prompt_injection_detected
                           and not any(q in w.window_text for q in original_quotes)]
                windows = [w.model_copy(update={"window_id": f"counter-{card.evidence_id}-{fact_index}-{w.window_id}"})
                           for w in windows]
                result["passages"] = [w.model_dump(mode="json") for w in windows]
                result["read_window_ids"] = [w.window_id for w in windows]
                if not result["search_executed"]:
                    continue
                check_cancelled()
                judgement = judge_fact(fact["claim_text"], windows, llm)
                if judgement is not None:
                    result.update(judgement)
            except Exception:
                # 工具失败保留未执行状态，取消仍由上层检查抛出。
                check_cancelled()
        verdicts = [f["verdict"] for f in record["facts"]]
        record["verdict"] = ("NOT_RUN" if "NOT_RUN" in verdicts else "REFUTED" if "REFUTED" in verdicts
                             else "NO_COUNTER_EVIDENCE_FOUND" if "NO_COUNTER_EVIDENCE_FOUND" in verdicts else "NOT_REFUTED")
        record["reason_code"] = "COUNTER_" + record["verdict"]
    if not targets:
        results.append({"verdict": "NOT_RUN", "reason_code": "COUNTER_NO_TARGET_CLAIM", "facts": []})
    plan.counter_checks = results


def counter_completed(record):
    facts = record.get("facts", [])
    if not facts or record.get("verdict") not in {"REFUTED", "NOT_REFUTED"}:
        return False
    for fact in facts:
        passages = {p.get("window_id"): p.get("window_text", "") for p in fact.get("passages", [])}
        citations = fact.get("citations", [])
        if (not fact.get("search_executed") or not fact.get("query")
                or fact.get("verdict") not in {"REFUTED", "NOT_REFUTED"}
                or not fact.get("read_window_ids") or not citations):
            return False
        for citation in citations:
            quote = citation.get("quote_text")
            if (not isinstance(quote, str) or not quote.strip()
                    or citation.get("window_id") not in fact["read_window_ids"]
                    or quote not in passages.get(citation.get("window_id"), "")):
                return False
    expected = "REFUTED" if any(f.get("verdict") == "REFUTED" for f in facts) else "NOT_REFUTED"
    return record.get("verdict") == expected
