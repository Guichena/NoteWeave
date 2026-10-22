"""按失败原因改写查询；反证查询明确指向待验证的结论。"""
from dataclasses import replace
from datetime import datetime, timezone
import re

from app.cell_query_planner import CellQueryPlan, ensure_unrestricted_query, normalize_query, query_rejection_reason
from app.json_repair import parse_json_payload
from app.models import ResearchQuerySpec

STRATEGIES = {"COUNTER_EVIDENCE", "BROADEN", "NARROW", "SWITCH_LANGUAGE", "SWITCH_SOURCE_TYPE"}


def plan_repair_queries(context, target, llm):
    cause = target.get("repair_cause", "MARGINAL_GAIN_EXHAUSTED")
    tried = tuple(target.get("tried_queries", context.tried_queries))
    context = replace(context, tried_queries=tried)
    actions = target.get("actions", []) if target.get("dispatch_version") == 1 else []
    missing_texts = list(dict.fromkeys(text for action in actions for text in action.get("missing_requirement_texts", [])))
    corroborate = any(action.get("action") == "CORROBORATE" for action in actions)
    no_gain = max((int(action.get("no_gain_rounds", 0)) for action in actions), default=0)
    condition_targets = [item for action in actions for item in action.get("condition_targets", [])]
    current = any(item.get("temporal_mode") == "CURRENT" for item in condition_targets)
    identifiers = any(item.get("answer_kind") == "IDENTIFIER" for item in condition_targets)
    allowed_versions = {value for scope in context.source_scopes for value in scope.get("required_versions", [])}
    for requirement in target.get("requirements", []):
        if any(item.get("requirement_id") == requirement.get("requirement_id") for item in condition_targets):
            allowed_versions.update(re.findall(r"(?<!\d)\d+\.\d+(?:\.\d+)*", requirement.get("text", "")))
    strategy = ("COUNTER_EVIDENCE" if cause == "EVIDENCE_CONFLICT" else
                "SWITCH_SOURCE_TYPE" if cause in {"FETCH_FAILED", "PROVIDER_FAILED"} else
                "NARROW" if len(tried) < 3 else "SWITCH_LANGUAGE")
    if actions and cause != "EVIDENCE_CONFLICT":
        strategy = "SWITCH_SOURCE_TYPE" if corroborate or no_gain else "NARROW"
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
                     "entity's version. Every query is about the given entity alone: never name another subject the "
                     "question compares it with. For empirical questions target studies and data. Every query must look for "
                     "what the column of repair_target.cell_key asks for: when that column asks for one side of a "
                     "debate, such as supporting evidence or opposing or inconclusive evidence, phrase every query "
                     "toward that side (for supporting evidence 'X reduced Y study', for opposing evidence 'X no "
                     "effect on Y study') and never toward the other side. When authoritative domains are "
                     "given, at least one query must add site:<domain> for the best matching one. "
                     "Keep another query unrestricted. If a publisher page cannot be read, search for the "
                     "same study's title in an author manuscript, preprint or institutional repository; do not "
                     "replace its findings with another study or infer support from its title. "
                     "For current status, target the latest stable release and accepted decisions rather than "
                     "repeating documentation about the first experimental release. "
                     "condition_targets freezes the missing conditions and temporal scope. CURRENT needs an explicit "
                     "effective status and applicable release: target release notes, acceptance announcements and stable version documentation. "
                     "Do not presume an unstated release number. A phase proposal or historical experimental announcement cannot establish current status. "
                     "IDENTIFIER needs the specification number/title correspondence, not installation or performance background. "
                     "When repair_target includes missing_requirement_ids, target those requirements specifically. "
                     "Use qualified_facts to avoid searching again for facts already established. Other requirements "
                     "For CORROBORATE actions, seek independent documentation for unconfirmed_claims; these are hypotheses, not accepted facts. "
                     "are context, not additional work. Preserve the entity and scope attached to each requirement. "
                     f"Return strategy and 1 to {context.max_queries} queries.",
                     "repair_target": target, "entity": context.entity_label,
                     "as_of_date": target.get("as_of_date") or datetime.now(timezone.utc).date().isoformat(),
                     "authoritative_domains": list(context.authoritative_domains),
                     "source_scopes": list(context.source_scopes),
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
                # 缺口单元格还没有结论，找反证没有对象；冲突题的支持证据列因此被检索成反方证据
                if chosen == "COUNTER_EVIDENCE" and cause != "EVIDENCE_CONFLICT" and not target.get("claims"):
                    raise ValueError("目标单元格还没有结论，不能用 COUNTER_EVIDENCE，按列要求的内容补检索")
                if cause in {"FETCH_FAILED", "PROVIDER_FAILED"} and chosen != "SWITCH_SOURCE_TYPE":
                    raise ValueError("获取失败必须使用 SWITCH_SOURCE_TYPE")
                specs = []
                for item in items:
                    if not isinstance(item, str) or any(ord(c) < 32 for c in item):
                        raise ValueError("检索词必须是字符串，且不能包含控制字符")
                    query = normalize_query(item)
                    if current and any(version not in allowed_versions for version in re.findall(r"(?<!\d)\d+\.\d+(?:\.\d+)*", query)):
                        raise ValueError("当前状态查询不能预设冻结需求未指定的版本，先检索正式发布状态及生效说明")
                    # 与已试检索词重复的只跳过这一条，不让整份输出作废
                    if query_rejection_reason(query, specs, tried):
                        continue
                    specs.append(ResearchQuerySpec(query=query, family="counterfactual" if chosen == "COUNTER_EVIDENCE" else "coverage_gap",
                                                  search_angle="counterfactual" if chosen == "COUNTER_EVIDENCE" else "coverage_gap",
                                                  search_lane="COUNTERFACTUAL" if chosen == "COUNTER_EVIDENCE" else "DEEP_FOCUS"))
                if not specs:
                    raise ValueError("全部检索词都与已试检索词重复")
                specs = [spec for spec in ensure_unrestricted_query(specs)
                         if not query_rejection_reason(spec.query, [], tried)]
                if not specs:
                    raise ValueError("移除域名限制后检索词与已试查询重复")
                return CellQueryPlan(specs, "LLM"), chosen
            except Exception as exc:
                errors = [str(exc)[:240]]
    claims = [str(c.get("claim_text", "")) for c in target.get("claims", []) if isinstance(c, dict)]
    if actions and cause != "EVIDENCE_CONFLICT":
        claims = []
    # 规则检索词：先用每列尚未试过的检索意图原文（规划时已写成完整的检索词），再给它们加上策略后缀；
    # 没有检索意图的列才用实体名加列名。切换语言时直接用用户的原问题，不把中英文拼在一起
    intents = [intent for c in context.columns for intent in c.search_intents if intent.strip()]
    pending_claims = list(dict.fromkeys(claim for action in actions if action.get("action") == "CORROBORATE" for claim in action.get("unconfirmed_claims", [])))
    bases = pending_claims or ([context.entity_label + " " + text for text in missing_texts] if missing_texts else []) or claims or intents or [" ".join([context.entity_label, c.label]) for c in context.columns] or [context.entity_label]
    if current and cause != "EVIDENCE_CONFLICT":
        subject = " ".join([context.entity_label, *sorted(allowed_versions)])
        bases = [subject + " current supported status stable release notes effective version",
                 subject + " accepted decision supported release announcement"]
        if identifiers:
            bases.append(context.entity_label + " defining specification number title official documentation")
    elif identifiers and cause != "EVIDENCE_CONFLICT":
        bases = [context.entity_label + " defining specification number title official documentation", *bases]
    suffixes = {"COUNTER_EVIDENCE": ["exceptions errata", "version differences counterexample"],
                "SWITCH_SOURCE_TYPE": ["official documentation", "technical report"],
                "NARROW": ["implementation requirements", "documented limitations"],
                "SWITCH_LANGUAGE": ["documentation", "study"],
                "BROADEN": ["overview", "survey"]}[strategy]
    candidates = ([context.question] if strategy == "SWITCH_LANGUAGE" and context.question else [])
    if current:
        candidates = []
        if context.authoritative_domains:
            candidates.append(bases[0] + " site:" + context.authoritative_domains[0])
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
