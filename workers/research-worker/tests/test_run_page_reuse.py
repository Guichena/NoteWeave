from types import SimpleNamespace
from uuid import uuid4

from app.fetch_adapters import UrlFetchAdapter, _select_query_relevant_text
from app.models import ResearchPlan, ResearchSearchHit


class Pages:
    def __init__(self, *, full_page=True, fetched=True):
        self.calls = 0
        self.full_page = full_page
        self.fetched = fetched
        self.text = ('Thread safety overview.\n\n' + 'Ordinary context. ' * 350
                     + '\n\nThe quasar parameter requires WAL summaries.\n\n' + 'More context. ' * 350)

    def read(self, url, query, token_budget):
        self.calls += 1
        return {'text': _select_query_relevant_text(self.text, query, token_budget),
                **({'_unfocused_text': self.text} if self.full_page else {}),
                'fetch_status': 'FETCHED' if self.fetched else 'FALLBACK_USED',
                'snapshot_status': 'HTTP_FETCHED', 'content_origin': 'FETCHED_SNAPSHOT',
                'fetch_method': 'HTTP', 'fetch_attempts': ['HTTP'], 'untrusted_content': True}


def context():
    task = SimpleNamespace(workspace_id='workspace', target_id=str(uuid4()), task_id='first')
    plan = ResearchPlan(normalized_question='Research', stop_contract={
        'reuse_fetched_pages': True, 'deep_cell_entity_id': 'product-1'})
    hit = ResearchSearchHit(hit_id='first', source_id='web-1', source_title='Manual',
        query='thread safety', rank=1, snippet='', confidence_score=1, retrieval_reason='search',
        url='https://docs.example/manual', adapter='external_url')
    return task, plan, hit


def test_repair_reuses_full_page_but_selects_text_again_for_its_new_question():
    task, plan, hit = context()
    transport = Pages()
    adapter = UrlFetchAdapter(transport, token_budget=100)
    first = adapter.fetch_hit(task, plan, hit, 1)
    task.task_id = 'repair'
    second_hit = hit.model_copy(update={'hit_id': 'second', 'query': 'quasar WAL summaries'})
    second = adapter.fetch_hit(task, plan, second_hit, 1)
    assert transport.calls == 1
    assert 'quasar parameter' not in first.snapshot_text
    assert 'quasar parameter' in second.snapshot_text
    assert second.hit_id == 'second' and second.query == 'quasar WAL summaries'
    assert second.fetch_method == 'RUN_CACHE_REUSE'
    assert second.snapshot_archive_ready


def test_pages_are_not_reused_across_workspaces_runs_or_entities():
    for field in ['workspace_id', 'target_id', 'entity']:
        task, plan, hit = context()
        transport = Pages()
        adapter = UrlFetchAdapter(transport)
        adapter.fetch_hit(task, plan, hit, 1)
        if field == 'entity':
            plan.stop_contract['deep_cell_entity_id'] = 'another'
        else:
            setattr(task, field, 'another')
        adapter.fetch_hit(task, plan, hit, 1)
        assert transport.calls == 2


def test_independent_checks_bypass_reuse_even_when_a_page_is_already_cached():
    task, plan, hit = context()
    transport = Pages()
    adapter = UrlFetchAdapter(transport)
    adapter.fetch_hit(task, plan, hit, 1)
    plan.stop_contract['reuse_fetched_pages'] = False
    adapter.fetch_hit(task, plan, hit, 1)
    assert transport.calls == 2


def test_failed_or_already_truncated_pages_are_not_reused_as_full_pages():
    for options in [{'fetched': False}, {'full_page': False}]:
        task, plan, hit = context()
        transport = Pages(**options)
        adapter = UrlFetchAdapter(transport)
        adapter.fetch_hit(task, plan, hit, 1)
        adapter.fetch_hit(task, plan, hit, 1)
        assert transport.calls == 2


def test_page_cache_expires_and_does_not_share_mutable_metadata():
    from app.run_page_cache import RunPageCache
    now = [0.0]
    cache = RunPageCache(ttl=10, max_entries=1, max_bytes=100, clock=lambda: now[0])
    snapshot = {'fetch_status': 'FETCHED', 'content_origin': 'FETCHED_SNAPSHOT',
                '_unfocused_text': 'Original page', 'transport_chain': ['HTTP']}
    cache.put('first', snapshot)
    returned = cache.get('first')
    returned['transport_chain'].append('changed')
    assert cache.get('first')['transport_chain'] == ['HTTP']
    cache.put('second', snapshot)
    assert cache.get('first') is None
    now[0] = 10.0
    assert cache.get('second') is None


def test_independent_evidence_tasks_never_opt_into_page_reuse():
    from app.deep_cell_executor import _build_task_scope
    from app.task_snapshot_contract import require_trusted_claim_snapshot
    from tests.test_ma4d_deep_cell_executor import _claim, _snapshot, _counterfactual_quorum_snapshot
    ordinary = require_trusted_claim_snapshot(_claim(_snapshot()))
    independent = require_trusted_claim_snapshot(_claim(_counterfactual_quorum_snapshot()))
    assert _build_task_scope(ordinary)[1].stop_contract['reuse_fetched_pages'] is True
    assert _build_task_scope(independent)[1].stop_contract['reuse_fetched_pages'] is False


def test_excluded_repair_sources_are_removed_before_fetching():
    from app.deep_cell_executor import DeepCellExecutor
    from app.extraction_result import ExtractionResult, ExtractionTerminationReason
    from tests.test_ma4d_deep_cell_executor import _claim, _command, _counterfactual_repair_snapshot, _Permit

    class Tools:
        fetched = []

        def search(self, *args, **kwargs):
            return [SimpleNamespace(source_id=source, url='https://docs.example/' + source,
                                    source_title='Manual', adapter='external_url', query='q', rank=1)
                    for source in ['source-1', 'source-3']]

        def fetch(self, task_input, plan, hits, **kwargs):
            self.fetched = [hit.source_id for hit in hits]
            return []

        def read(self, *args, **kwargs):
            return []

        def extract(self, *args, **kwargs):
            return ExtractionResult(termination_reason=ExtractionTerminationReason.NO_READ_WINDOWS)

    tools = Tools()
    DeepCellExecutor(_Permit(), 'worker-a', toolchain=tools, enable_llm=False)(
        _command(), _claim(_counterfactual_repair_snapshot()))
    assert tools.fetched == ['source-3']

