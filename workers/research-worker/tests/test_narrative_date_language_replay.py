"""资料研究 5dc0a22c（Star River）回放：原文是英文日期，中文成文不能退回英文原句，对比表要覆盖全部已核验项。"""

from app.narrative_report import _normalize_narrative, _render_markdown, _typed_tokens

QUESTION = "根据工作台已有资料，核实 Star River 项目的预算分配、试点学校与教师规模、上线日期及第二阶段计划，逐项附引用并标注资料未说明的信息。"
POINTS = [
    "资料中 Star River 项目的预算总额及各分项（研究、数据、培训）的金额与币种是多少？",
    "资料中 Star River 项目的试点学校数量与参与教师人数是多少？",
    "资料中 Star River 项目的上线日期是哪一天？",
    "资料中 Star River 项目第二阶段覆盖的教师规模与时间安排是什么？",
]
ENTITY = "Star River 项目"


def _cell(key: str, column: str, value: str, evidence: str, quote: str) -> dict[str, object]:
    return {"cell_key": key, "candidate_value": value, "evidence_keys": [evidence], "guarded": False,
            "entity_label": ENTITY, "column_label": column, "supporting_quotes": [quote],
            "evidence_quotes": [{"evidence_key": evidence, "quote": quote}]}


CELLS = [
    _cell("c-budget", "预算分配", "Star River 项目预算总额为 480000 TWD", "evidence:budget", "Budget: 480000 TWD."),
    _cell("c-phase2", "第二阶段计划", "第二阶段：2027 年 2 月覆盖 600 名教师", "evidence:phase2",
          "Phase two: 600 teachers in February 2027."),
    _cell("c-launch", "上线日期", "Star River project launches on December 15, 2026.", "evidence:launch",
          "Star River project launches on December 15, 2026."),
    _cell("c-pilot", "试点学校与教师规模", "Star River 项目试点规模为 3 所学校、180 名教师", "evidence:pilot",
          "Pilot: 180 teachers in 3 schools."),
]


def _payload(table_rows: list[dict[str, object]], **extra: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "title": "Star River 项目核心数据核实",
        "executive_summary": "Star River 项目预算总额为 480000 TWD [evidence:budget]。",
        "sections": [{"heading": "直接回答", "paragraphs": [
            {"text": "试点阶段的规模为 3 所学校、180 名教师 [evidence:pilot]。",
             "cell_keys": ["c-pilot"], "evidence_keys": ["evidence:pilot"]},
            {"text": "Star River 项目于 2026 年 12 月 15 日上线 [evidence:launch]。",
             "cell_keys": ["c-launch"], "evidence_keys": ["evidence:launch"]},
            {"text": "第二阶段计划在 2027 年 2 月覆盖 600 名教师 [evidence:phase2]。",
             "cell_keys": ["c-phase2"], "evidence_keys": ["evidence:phase2"]},
        ]}],
        "comparison_table": {"columns": ["维度", ENTITY], "rows": table_rows},
    }
    payload.update(extra)
    return payload


def test_chinese_dates_supported_by_english_quotes_stay_in_the_report() -> None:
    narrative = _normalize_narrative(_payload([
        {"cells": ["上线日期", "2026 年 12 月 15 日 [evidence:launch]"], "cell_keys": ["c-launch"],
         "evidence_keys": ["evidence:launch"]},
    ]), CELLS, [], QUESTION, False, [], POINTS)
    markdown = _render_markdown(narrative)

    assert "于 2026 年 12 月 15 日上线" in markdown
    assert "2027 年 2 月覆盖 600 名教师" in markdown
    assert "launches on" not in markdown.split("## 对比表")[0]
    assert "Phase two" not in markdown


def test_table_gets_every_verified_aspect_and_inventing_a_day_is_still_rejected() -> None:
    narrative = _normalize_narrative(_payload([
        {"cells": ["上线日期", "2026 年 12 月 16 日 [evidence:launch]"], "cell_keys": ["c-launch"],
         "evidence_keys": ["evidence:launch"]},
        {"cells": ["试点规模", "3 所学校、180 名教师 [evidence:pilot]"], "cell_keys": ["c-pilot"],
         "evidence_keys": ["evidence:pilot"]},
    ]), CELLS, [], QUESTION, False, [], POINTS)
    table = narrative["comparison_table"]
    rows = {row["cells"][0]: row for row in table["rows"]}  # type: ignore[index]

    # 写错日期的行被去掉，再按已核验结论补回；四项都在表里
    covered = {key for row in table["rows"] for key in row["cell_keys"]}  # type: ignore[index]
    assert covered == {"c-budget", "c-phase2", "c-launch", "c-pilot"}
    assert "12 月 16 日" not in str(table)
    assert "第二阶段计划" in rows


def test_unstated_details_go_to_limitations_without_new_facts() -> None:
    narrative = _normalize_narrative(_payload([], unstated_details=[
        {"point": POINTS[0], "missing": "研究、数据、培训各分项的币种"},
        {"point": POINTS[3], "missing": "第二阶段覆盖 5 所学校"},
        {"point": "资料外的问题", "missing": "项目负责人"},
    ]), CELLS, [], QUESTION, False, [], POINTS)

    assert narrative["limitations"] == ["资料未说明：研究、数据、培训各分项的币种"]


def test_date_tokens_keep_precision() -> None:
    assert _typed_tokens("2027 年 2 月") <= _typed_tokens("February 2027")
    assert not _typed_tokens("2027 年 2 月 3 日") <= _typed_tokens("February 2027")
    assert not _typed_tokens("2 所学校") <= _typed_tokens("February 2027")
    assert not _typed_tokens("15 名教师") <= _typed_tokens("Released 2024-03-01")
