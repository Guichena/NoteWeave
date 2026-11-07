import json

from app.extractor import extract_evidence_cards_detailed
from app.models import ResearchColumn, ResearchPlan, ResearchReadWindow, ResearchSchema


def _window(key, version, text):
    return ResearchReadWindow(window_id=key, hit_id=key, source_id=key, source_title='Official docs',
        query='GIL default', read_focus='default', window_text=text, retention_reason='search', token_estimate=50,
        url=f'https://docs.python.org/{version}/howto/free-threading-python.html', adapter='deepseek',
        snapshot_status='FETCHED')


class RecordingLlm:
    def __init__(self):
        self.payloads = []

    def complete_json(self, purpose, payload):
        self.payloads.append(payload)
        return json.dumps({'evidence_cards': []})


def _extract(entity, windows):
    plan = ResearchPlan(normalized_question='核实默认 GIL 状态和可选构建',
        research_schema=ResearchSchema(columns=[ResearchColumn(key='default', label='默认 GIL 状态')]),
        stop_contract={'deep_cell_entity_label': entity, 'evidence_horizon_window_budget': 7})
    llm = RecordingLlm()
    result = extract_evidence_cards_detailed(None, plan, windows, llm_client=llm)
    return llm.payloads, result


def test_wrong_release_pages_do_not_drown_the_matching_release_during_extraction():
    windows = [_window('match', '3.13', 'The default GIL-enabled build is the standard build.')]
    windows += [_window(f'other-{i}', version, 'The free-threaded build disables the GIL.')
                for i, version in enumerate(['3', '3.14', '3.15', 'zh-cn/3', 'de/3', 'pt-br/3'])]
    payloads, _ = _extract('Python 3.13', windows)
    assert [w['window_id'] for w in payloads[0]['windows']] == ['match']


def test_later_introduction_boundary_is_still_readable_for_an_earlier_release():
    windows = [_window('match', '3.12', 'The standard build uses the GIL.'),
        _window('intro', '3.13', 'Starting with the 3.13 release, CPython supports a free-threaded build.'),
        _window('unrelated', '3.14', 'The build supports thread-local state.')]
    payloads, _ = _extract('Python 3.12', windows)
    assert [w['window_id'] for w in payloads[0]['windows']] == ['match', 'intro']


def test_only_wrong_release_windows_do_not_trigger_an_empty_model_call():
    payloads, result = _extract('Python 3.12', [_window('wrong', '3.14', 'The build supports thread-local state.')])
    assert payloads == []
    assert result.accepted_count == 0


def test_unversioned_research_keeps_its_existing_window_selection():
    payloads, _ = _extract('Threading', [_window('a', '3.12', 'One fact.'), _window('b', '3.13', 'Another fact.')])
    assert len(payloads[0]['windows']) == 2


def test_an_unversioned_independent_source_is_not_removed_by_alias_deduplication():
    matching = _window('match', '3.13', 'The standard build uses the GIL.')
    independent = _window('other', 'current', 'Python 3.13 retains the GIL.')
    independent.url = 'https://example.org/decisions/gil'
    payloads, _ = _extract('Python 3.13', [matching, independent])
    assert [w['window_id'] for w in payloads[0]['windows']] == ['match', 'other']


def test_calendar_paths_are_not_version_aliases():
    first = _window('first', 'current', 'Python 3.13 keeps a standard build.')
    second = _window('second', 'current', 'Python 3.13 has an optional build.')
    first.url = 'https://example.org/2024/05/release'
    second.url = 'https://example.org/2025/05/release'
    payloads, _ = _extract('Python 3.13', [first, second])
    assert [w['window_id'] for w in payloads[0]['windows']] == ['first', 'second']


def test_window_scan_keeps_a_later_boundary_after_an_unrelated_older_boundary():
    window = _window('intro', '3.13', 'Added in version 3.1: classic locking. '
                     'Starting with the 3.13 release, CPython supports a free-threaded build.')
    payloads, _ = _extract('Python 3.12', [window])
    assert [w['window_id'] for w in payloads[0]['windows']] == ['intro']


def test_query_parameters_identify_independent_documents():
    first = _window('default', '3.13', 'The standard build uses the GIL.')
    second = _window('optional', '3.13', 'An optional build disables the GIL.')
    first.url = 'https://docs.example/docs/3.13/view?page=default'
    second.url = 'https://docs.example/docs/3.13/view?page=optional'
    payloads, _ = _extract('Python 3.13', [first, second])
    assert [w['window_id'] for w in payloads[0]['windows']] == ['default', 'optional']
