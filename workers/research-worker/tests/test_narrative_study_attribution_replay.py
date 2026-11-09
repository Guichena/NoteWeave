"""开放式研究回放（scope-release-open-20261109）：相邻引用的不同研究不能写成同一研究，点名的作者必须出现在所引原文里。"""

from app.narrative_report import _checked_statements

ENTITY = "远程或混合办公"
GIBBS = ("evidence:gibbs", "(2022) examine graduate team employees in an Asian IT firm who shifted to working from home "
         "during the pandemic. They find large negative effects on productivity, although their result examines a "
         "shift from fully in-person to fully remote executed at speed at the onset of the pandemic without a control "
         "group, making these results hard to assess.")
INDICATORS = ("evidence:indicators", "Two indicators (performance reviews and promotions) show no impact, while two "
              "others (lines of code and self-assessed productivity impact) show a positive impact. While this result "
              "is somewhat inconclusive")
REVIEW = ("evidence:review", "Mandatory and full-time or external factors (i.e., the COVID-19 pandemic) are at play; the "
          "overall impacts are less positive and can harm productivity and performance Mutiganda et al. (2022) - 43 "
          "original articles published from 2000 until May 2021")
BLOOM = ("evidence:bloom", "Bloom et al. (2015) - Empirical study: work from home (WFH) (N = 249) or in the office "
         "(N = 249) for 9 months 13% performance increase")

CELL = {"cell_key": "c-opposing", "candidate_value": "多项研究显示负面或不确定的生产率效应", "guarded": False,
        "entity_label": ENTITY, "column_label": "反对或不确定证据",
        "evidence_keys": [GIBBS[0], INDICATORS[0], REVIEW[0], BLOOM[0]],
        "evidence_quotes": [{"evidence_key": key, "quote": quote} for key, quote in (GIBBS, INDICATORS, REVIEW, BLOOM)]}


def _results(text: str) -> list[bool]:
    return [ok for statement, ok in _checked_statements(text, [CELL], [], [CELL]) if statement.strip()]


def test_named_author_must_appear_in_the_cited_quote() -> None:
    # 报告原句：作者取自别处，引用的却是综述里另一条
    assert _results("Gibbs et al. (2022) 对亚洲IT企业团队员工的研究发现远程办公对生产率有大幅负面影响 [evidence:review]。") == [False]
    # 原文截掉了作者名时也不能点名
    assert _results("Gibbs 等（2022）发现生产率大幅负面效应 [evidence:gibbs]。") == [False]
    assert _results("一项针对亚洲 IT 企业的研究（2022）发现生产率大幅负面效应 [evidence:gibbs]。") == [True]
    assert _results("Bloom 等（2015）报告了 13% 的绩效提升 [evidence:bloom]。") == [True]


def test_same_study_must_share_a_citation_with_the_previous_sentence() -> None:
    text = ("一项针对亚洲 IT 企业的研究（2022）发现生产率大幅负面效应 [evidence:gibbs]。"
            "同一研究的两项指标显示无影响，另两项显示正面影响 [evidence:indicators]。")
    assert _results(text) == [True, False]
    rewritten = ("一项针对亚洲 IT 企业的研究（2022）发现生产率大幅负面效应 [evidence:gibbs]。"
                 "另一项研究的两项指标显示无影响，另两项显示正面影响 [evidence:indicators]。")
    assert _results(rewritten) == [True, True]


def test_et_al_does_not_split_the_sentence() -> None:
    statements = [statement for statement, _ in _checked_statements(
        "Bloom et al. (2015) 报告了 13% 的绩效提升 [evidence:bloom]。", [CELL], [], [CELL]) if statement.strip()]
    assert len(statements) == 1
