from types import SimpleNamespace
import pytest

from app.deep_cell_executor import _answering_column_cards
from tests.test_cell_levels import _card
from tests.test_official_source_priority import _Llm


def _select(window, *, question="比较 Python 3.12 和 3.13 的可选构建", claim="CPython can be built with --disable-gil"):
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.12",
        "authoritative_domains": ["python.org"], "research_brief": {"current_question": question}})
    card = _card("w", claim)
    llm = _Llm({"answering_indices": [0], "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    return _answering_column_cards(snapshot, SimpleNamespace(key="build", label="可选构建"),
                                  [card], [window], llm, [])


def test_pep_release_metadata_cannot_be_laundered_into_another_version():
    window = SimpleNamespace(window_id="w", url="https://peps.python.org/pep-0703/",
        source_title="PEP 703", source_domain="peps.python.org", adapter="serper", source_quality="OFFICIAL_DOC",
        window_text="Python-Version: 3.13\nCPython can be built with --disable-gil")
    assert _select(window) == []


def test_wrong_version_is_rejected_even_without_an_official_sources_requirement():
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_title="Python free threading", source_domain="docs.python.org", adapter="serper",
        source_quality="OFFICIAL_DOC", window_text="CPython can be built with --disable-gil")
    assert _select(window) == []


def test_unversioned_generic_claim_does_not_prove_a_specific_release():
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3/howto/free-threading-python.html",
        source_title="Python free threading", source_domain="docs.python.org", adapter="serper",
        source_quality="OFFICIAL_DOC", window_text="CPython can be built with --disable-gil")
    assert _select(window) == []


@pytest.mark.parametrize("metadata", ["Python-Version: 3.12", "- Python-Version:\n- 3.12", "| Python-Version | 3.12 |"])
def test_matching_release_metadata_preserves_valid_evidence(metadata):
    window = SimpleNamespace(window_id="w", url="https://peps.python.org/pep-9999/",
        source_title="Versioned proposal", source_domain="peps.python.org", adapter="serper",
        source_quality="OFFICIAL_DOC", window_text=metadata + "\nThe standard build enables the GIL.")
    assert len(_select(window, claim="The standard build enables the GIL")) == 1


def test_a_later_introduction_is_usable_only_for_a_negative_earlier_version_finding():
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://docs.python.org/3.13/howto/free-threading-python.html")
    quote = "Starting with the 3.13 release, CPython has experimental support for a free-threaded build."
    assert supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="尚未提供可选自由线程构建"), window)
    assert not supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="提供可选自由线程构建"), window)


def test_versionless_entities_are_not_forced_into_a_release_scope():
    from app.version_scope import supports_entity_version
    assert supports_entity_version(set(), _card("w", "A general fact"), None)


@pytest.mark.parametrize("claim", ["CPython supports running without the GIL", "This build does not require the GIL",
                                  "CPython offers a no-GIL build", "可在没有 GIL 的情况下运行"])
def test_positive_gil_free_capability_is_not_a_negative_availability_finding(claim):
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://docs.python.org/3.13/howto/free-threading-python.html")
    quote = "Starting with the 3.13 release, CPython supports running without the GIL."
    assert not supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text=claim), window)


@pytest.mark.parametrize("metadata", ["Version: 3.12", "Python-Version: 3.12"])
def test_body_metadata_cannot_override_an_explicit_other_release_url(metadata):
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_title="Python free threading", source_domain="docs.python.org", adapter="serper",
        source_quality="OFFICIAL_DOC", window_text=metadata + "\nCPython can be built with --disable-gil")
    assert _select(window) == []


@pytest.mark.parametrize("url,metadata,version", [
    ("https://www.envoyproxy.io/docs/envoy/v1.5.0/intro", "", "1.5.0"),
    ("https://openjdk.org/jeps/444", "Release: 21", "21"),
    ("https://peps.python.org/pep-0703/", "| Python-Version | 3.13 |", "3.13"),
])
def test_supported_metadata_shapes_accept_only_the_matching_release(url, metadata, version):
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url=url, window_text=metadata)
    card = _card("w", "A supported feature")
    assert supports_entity_version({version}, card, window)
    assert not supports_entity_version({"0.1"}, card, window)


def test_a_count_in_an_unversioned_quote_is_not_release_applicability():
    from app.version_scope import supports_entity_version
    assert not supports_entity_version({"12"}, _card("w", "Supports 12 threads"), None, "JDK 12")
    assert supports_entity_version({"12"}, _card("w", "JDK 12 supports the feature"), None, "JDK 12")


def test_an_optional_build_cannot_answer_the_default_state_column():
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", "CPython provides an experimental free-threaded build with the GIL disabled")
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == []


@pytest.mark.parametrize("quote", [
    "The GIL is enabled by default; an experimental build can disable it.",
    "The standard build enables the GIL; an experimental build can disable it.",
    "In CPython, only one thread can execute Python code at once because of the GIL.",
])
def test_default_state_evidence_is_preserved(quote):
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", quote).model_copy(update={"quote_text": quote})
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/library/threading.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
                "default_configuration_quotes": [{"claim_index": 0, "quote": quote.split(";")[0]}],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == [card]


def test_default_inside_an_optional_mode_requires_separate_scope_approval():
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", "The experimental free-threaded build disables the GIL by default.")
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == []


@pytest.mark.parametrize("quote", [
    "从 3.13 版本开始，CPython 提供实验性的自由线程构建。",
    "自 3.13 起，CPython 提供实验性的自由线程构建。",
    "从 Python 3.13 版本开始，提供实验性的自由线程构建。",
    "Starting with Python 3.13, CPython supports a free-threaded build.",
])
def test_localized_explicit_introduction_supports_only_negative_earlier_findings(quote):
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://docs.python.org/zh-cn/3.13/howto/free-threading-python.html")
    assert supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="尚未提供该可选构建"), window, "Python 3.12")
    assert not supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="提供可选构建"), window, "Python 3.12")


@pytest.mark.parametrize("proof", [
    "The free-threaded build supports optionally enabling the GIL at runtime.",
    "The standard build enables the GIL.",
    "enabling the GIL at runtime",
])
def test_default_scope_approval_needs_an_exact_standard_configuration_quote(proof):
    quote = "The free-threaded build supports optionally enabling the GIL at runtime."
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", quote)
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
                "default_configuration_quotes": [{"claim_index": 0, "quote": proof}],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == []


def test_default_proof_cannot_remove_the_optional_mode_subject():
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", "The experimental free-threaded build disables the GIL by default.")
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
                "default_configuration_quotes": [{"claim_index": 0, "quote": "disables the GIL by default"}],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == []


def test_historical_proposal_metadata_does_not_prove_released_behaviour():
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://raw.githubusercontent.com/python/peps/oldcommit/pep-0703.rst",
                             window_text="Python-Version: 3.12\nStatus: Draft")
    card = SimpleNamespace(quote_text="The global interpreter lock will remain the default for CPython builds.",
                           claim_text="GIL 是默认配置")
    assert not supports_entity_version({"3.12"}, card, window, "Python 3.12")


@pytest.mark.parametrize("proof", ["the default GIL-enabled build",
    "The free-threaded build has additional overhead when executing Python code compared to the default GIL-enabled build."])
def test_default_build_fact_in_a_comparison_is_not_discarded(proof):
    quote = ("The free-threaded build has additional overhead when executing Python code compared to the "
             "default GIL-enabled build. In 3.13, this overhead is about 40% on the pyperformance suite.")
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", "默认构建启用 GIL").model_copy(update={"quote_text": quote})
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
                "default_configuration_quotes": [{"claim_index": 0, "quote": proof}],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == [card]


def test_future_grammar_in_published_runtime_documentation_is_not_a_proposal():
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://docs.python.org/3.12/c-api/init.html")
    card = SimpleNamespace(quote_text="The GIL will be released while the blocking operation runs.",
                           claim_text="执行阻塞操作时释放 GIL")
    assert supports_entity_version({"3.12"}, card, window, "Python 3.12")


def test_optional_distribution_default_interpreter_is_not_the_standard_configuration():
    snapshot = SimpleNamespace(query_policy={"entity_label": "Python 3.13", "authoritative_domains": ["python.org"]})
    card = _card("w", "默认解释器禁用 GIL").model_copy(update={
        "quote_text": "In the optional free-threaded distribution, the default interpreter runs with the GIL disabled."})
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/howto/free-threading-python.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
                "default_configuration_quotes": [{"claim_index": 0, "quote": "the default interpreter"}],
                "coverage": [{"requirement_index": 0, "claim_indices": [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key="default", label="默认 GIL 状态"),
                                   [card], [window], llm, []) == []


@pytest.mark.parametrize("metadata", ["Status: Draft", "| Status | Draft |", "- Status:\n- Draft"])
def test_draft_metadata_does_not_prove_implementation_on_a_versioned_url(metadata):
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://example.org/docs/1.0/feature", window_text=metadata)
    card = SimpleNamespace(quote_text="Version 1.0 will support this feature.", claim_text="Version 1.0 supports the feature")
    assert not supports_entity_version({"1.0"}, card, window, "Product 1.0")


def test_similar_to_comparison_preserves_the_default_build_fact():
    quote = ('The free-threaded build of CPython aims to provide similar thread-safety behavior '
             'at the Python level to the default GIL-enabled build.')
    snapshot = SimpleNamespace(query_policy={'entity_label': 'Python 3.13', 'authoritative_domains': ['python.org']})
    card = _card('w', '默认构建启用 GIL').model_copy(update={'quote_text': quote})
    window = SimpleNamespace(window_id='w', url='https://docs.python.org/3.13/howto/free-threading-python.html',
        source_domain='docs.python.org', adapter='serper', source_quality='OFFICIAL_DOC')
    llm = _Llm({'answering_indices': [0], 'default_configuration_indices': [0],
                'default_configuration_quotes': [{'claim_index': 0, 'quote': 'the default GIL-enabled build'}],
                'coverage': [{'requirement_index': 0, 'claim_indices': [0]}]})
    assert _answering_column_cards(snapshot, SimpleNamespace(key='default', label='默认 GIL 状态'),
                                   [card], [window], llm, []) == [card]


def test_a_proposal_is_not_a_released_capability_even_without_future_tense():
    from app.version_scope import supports_entity_version
    quote = ('This PEP proposes adding a build configuration (``--without-gil``) to CPython '
             'to let it run Python code without the global interpreter lock')
    source = SimpleNamespace(url='https://raw.githubusercontent.com/python/peps/oldcommit/pep-0703.rst',
                             window_text='Python-Version: 3.12\nStatus: Draft')
    assert not supports_entity_version({'3.12'}, SimpleNamespace(quote_text=quote, claim_text=quote), source, 'Python 3.12')


def test_draft_introduction_cannot_prove_a_historical_negative_but_proposal_topics_are_preserved():
    from app.version_scope import supports_entity_version
    quote = 'Starting with the 3.13 release, CPython supports a free-threaded build.'
    source = SimpleNamespace(url='https://peps.python.org/pep-0703/', window_text='Python-Version: 3.13\nStatus: Draft')
    card = SimpleNamespace(quote_text=quote, claim_text='该版本不提供该可选构建')
    assert not supports_entity_version({'3.12'}, card, source, 'Python 3.12')
    assert supports_entity_version(set(), card, source, 'PEP 703')


@pytest.mark.parametrize('claim', [
    'Python 3.12 提供 free threading。',
    'Python 3.12 不提供 64 线程构建。',
    'Python 3.11 不提供该可选构建。',
    'Python 3.12 does not support this build; its memory limit is 3.12 GB.',
])
def test_negative_context_normalization_does_not_erase_unquoted_facts(claim):
    from app.extractor import _grounded_claim
    quote = 'Starting with the 3.13 release, CPython supports a free-threaded build.'
    assert _grounded_claim(claim, quote, entity_label='Python 3.12') == quote


def test_negative_context_normalization_requires_an_explicit_later_introduction():
    from app.extractor import _grounded_claim
    quote = 'CPython supports a free-threaded build.'
    assert _grounded_claim('Python 3.12 不提供该构建', quote, entity_label='Python 3.12') == quote
