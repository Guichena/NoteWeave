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
    card = _card("w", quote)
    window = SimpleNamespace(window_id="w", url="https://docs.python.org/3.13/library/threading.html",
        source_domain="docs.python.org", adapter="serper", source_quality="OFFICIAL_DOC")
    llm = _Llm({"answering_indices": [0], "default_configuration_indices": [0],
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
])
def test_localized_explicit_introduction_supports_only_negative_earlier_findings(quote):
    from app.version_scope import supports_entity_version
    window = SimpleNamespace(url="https://docs.python.org/zh-cn/3.13/howto/free-threading-python.html")
    assert supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="尚未提供该可选构建"), window)
    assert not supports_entity_version({"3.12"}, SimpleNamespace(quote_text=quote, claim_text="提供可选构建"), window)
