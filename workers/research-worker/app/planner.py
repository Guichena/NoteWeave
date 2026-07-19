from __future__ import annotations

from dataclasses import dataclass

from app.intent_contract import build_required_finding_contract
from app.models import ResearchColumn, ResearchColumnDtype, ResearchPlan, ResearchSchema, ResearchTaskInput


def _resolve_depth_config(depth: str) -> dict[str, object]:
    depth = _canonical_depth(depth)
    return {
        "QUICK": {
            "global_search_limit": 4,
            "search_query_budget": 2,
            "per_query_result_limit": 2,
            "tool_response_retention_budget": 3,
            "branch_budget": 0,
            # P0-6: QUICK 保留原 2 轮, cell retry 1
            "max_loop_rounds": 2,
            "max_retry_per_cell": 1,
            # This is a task-wide budget, not a per-tool timeout.  It prevents
            # bounded retries from adding up to an unbounded wall-clock run.
            "max_wall_clock_seconds": 90,
            "min_sources_cap": 1,
            "planning_mode": "FAST_GUARDED",
            "web_search_posture": "MINIMAL",
            "wide_search_enabled": False,
            "deep_focus_query_enabled": False,
            "coverage_gap_query_enabled": False,
            "counterfactual_query_enabled": False,
            "source_scope_bias": "SOURCE_FIRST",
        },
        "DEEP": {
            "global_search_limit": 12,
            "search_query_budget": 8,
            "per_query_result_limit": 3,
            "tool_response_retention_budget": 7,
            "branch_budget": 2,
            # P0-6: DEEP 6 轮, cell retry 5
            "max_loop_rounds": 6,
            "max_retry_per_cell": 5,
            "max_wall_clock_seconds": 480,
            "min_sources_cap": 5,
            "planning_mode": "WIDE_AND_DEEP",
            "web_search_posture": "EXPANSIVE",
            "wide_search_enabled": True,
            "deep_focus_query_enabled": True,
            "coverage_gap_query_enabled": True,
            "counterfactual_query_enabled": True,
            "source_scope_bias": "SOURCE_PLUS_WEB",
        },
        "STANDARD": {
            "global_search_limit": 8,
            "search_query_budget": 5,
            "per_query_result_limit": 2,
            "tool_response_retention_budget": 5,
            "branch_budget": 1,
            # P0-6: STANDARD 4 轮, cell retry 3
            "max_loop_rounds": 4,
            "max_retry_per_cell": 3,
            "max_wall_clock_seconds": 240,
            "min_sources_cap": 3,
            "planning_mode": "BALANCED_COVERAGE",
            "web_search_posture": "BALANCED",
            "wide_search_enabled": True,
            "deep_focus_query_enabled": False,
            "coverage_gap_query_enabled": True,
            "counterfactual_query_enabled": True,
            "source_scope_bias": "SOURCE_GUIDED",
        },
    }.get(depth, {
        "global_search_limit": 8,
        "search_query_budget": 5,
        "per_query_result_limit": 2,
        "tool_response_retention_budget": 5,
        "branch_budget": 1,
        "max_loop_rounds": 4,
        "max_retry_per_cell": 3,
        "max_wall_clock_seconds": 240,
        "min_sources_cap": 3,
        "planning_mode": "BALANCED_COVERAGE",
        "web_search_posture": "BALANCED",
        "wide_search_enabled": True,
        "deep_focus_query_enabled": False,
        "coverage_gap_query_enabled": True,
        "counterfactual_query_enabled": True,
        "source_scope_bias": "SOURCE_GUIDED",
    })


def _canonical_depth(depth: str) -> str:
    normalized = (depth or "STANDARD").strip().upper() or "STANDARD"
    return {
        "FAST": "QUICK",
        "HIGH_CONFIDENCE": "DEEP",
    }.get(normalized, normalized)


def _build_query_family_budgets(
    *,
    depth: str,
    has_source_scope: bool,
    deep_focus_enabled: bool,
    coverage_gap_enabled: bool,
    counterfactual_enabled: bool,
) -> dict[str, int]:
    family_budgets = {
        "direct": 1,
        "source_scoped": 2 if has_source_scope and depth == "DEEP" else 1 if has_source_scope else 0,
        "intent": 1,
        "deliverable": 1,
        "time_range": 1,
        "constraints": 2 if depth == "DEEP" else 1,
        "deep_focus": 1 if deep_focus_enabled else 0,
        "triangulation": 1 if deep_focus_enabled and depth == "DEEP" else 0,
        "coverage_gap": 1 if coverage_gap_enabled else 0,
        "verified_evidence": 2 if depth == "DEEP" else 1,
        "direct_evidence": 1,
        "counterfactual": 1 if counterfactual_enabled else 0,
    }
    return family_budgets


def _build_execution_profile(
    *,
    depth: str,
    depth_config: dict[str, object],
    has_source_scope: bool,
) -> dict[str, object]:
    search_angles = [
        "direct",
        "source_scoped",
    ]
    query_family_order = [
        "direct",
        "source_scoped",
        "intent",
        "deep_focus",
        "coverage_gap",
        "deliverable",
        "time_range",
        "constraints",
    ]
    if bool(depth_config["deep_focus_query_enabled"]):
        search_angles.append("deep_focus")
        query_family_order.append("triangulation")
    if has_source_scope and bool(depth_config["coverage_gap_query_enabled"]):
        search_angles.append("coverage_gap")
    if has_source_scope and bool(depth_config["counterfactual_query_enabled"]):
        search_angles.append("counterfactual")
        query_family_order.append("counterfactual")
    query_family_order.extend(["verified_evidence", "direct_evidence"])
    query_family_budgets = _build_query_family_budgets(
        depth=depth,
        has_source_scope=has_source_scope,
        deep_focus_enabled=bool(depth_config["deep_focus_query_enabled"]),
        coverage_gap_enabled=bool(has_source_scope and depth_config["coverage_gap_query_enabled"]),
        counterfactual_enabled=bool(has_source_scope and depth_config["counterfactual_query_enabled"]),
    )
    return {
        "depth": depth,
        "planning_mode": str(depth_config["planning_mode"]),
        "web_search_posture": str(depth_config["web_search_posture"]),
        "wide_search_enabled": bool(depth_config["wide_search_enabled"]),
        "deep_focus_query_enabled": bool(depth_config["deep_focus_query_enabled"]),
        "coverage_gap_query_enabled": bool(has_source_scope and depth_config["coverage_gap_query_enabled"]),
        "counterfactual_query_enabled": bool(has_source_scope and depth_config["counterfactual_query_enabled"]),
        "source_scope_bias": str(depth_config["source_scope_bias"]),
        "search_angles": search_angles,
        "query_family_order": query_family_order,
        "query_family_budgets": query_family_budgets,
        "loop_round_cap": int(depth_config["max_loop_rounds"]),
        "global_search_limit": int(depth_config["global_search_limit"]),
        "search_query_budget": int(depth_config["search_query_budget"]),
        "per_query_result_limit": int(depth_config["per_query_result_limit"]),
    }


def build_research_plan(task_input: ResearchTaskInput) -> ResearchPlan:
    question = task_input.input_payload.question.strip()
    profile_key = task_input.input_payload.profile_key.strip().upper() or "DEFAULT"
    research_intent = task_input.input_payload.research_intent
    depth = _canonical_depth(research_intent.depth or "STANDARD")
    depth_config = _resolve_depth_config(depth)
    execution_profile = _build_execution_profile(
        depth=depth,
        depth_config=depth_config,
        has_source_scope=bool(task_input.source_scope),
    )
    required_finding_contract = build_required_finding_contract(question, research_intent)

    query_set = [question]
    if research_intent.research_goal:
        query_set.append(f"{question} :: research goal :: {research_intent.research_goal}".strip())
    if research_intent.time_range:
        query_set.append(f"{question} :: time range :: {research_intent.time_range}".strip())
    if research_intent.deliverable_format:
        query_set.append(f"{question} :: deliverable :: {research_intent.deliverable_format}".strip())
    for constraint in research_intent.constraints[:3]:
        query_set.append(f"{question} :: constraint :: {constraint}".strip())
    for item in task_input.source_scope[:3]:
        query_set.append(f"{question} :: {item.title}".strip())
    query_set.extend(
        _build_requirement_targeted_queries(
            question=question,
            required_finding_contract=required_finding_contract,
        )
    )
    if execution_profile["deep_focus_query_enabled"]:
        query_set.append(f"{question} :: deep focus :: verify the core research goal with explicit evidence")
        query_set.append(f"{question} :: triangulation :: cross-check independent evidence and conflicts")
    if execution_profile["coverage_gap_query_enabled"]:
        query_set.append(f"{question} :: coverage gap check")
    if execution_profile["counterfactual_query_enabled"]:
        query_set.append(f"{question} :: counterfactual evidence check")

    # P0-1: 用 ResearchSchema 替换 11 个元数据列
    research_type = _detect_research_type(question, research_intent)
    report_sections = _build_report_sections(research_type, research_intent)
    schema = _build_research_schema(research_type, depth, research_intent)
    # 兼容字段:state_columns 直接由 schema.columns[*].key 派生
    state_columns = [col.key for col in schema.columns]
    target_entity_type = _infer_target_entity_type(research_type)

    notes: list[str] = []
    if task_input.control_pack.style_constraints:
        notes.append(
            "style constraints: " + "; ".join(task_input.control_pack.style_constraints)
        )
    if task_input.control_pack.structure_constraints:
        notes.append(
            "structure constraints: "
            + "; ".join(task_input.control_pack.structure_constraints)
        )
    if research_intent.research_goal:
        notes.append("research goal: " + research_intent.research_goal)
    if research_intent.deliverable_format:
        notes.append("deliverable format: " + research_intent.deliverable_format)
    if research_intent.constraints:
        notes.append("explicit constraints: " + "; ".join(research_intent.constraints))
    if research_intent.time_range:
        notes.append("time range: " + research_intent.time_range)
    notes.append("depth tier: " + depth)
    notes.append("research type: " + research_type)
    notes.append(
        "table schema: " + ", ".join(col.key for col in schema.columns[:6])
        + ("..." if len(schema.columns) > 6 else "")
    )
    if task_input.input_payload.resume_checkpoint is not None:
        notes.append(
            "resume from checkpoint: "
            f"{task_input.input_payload.resume_checkpoint.source_research_run_id}"
            f"#{task_input.input_payload.resume_checkpoint.checkpoint_no}"
        )

    stop_contract = {
        "profile_key": profile_key,
        "research_goal": research_intent.research_goal,
        "deliverable_format": research_intent.deliverable_format,
        "constraints": list(research_intent.constraints),
        "time_range": research_intent.time_range,
        "depth": depth,
        "required_sections": report_sections,
        "min_sources": min(depth_config["min_sources_cap"], max(1, len(task_input.source_scope) or 1)),
        "must_respect_evidence_policy": True,
        "requires_local_verifier": True,
        "requires_global_verifier": True,
        "global_search_limit": depth_config["global_search_limit"],
        "tool_response_retention_budget": depth_config["tool_response_retention_budget"],
        "branch_budget": depth_config["branch_budget"],
        # P0-6: max_loop_rounds 与 max_retry_per_cell 分离
        "max_loop_rounds": depth_config["max_loop_rounds"],
        "max_retry_per_cell": depth_config["max_retry_per_cell"],
        "max_wall_clock_seconds": depth_config["max_wall_clock_seconds"],
        "wall_clock_seconds_consumed": 0.0,
        "deadline_policy": "FINISH_CURRENT_ROUND_THEN_GUARDED_OUTPUT",
        "min_evidence_cards": 1,
        "search_angles": list(execution_profile["search_angles"]),
        "enable_source_quality_profile": True,
        "enable_wide_deep_read_strategy": True,
        "execution_profile": execution_profile,
        "required_finding_contract": required_finding_contract,
        "research_type": research_type,
        "target_entity_type": target_entity_type,
        "source_scope_count": len(task_input.source_scope),
        "abandon_conditions": [
            "NO_SEARCH_HITS_WITHOUT_EVIDENCE",
            "LOOP_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE",
            "WALL_CLOCK_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE",
        ],
        "human_handoff_conditions": [
            "COUNTERFACTUAL_CONFLICT_UNRESOLVED_AFTER_BUDGET",
            "FORBIDDEN_PATTERN_FOUND",
            "SINGLE_SOURCE_DOMINANCE",
        ],
    }
    plan_horizon = [
        {"phase": "PLANNING", "status": "COMPLETE", "depends_on": [], "lazy": False},
        {"phase": "WIDE_DISCOVERY", "status": "PENDING", "depends_on": ["PLANNING"], "lazy": False},
        {"phase": "ENTITY_FREEZE", "status": "PENDING", "depends_on": ["WIDE_DISCOVERY"], "lazy": False},
        {"phase": "DEEP_CELL_COMPLETION", "status": "PENDING", "depends_on": ["ENTITY_FREEZE"], "lazy": False},
        {"phase": "COUNTERFACTUAL_RECOVERY", "status": "CONDITIONAL", "depends_on": ["DEEP_CELL_COMPLETION"], "lazy": True},
        {"phase": "GLOBAL_VERIFY", "status": "PENDING", "depends_on": ["DEEP_CELL_COMPLETION"], "lazy": False},
        {"phase": "REPORT_AUDIT", "status": "PENDING", "depends_on": ["GLOBAL_VERIFY"], "lazy": False},
        {"phase": "DELIVERY", "status": "PENDING", "depends_on": ["REPORT_AUDIT"], "lazy": False},
    ]

    return ResearchPlan(
        normalized_question=question,
        query_set=query_set,
        report_sections=report_sections,
        schema=schema,
        state_columns=state_columns,
        stop_contract=stop_contract,
        notes=notes,
        research_type=research_type,
        target_entity_type=target_entity_type,
        plan_horizon=plan_horizon,
        plan_revision=0,
        replan_history=[],
    )


def _build_requirement_targeted_queries(
    *,
    question: str,
    required_finding_contract: list[dict[str, object]],
) -> list[str]:
    queries: list[str] = []
    for requirement in required_finding_contract:
        requirement_type = str(requirement.get("requirement_type", "")).strip().upper()
        label = str(requirement.get("label", "")).strip()
        if not label:
            continue
        if requirement_type == "GOAL_FINDING":
            queries.append(f"{question} :: direct answer with evidence :: {label}")
            continue
        if requirement_type == "CONSTRAINT_FINDING":
            queries.append(f"{question} :: verified evidence search :: {label}")
            continue
        if requirement_type == "CONFLICT_FINDING":
            queries.append(f"{question} :: counterfactual evidence check :: {label}")
    return list(dict.fromkeys(query.strip() for query in queries if query.strip()))


def _build_report_sections(research_type: str, research_intent) -> list[str]:
    sections_by_type = {
        "PAPER_SURVEY": [
            "Research Question",
            "Research Intent",
            "Paper Map",
            "Methods And Benchmarks",
            "Evidence Ledger",
            "Conflicts And Limitations",
            "Next Actions",
        ],
        "GITHUB_REPO_ANALYSIS": [
            "Research Question",
            "Research Intent",
            "Repository Overview",
            "Architecture And Reusable Design",
            "Evidence Ledger",
            "Risks And Gaps",
            "Next Actions",
        ],
        "PRODUCT_COMPARISON": [
            "Research Question",
            "Research Intent",
            "Product Positioning",
            "Feature Comparison",
            "Evidence Ledger",
            "Conflicts And Uncertainty",
            "Next Actions",
        ],
        "TECH_SOLUTION_COMPARISON": [
            "Research Question",
            "Research Intent",
            "Solution Landscape",
            "Architecture Tradeoffs",
            "Evidence Ledger",
            "Risks And Costs",
            "Next Actions",
        ],
        "CONCEPT_RESEARCH": [
            "Research Question",
            "Research Intent",
            "Concept Definition",
            "Key Ideas And Examples",
            "Evidence Ledger",
            "Open Questions",
            "Next Actions",
        ],
    }
    sections = list(sections_by_type.get(research_type, sections_by_type["PRODUCT_COMPARISON"]))
    if getattr(research_intent, "deliverable_format", ""):
        sections.insert(2, "Deliverable Requirements")
    return sections


# ---------------------------------------------------------------------------
# P0-1: research_type 自动识别 + Table-as-Search schema 模板
# ---------------------------------------------------------------------------


# 设计文档 §6.3 五类模板 + 列定义(借鉴 Table-as-Search 的 __schema__ 文档)
RESEARCH_TYPE_TEMPLATES: dict[str, dict[str, object]] = {
    "PAPER_SURVEY": {
        "entity_type": "academic paper",
        "columns": [
            ("paper_title", "text", True),
            ("problem", "text", True),
            ("method", "text", True),
            ("base_model", "text", False),
            ("benchmark", "list", False),
            ("result", "text", False),
            ("code", "text", False),
            ("limitation", "text", False),
        ],
    },
    "GITHUB_REPO_ANALYSIS": {
        "entity_type": "github repository",
        "columns": [
            ("project", "text", True),
            ("problem", "text", True),
            ("architecture", "text", True),
            ("core_feature", "text", True),
            ("tech_stack", "list", False),
            ("reusable_design", "text", True),
            ("limitation", "text", False),
        ],
    },
    "PRODUCT_COMPARISON": {
        "entity_type": "product",
        "columns": [
            ("product", "text", True),
            ("positioning", "text", True),
            ("core_feature", "text", True),
            ("search_mechanism", "text", False),
            ("pricing", "text", False),
            ("reusable_idea", "text", False),
            ("limitation", "text", False),
        ],
    },
    "TECH_SOLUTION_COMPARISON": {
        "entity_type": "tech solution",
        "columns": [
            ("solution", "text", True),
            ("scenario", "text", True),
            ("architecture", "text", True),
            ("pros", "text", False),
            ("cons", "text", False),
            ("risk", "text", False),
            ("implementation_cost", "text", False),
        ],
    },
    "CONCEPT_RESEARCH": {
        "entity_type": "concept",
        "columns": [
            ("concept", "text", True),
            ("definition", "text", True),
            ("key_idea", "text", True),
            ("related_work", "text", False),
            ("example", "text", False),
            ("limitation", "text", False),
        ],
    },
}


# 关键词识别 (借鉴 Table-as-Search Main Agent 的 research_type 自动分类)
RESEARCH_TYPE_KEYWORDS: dict[str, list[str]] = {
    "PAPER_SURVEY": [
        "paper", "arxiv", "survey", "literature", "学术论文", "论文综述",
        "research", "study", "experiment",
    ],
    "GITHUB_REPO_ANALYSIS": [
        "github", "git", "repo", "open source", "开源", "repository",
        "codebase", "code", "implementation",
    ],
    "PRODUCT_COMPARISON": [
        "notebooklm", "product", "比较", "对比", "competitor", "vs",
        "alternative", "评测", "review",
    ],
    "TECH_SOLUTION_COMPARISON": [
        "rag", "embedding", "vector db", "技术方案", "architecture",
        "技术对比", "技术选型", "framework", "technical solution",
        "tech solution", "solution architecture",
    ],
    "CONCEPT_RESEARCH": [
        "什么是", "what is", "concept", "概念", "原理", "theory",
        "definition", "intro",
    ],
}


def _detect_research_type(question: str, intent) -> str:
    """借鉴 Table-as-Search Main Agent Prompt 的自动分类。

    优先级:intent.research_type 显式 > 关键词匹配 > 兜底 PRODUCT_COMPARISON。
    """
    explicit = getattr(intent, "research_type", None)
    if isinstance(explicit, str) and explicit.strip() and explicit.strip().upper() != "AUTO":
        normalized = explicit.strip().upper()
        if normalized in RESEARCH_TYPE_TEMPLATES:
            return normalized
        # 兼容单复数 / 别名
        alias = {
            "PAPER": "PAPER_SURVEY",
            "PAPERS": "PAPER_SURVEY",
            "GITHUB": "GITHUB_REPO_ANALYSIS",
            "REPO": "GITHUB_REPO_ANALYSIS",
            "PRODUCT": "PRODUCT_COMPARISON",
            "PRODUCTS": "PRODUCT_COMPARISON",
            "TECH": "TECH_SOLUTION_COMPARISON",
            "TECHNOLOGY": "TECH_SOLUTION_COMPARISON",
            "CONCEPT": "CONCEPT_RESEARCH",
        }.get(normalized)
        if alias:
            return alias

    text = (question or "").lower()
    deliverable = (getattr(intent, "deliverable_format", "") or "").lower()
    blob = f"{text} {deliverable}"
    scores: dict[str, int] = {}
    for rtype, keywords in RESEARCH_TYPE_KEYWORDS.items():
        for kw in keywords:
            if kw.lower() in blob:
                scores[rtype] = scores.get(rtype, 0) + 1
    if scores:
        return max(scores.items(), key=lambda item: item[1])[0]
    # 兜底:交付物包含"对比/比较/调研" -> PRODUCT_COMPARISON
    return "PRODUCT_COMPARISON"


def _infer_target_entity_type(research_type: str) -> str:
    template = RESEARCH_TYPE_TEMPLATES.get(research_type, {})
    return str(template.get("entity_type", "candidate"))


def _build_research_schema(
    research_type: str,
    depth: str,
    intent,
) -> ResearchSchema:
    """根据 research_type 与 depth 构建 ResearchSchema。

    设计原则(借鉴 Table-as-Search __schema__ 文档):
    - QUICK: 3 列;STANDARD: 5–7 列;DEEP: 8–10 列
    - 列按 (key, label, dtype, required, ...) 顺序展开
    """
    template = RESEARCH_TYPE_TEMPLATES.get(research_type, RESEARCH_TYPE_TEMPLATES["PRODUCT_COMPARISON"])
    raw_columns = template.get("columns", [])  # list[tuple[key, dtype, required]]

    # QUICK 仅保留 required=true 的列
    if depth == "QUICK":
        raw_columns = [c for c in raw_columns if c[2]]
    # DEEP 在 required 之外再补一些派生列(用于更细粒度分析)
    if depth == "DEEP" and research_type == "PAPER_SURVEY":
        raw_columns = list(raw_columns) + [
            ("venue", "text", False),
            ("year", "number", False),
        ]
    elif depth == "DEEP" and research_type == "GITHUB_REPO_ANALYSIS":
        raw_columns = list(raw_columns) + [
            ("license", "text", False),
            ("last_commit", "text", False),
        ]
    elif depth == "DEEP" and research_type == "PRODUCT_COMPARISON":
        raw_columns = list(raw_columns) + [
            ("vendor", "text", False),
            ("open_source", "boolean", False),
        ]

    columns: list[ResearchColumn] = []
    for col_def in raw_columns:
        key, dtype_value, required = col_def[0], col_def[1], col_def[2]
        try:
            dtype = ResearchColumnDtype(dtype_value)
        except ValueError:
            dtype = ResearchColumnDtype.TEXT
        columns.append(
            ResearchColumn(
                key=key,
                label=key.replace("_", " ").title(),
                dtype=dtype,
                required=bool(required),
                description=f"{research_type} schema column: {key}",
            )
        )

    return ResearchSchema(
        columns=columns,
        research_type=research_type,
        entity_type=str(template.get("entity_type", "candidate")),
        required_column_count=sum(1 for col in columns if col.required),
    )
