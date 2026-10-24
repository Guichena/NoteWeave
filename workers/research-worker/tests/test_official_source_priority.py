"""官方来源优先：规划给出官方域名时保证一条 site: 检索词，官方与高可信来源排在抓取范围之前，文档路径识别为官方文档。"""

from __future__ import annotations

import json
from types import SimpleNamespace

from app.cell_query_planner import CellColumn, CellQueryContext, best_matching_domain, plan_cell_queries
from app.deep_cell_executor import _prioritize_authoritative_hits
from app.source_profile import infer_source_quality


class _Llm:
    def __init__(self, response: object) -> None:
        self.response = response

    def complete_json(self, purpose: str, payload: dict[str, object]) -> str:
        del purpose, payload
        return json.dumps(self.response, ensure_ascii=False)


def _context(**overrides: object) -> CellQueryContext:
    values: dict[str, object] = {
        "question": "比较 PostgreSQL 17 与 MySQL 8.4 的 JSON 索引类型，引用官方文档。",
        "entity_label": "MySQL 8.4",
        "columns": (CellColumn("index_type", "JSON 索引类型"),),
        "authoritative_domains": ("postgresql.org", "dev.mysql.com"),
        "max_queries": 3,
    }
    values.update(overrides)
    return CellQueryContext(**values)  # type: ignore[arg-type]


def test_missing_site_query_goes_second_without_dropping_the_last_column_query() -> None:
    """额度已满时给第二条查询增加官方域名，保留最后一条具体查询。"""
    llm = _Llm({"queries": [
        {"query": "MySQL 8.4 JSON index", "intent": "direct", "column_keys": ["index_type"]},
        {"query": "MySQL multi-valued index JSON", "intent": "direct"},
        {"query": "MySQL generated column index JSON", "intent": "direct"},
    ]})

    plan = plan_cell_queries(_context(), llm)

    assert [spec.query for spec in plan.specs] == [
        "MySQL 8.4 JSON index", "MySQL multi-valued index JSON site:dev.mysql.com", "MySQL generated column index JSON"]
    assert plan.specs[1].family == "verified_evidence"


def test_site_query_is_inserted_second_when_the_plan_has_room() -> None:
    llm = _Llm({"queries": [
        {"query": "MySQL 8.4 JSON index", "intent": "direct", "column_keys": ["index_type"]},
        {"query": "MySQL multi-valued index JSON", "intent": "direct"},
    ]})

    plan = plan_cell_queries(_context(), llm)

    assert [spec.query for spec in plan.specs] == [
        "MySQL 8.4 JSON index", "MySQL 8.4 JSON 索引类型 site:dev.mysql.com", "MySQL multi-valued index JSON"]


def test_model_site_query_is_kept_as_is() -> None:
    llm = _Llm({"queries": [
        {"query": "MySQL 8.4 JSON index", "intent": "direct", "column_keys": ["index_type"]},
        {"query": "multi-valued index site:dev.mysql.com", "intent": "verify"},
    ]})

    plan = plan_cell_queries(_context(), llm)

    assert [spec.query for spec in plan.specs] == ["MySQL 8.4 JSON index", "multi-valued index site:dev.mysql.com"]


def test_rule_plan_also_targets_the_official_domain_and_no_domain_means_no_change() -> None:
    assert any("site:dev.mysql.com" in spec.query for spec in plan_cell_queries(_context(), None).specs)
    assert not any("site:" in spec.query
                   for spec in plan_cell_queries(_context(authoritative_domains=()), None).specs)


def test_best_matching_domain_follows_the_entity_name() -> None:
    domains = ["postgresql.org", "dev.mysql.com"]
    assert best_matching_domain(domains, "MySQL 8.4") == "dev.mysql.com"
    assert best_matching_domain(domains, "PostgreSQL 17") == "postgresql.org"
    assert best_matching_domain(domains, "数据库") == "postgresql.org"


def test_official_and_high_trust_hits_move_ahead_in_stable_order() -> None:
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["dev.mysql.com"]})
    hits = [
        SimpleNamespace(url="https://blog.example.com/a", source_domain="blog.example.com",
                        source_quality="SECONDARY_SOURCE", adapter="serper"),
        SimpleNamespace(url="https://www.rfc-editor.org/rfc/rfc9700", source_domain="rfc-editor.org",
                        source_quality="OFFICIAL_DOC", adapter="serper"),
        SimpleNamespace(url="https://medium.com/b", source_domain="medium.com",
                        source_quality="SECONDARY_SOURCE", adapter="serper"),
        SimpleNamespace(url="https://dev.mysql.com/doc/refman/8.4/en/json.html", source_domain="dev.mysql.com",
                        source_quality="GENERAL_WEB", adapter="serper"),
    ]

    ordered = _prioritize_authoritative_hits(snapshot, hits)

    assert [hit.source_domain for hit in ordered] == [
        "dev.mysql.com", "rfc-editor.org", "blog.example.com", "medium.com"]


def test_doc_paths_and_standards_sites_count_as_official_docs() -> None:
    def quality(url: str) -> str:
        return infer_source_quality(url=url, provider="serper", adapter="serper", title="")[0]

    assert quality("https://www.postgresql.org/docs/17/datatype-json.html") == "OFFICIAL_DOC"
    assert quality("https://dev.mysql.com/doc/refman/8.4/en/create-index.html") == "OFFICIAL_DOC"
    assert quality("https://openjdk.org/jeps/444") == "OFFICIAL_DOC"
    assert quality("https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1") == "OFFICIAL_DOC"
    assert quality("https://medium.com/@someone/docs/postgres-json") == "SECONDARY_SOURCE"
    assert quality("https://example.com/blog/postgres-json") != "OFFICIAL_DOC"


def test_doc_url_with_another_version_moves_behind_the_matching_version() -> None:
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["dev.mysql.com"], "entity_label": "MySQL 8.4"})
    hits = [
        SimpleNamespace(url="https://dev.mysql.com/doc/refman/9.3/en/json.html", source_domain="dev.mysql.com",
                        source_quality="OFFICIAL_DOC", adapter="serper"),
        SimpleNamespace(url="https://dev.mysql.com/doc/refman/8.4/en/json.html", source_domain="dev.mysql.com",
                        source_quality="OFFICIAL_DOC", adapter="serper"),
        SimpleNamespace(url="https://dev.mysql.com/doc/relnotes/", source_domain="dev.mysql.com",
                        source_quality="OFFICIAL_DOC", adapter="serper"),
    ]

    ordered = _prioritize_authoritative_hits(snapshot, hits)

    assert [hit.url.split("/doc/")[1] for hit in ordered] == [
        "refman/8.4/en/json.html", "relnotes/", "refman/9.3/en/json.html"]


def test_major_version_doc_path_is_compared_but_other_numbers_are_not() -> None:
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["postgresql.org", "openjdk.org"],
                                             "entity_label": "PostgreSQL 17"})

    def hit(url: str) -> SimpleNamespace:
        return SimpleNamespace(url=url, source_domain=url.split("/")[2], source_quality="OFFICIAL_DOC", adapter="serper")

    ordered = _prioritize_authoritative_hits(snapshot, [
        hit("https://www.postgresql.org/docs/16/gin.html"),
        hit("https://openjdk.org/jeps/444"),
        hit("https://www.postgresql.org/docs/17/gin.html"),
    ])

    assert [item.url for item in ordered] == [
        "https://openjdk.org/jeps/444", "https://www.postgresql.org/docs/17/gin.html",
        "https://www.postgresql.org/docs/16/gin.html"]


def test_unversioned_entity_prefers_current_docs_over_pages_pinned_to_an_old_release() -> None:
    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["envoyproxy.io"], "entity_label": "Envoy"})

    def hit(url: str, quality: str = "OFFICIAL_DOC") -> SimpleNamespace:
        return SimpleNamespace(url=url, source_domain=url.split("/")[2], source_quality=quality, adapter="serper")

    ordered = _prioritize_authoritative_hits(snapshot, [
        hit("https://www.envoyproxy.io/docs/envoy/v1.5.0/intro/arch_overview/load_balancing"),
        hit("https://blog.example.com/envoy-load-balancing", "SECONDARY_SOURCE"),
        hit("https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/overview"),
    ])

    # 旧版官方文档只排到当前文档之后，仍在二手来源之前
    assert [item.url.split("/")[2] + ("/latest" if "/latest/" in item.url else "") for item in ordered] == [
        "www.envoyproxy.io/latest", "www.envoyproxy.io", "blog.example.com"]


def test_site_query_uses_the_english_search_intent_instead_of_a_chinese_label() -> None:
    llm = _Llm({"queries": [{"query": "MySQL 8.4 JSON index", "intent": "direct"}]})
    column = CellColumn("operators", "可加速的查询运算符", search_intents=("JSON operators that use indexes",))

    plan = plan_cell_queries(_context(columns=(column,)), llm)

    assert plan.specs[1].query == "MySQL 8.4 JSON operators that use indexes site:dev.mysql.com"


def test_cell_keeps_only_primary_source_evidence_when_both_kinds_exist() -> None:
    from app.deep_cell_executor import _prefer_primary_cards

    snapshot = SimpleNamespace(query_policy={"authoritative_domains": ["dev.mysql.com"], "entity_label": "MySQL 8.4"})
    windows = [
        SimpleNamespace(window_id="w-blog", adapter="external_url", source_domain="jamsql.com",
                        source_quality="GENERAL_WEB", url="https://jamsql.com/blog/mysql-json"),
        SimpleNamespace(window_id="w-old", adapter="external_url", source_domain="dev.mysql.com",
                        source_quality="OFFICIAL_DOC", url="https://dev.mysql.com/doc/refman/9.3/en/create-index.html"),
        SimpleNamespace(window_id="w-doc", adapter="external_url", source_domain="dev.mysql.com",
                        source_quality="OFFICIAL_DOC", url="https://dev.mysql.com/doc/refman/8.4/en/json.html"),
    ]
    cards = [SimpleNamespace(window_id=window.window_id) for window in windows]

    assert [card.window_id for card in _prefer_primary_cards(snapshot, cards, windows)] == ["w-doc"]
    # 只有二手来源时照常保留，不因此丢掉单元格
    assert [card.window_id for card in _prefer_primary_cards(snapshot, cards[:1], windows)] == ["w-blog"]


def test_secondary_only_cell_stays_unverified_even_after_repair_when_official_sources_are_required() -> None:
    from app.deep_cell_executor import _prefer_primary_cards

    blog = SimpleNamespace(window_id="w-blog", adapter="external_url", source_domain="jamsql.com",
                           source_quality="GENERAL_WEB", url="https://jamsql.com/blog/mysql-json")
    cards = [SimpleNamespace(window_id="w-blog")]
    brief = {"current_question": "比较 PostgreSQL 17 与 MySQL 8.4 的 JSON 索引，引用官方文档。"}

    first_pass = SimpleNamespace(query_policy={"authoritative_domains": ["dev.mysql.com"], "research_brief": brief})
    assert _prefer_primary_cards(first_pass, cards, [blog]) == []

    repair = SimpleNamespace(query_policy={"authoritative_domains": ["dev.mysql.com"], "research_brief": brief,
                                           "repair_target": {"cell_key": "e:c"}})
    assert _prefer_primary_cards(repair, cards, [blog]) == []

    no_request = SimpleNamespace(query_policy={"research_brief": {"current_question": "微服务是否提高交付速度？"}})
    assert _prefer_primary_cards(no_request, cards, [blog]) == cards
