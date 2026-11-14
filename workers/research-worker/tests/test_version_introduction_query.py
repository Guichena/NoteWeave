"""版本研究回放（scope-release-web-20261109）：3.12 的查询全部钉住 3.12，读不到 3.13 起才提供的引入边界。"""

from app.cell_query_planner import CellColumn, CellQueryContext, _spec, ensure_introduction_query, plan_cell_queries
from app.repair_queries import plan_repair_queries

COLUMNS = (
    CellColumn(key="dim-ft", label="可选 free-threaded 构建",
               description="针对每个版本，是否提供可选的 free-threaded（no-GIL）构建，以及官方文档对该构建的称呼。",
               search_intents=("Python 3.12 free-threaded 构建是否可用",)),
    CellColumn(key="dim-gil", label="默认 GIL 状态",
               description="针对每个版本，官方文档说明标准 CPython 构建是否默认保持全局解释器锁（GIL）启用。",
               search_intents=("Python 3.12 GIL 默认为启用官方文档",)),
)


def _context(entity: str = "Python 3.12", max_queries: int = 3) -> CellQueryContext:
    return CellQueryContext(question="比较 Python 3.12 和 Python 3.13 的默认 GIL 状态，以及是否提供可选的 free-threaded 构建。",
                            entity_label=entity, columns=COLUMNS, max_queries=max_queries,
                            authoritative_domains=("docs.python.org", "peps.python.org"))


ORIGINAL = [
    _spec("Python 3.12 free-threaded GIL", "direct", ["dim-ft", "dim-gil"]),
    _spec("PEP 703 making the global interpreter lock optional in CPython", "direct", ["dim-ft"]),
    _spec("site:docs.python.org Python 3.12 C-API global interpreter lock always enabled", "verify", ["dim-gil"]),
]


def test_version_pinned_queries_get_an_unpinned_introduction_query_without_losing_columns() -> None:
    specs = ensure_introduction_query(list(ORIGINAL), _context())

    queries = [spec.query for spec in specs]
    assert len(specs) == 3
    assert "Python free-threaded GIL introduced in version" in queries
    assert any("site:docs.python.org" in query for query in queries)
    assert {key for spec in specs for key in spec.column_keys} == {"dim-ft", "dim-gil"}


def test_existing_introduction_query_or_unversioned_entity_is_left_alone() -> None:
    with_boundary = [*ORIGINAL[:2], _spec("Python free threading introduced in which release", "verify", ["dim-ft"])]
    assert ensure_introduction_query(list(with_boundary), _context()) == with_boundary
    assert ensure_introduction_query(list(ORIGINAL), _context(entity="CPython")) == ORIGINAL


class _FakeLlm:
    def __init__(self, response: str) -> None:
        self.response = response

    def complete_json(self, purpose: str, payload: object) -> str:
        return self.response


def test_planner_and_repair_both_add_the_boundary_query() -> None:
    planned = plan_cell_queries(_context(max_queries=4), _FakeLlm(
        '{"queries":[{"query":"Python 3.12 free-threaded GIL","intent":"direct","column_keys":["dim-ft","dim-gil"]},'
        '{"query":"Python 3.12 global interpreter lock default","intent":"direct","column_keys":["dim-gil"]}]}'))
    assert any(spec.query.endswith("introduced in version") and "3.12" not in spec.query for spec in planned.specs)

    repaired, strategy = plan_repair_queries(_context(), {"cell_key": "entity:dim-ft", "repair_cause": "MARGINAL_GAIN_EXHAUSTED",
                                                         "tried_queries": [s.query for s in ORIGINAL]},
                                             _FakeLlm('{"strategy":"NARROW","queries":["Python 3.12 per-interpreter GIL C-API"]}'))
    assert strategy == "NARROW"
    assert "Python free-threaded GIL introduced in version" in [spec.query for spec in repaired.specs]
