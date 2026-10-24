from app.planning_completeness import apply_requirement_checks

CONSTRAINT = {"policy": "PRIMARY_SOURCE", "question_quote": "引用 PEP 和官方文档", "source_types": ["SPECIFICATION"]}


def _plan():
    return {
        "required_points": ["专家要点"],
        "user_requirements": [
            {"text": "首次提供的版本", "conditions": ["首个提供该构建的版本", "安装方式"], "source_constraints": []},
            {"text": "当前支持状态", "conditions": ["当前状态", "生效版本"], "source_constraints": []},
            {"text": "引用 PEP 和官方文档", "conditions": ["给出 PEP 与官方文档出处"], "source_constraints": [CONSTRAINT]},
        ],
    }


def _completeness(checks, clauses=None):
    return {
        "schema_version": "research-planning-completeness.v2", "complete": False, "model_calls": 1,
        "clauses": clauses or [
            {"clause_id": "clause:0", "status": "MAPPED", "requirement_indexes": [0]},
            {"clause_id": "clause:1", "status": "MAPPED", "requirement_indexes": [1]},
            {"clause_id": "clause:2", "status": "MAPPED", "requirement_indexes": [2]},
        ],
        "requirement_checks": checks,
    }


def test_expanded_conditions_become_points_and_provenance_merges_into_content_items():
    checks = [
        {"requirement_index": 0, "status": "EXPANDED", "invalid_condition_indexes": [1]},
        {"requirement_index": 1, "status": "VALID", "invalid_condition_indexes": []},
        {"requirement_index": 2, "status": "PROVENANCE_ONLY", "invalid_condition_indexes": []},
    ]
    plan, completeness = apply_requirement_checks(_plan(), _completeness(checks))
    assert [item["text"] for item in plan["user_requirements"]] == ["首次提供的版本", "当前支持状态"]
    assert plan["user_requirements"][0]["conditions"] == ["首个提供该构建的版本"]
    assert all(item["source_constraints"] == [CONSTRAINT] for item in plan["user_requirements"])
    assert plan["required_points"] == ["专家要点", "安装方式"]
    assert completeness["complete"] is True
    assert [clause["requirement_indexes"] for clause in completeness["clauses"]] == [[0], [1], [0, 1]]
    assert [check["status"] for check in completeness["requirement_checks"]] == ["VALID", "VALID"]


def test_missing_clause_or_unchecked_requirement_keeps_the_plan_unchanged():
    valid = [{"requirement_index": index, "status": "VALID", "invalid_condition_indexes": []} for index in range(3)]
    missing = _completeness(valid, [
        {"clause_id": "clause:0", "status": "MAPPED", "requirement_indexes": [0]},
        {"clause_id": "clause:1", "status": "MISSING", "requirement_indexes": []},
        {"clause_id": "clause:2", "status": "MAPPED", "requirement_indexes": [1, 2]},
    ])
    plan = _plan()
    assert apply_requirement_checks(plan, missing) == (plan, missing)
    unchecked = _completeness([*valid[:2], {"requirement_index": 2, "status": "NOT_CHECKED", "invalid_condition_indexes": []}])
    assert apply_requirement_checks(plan, unchecked) == (plan, unchecked)


def test_requirement_made_entirely_of_expansion_is_not_silently_dropped():
    checks = [
        {"requirement_index": 0, "status": "EXPANDED", "invalid_condition_indexes": [0, 1]},
        {"requirement_index": 1, "status": "VALID", "invalid_condition_indexes": []},
        {"requirement_index": 2, "status": "VALID", "invalid_condition_indexes": []},
    ]
    plan = _plan()
    completeness = _completeness(checks)
    assert apply_requirement_checks(plan, completeness) == (plan, completeness)


def test_clause_losing_a_content_item_is_not_remapped():
    checks = [
        {"requirement_index": 0, "status": "PROVENANCE_ONLY", "invalid_condition_indexes": []},
        {"requirement_index": 1, "status": "VALID", "invalid_condition_indexes": []},
        {"requirement_index": 2, "status": "PROVENANCE_ONLY", "invalid_condition_indexes": []},
    ]
    plan = _plan()
    completeness = _completeness(checks)
    revised, result = apply_requirement_checks(plan, completeness)
    # 分句 0 只关联到被删除的纯来源项，按来源约束处理并关联到剩余内容项
    assert [item["text"] for item in revised["user_requirements"]] == ["当前支持状态"]
    assert result["complete"] is True
    assert [clause["requirement_indexes"] for clause in result["clauses"]] == [[0], [0], [0]]
