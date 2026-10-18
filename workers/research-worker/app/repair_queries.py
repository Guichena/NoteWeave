"""按失败原因改写查询；反证查询明确指向待验证的结论。"""
from dataclasses import replace

from app.cell_query_planner import CellQueryPlan, normalize_query, query_rejection_reason
from app.json_repair import parse_json_payload
from app.models import ResearchQuerySpec

STRATEGIES = {"COUNTER_EVIDENCE", "BROADEN", "NARROW", "SWITCH_LANGUAGE", "SWITCH_SOURCE_TYPE"}


def plan_repair_queries(context, target, llm):
    cause = target.get("repair_cause", "MARGINAL_GAIN_EXHAUSTED")
    tried = tuple(target.get("tried_queries", context.tried_queries))
    context = replace(context, tried_queries=tried)
    strategy = ("COUNTER_EVIDENCE" if cause == "EVIDENCE_CONFLICT" else
                "SWITCH_SOURCE_TYPE" if cause in {"FETCH_FAILED", "PROVIDER_FAILED"} else
                "NARROW" if len(tried) < 3 else "SWITCH_LANGUAGE")
    errors = []
    if llm is not None:
        for _ in range(2):
            try:
                raw = parse_json_payload(llm.complete_json(
                    "research.counter.queries" if cause == "EVIDENCE_CONFLICT" else "research.replan.queries",
                    {"policy": "Generate new short search queries for this repair reason. For conflicts target "
                     "the exact opposing claims: exceptions, version differences, counterexamples, errata. "
                     "For exhausted gain broaden, narrow or switch language. For failed providers change "
                     "both query wording and source type. Do not repeat tried queries. Use each column's label and "
                     "description to decide what is still missing. For software, standards and science topics write "
                     "English queries, include known identifiers (JEP, RFC, PEP, version numbers) and keep the "
                     "entity's version. For empirical questions target studies and data. When authoritative domains are "
                     "given, at least one query must add site:<domain> for the best matching one. "
                     f"Return strategy and 1 to {context.max_queries} queries.",
                     "repair_target": target, "entity": context.entity_label,
                     "authoritative_domains": list(context.authoritative_domains),
                     "columns": [vars(c) for c in context.columns], "tried_queries": list(tried),
                     "output_schema": {"strategy": "|".join(sorted(STRATEGIES)), "queries": ["string"]},
                     "validation_errors": errors}))
                chosen = raw.get("strategy") if isinstance(raw, dict) else None
                items = raw.get("queries") if isinstance(raw, dict) else None
                # 只要求包含 strategy 与 queries，模型附带的其他字段忽略，不因此退回规则检索词
                if (not isinstance(raw, dict) or not {"strategy", "queries"} <= set(raw)
                        or chosen not in STRATEGIES or not isinstance(items, list) or not items):
                    raise ValueError("策略、输出字段或查询条数不符合约定")
                # 模型常多给几条；按顺序保留前几条，不因条数超出而整份作废
                items = items[:context.max_queries]
                if cause == "EVIDENCE_CONFLICT" and chosen != "COUNTER_EVIDENCE":
                    raise ValueError("冲突修复必须使用 COUNTER_EVIDENCE")
                if cause in {"FETCH_FAILED", "PROVIDER_FAILED"} and chosen != "SWITCH_SOURCE_TYPE":
                    raise ValueError("获取失败必须使用 SWITCH_SOURCE_TYPE")
                specs = []
                for item in items:
                    if not isinstance(item, str) or any(ord(c) < 32 for c in item):
                        raise ValueError("检索词必须是字符串，且不能包含控制字符")
                    query = normalize_query(item)
                    # 与已试检索词重复的只跳过这一条，不让整份输出作废
                    if query_rejection_reason(query, specs, tried):
                        continue
                    specs.append(ResearchQuerySpec(query=query, family="counterfactual" if chosen == "COUNTER_EVIDENCE" else "coverage_gap",
                                                  search_angle="counterfactual" if chosen == "COUNTER_EVIDENCE" else "coverage_gap",
                                                  search_lane="COUNTERFACTUAL" if chosen == "COUNTER_EVIDENCE" else "DEEP_FOCUS"))
                if not specs:
                    raise ValueError("全部检索词都与已试检索词重复")
                return CellQueryPlan(specs, "LLM"), chosen
            except Exception as exc:
                errors = [str(exc)[:240]]
    claims = [str(c.get("claim_text", "")) for c in target.get("claims", []) if isinstance(c, dict)]
    # 规则检索词：先用每列尚未试过的检索意图原文（规划时已写成完整的检索词），再给它们加上策略后缀；
    # 没有检索意图的列才用实体名加列名。切换语言时直接用用户的原问题，不把中英文拼在一起
    intents = [intent for c in context.columns for intent in c.search_intents if intent.strip()]
    bases = claims or intents or [" ".join([context.entity_label, c.label]) for c in context.columns] or [context.entity_label]
    suffixes = {"COUNTER_EVIDENCE": ["exceptions errata", "version differences counterexample"],
                "SWITCH_SOURCE_TYPE": ["official documentation", "technical report"],
                "NARROW": ["implementation requirements", "documented limitations"],
                "SWITCH_LANGUAGE": ["documentation", "study"],
                "BROADEN": ["overview", "survey"]}[strategy]
    candidates = ([context.question] if strategy == "SWITCH_LANGUAGE" and context.question else [])
    if not claims:
        candidates.extend(bases)
    candidates.extend(f"{base[:140]} {suffix}" for suffix in suffixes for base in bases)
    specs = []
    for candidate in candidates:
        query = normalize_query(candidate)[:200]
        if len(specs) >= context.max_queries:
            break
        if not query_rejection_reason(query, specs, tried):
            specs.append(ResearchQuerySpec(query=query, family="counterfactual" if strategy == "COUNTER_EVIDENCE" else "coverage_gap",
                                          search_angle="counterfactual" if strategy == "COUNTER_EVIDENCE" else "coverage_gap",
                                          search_lane="COUNTERFACTUAL" if strategy == "COUNTER_EVIDENCE" else "DEEP_FOCUS"))
    return CellQueryPlan(specs, "RULE", ["REPAIR_QUERY_LLM_UNAVAILABLE_OR_INVALID"]), strategy
