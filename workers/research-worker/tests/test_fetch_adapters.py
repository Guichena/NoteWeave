from __future__ import annotations

import gzip

import pytest

from app.fetch_adapters import (
    CompositeFetchAdapter,
    CompositeUrlSnapshotTransport,
    HttpUrlSnapshotTransport,
    UrlFetchAdapter,
    WorkspaceFetchAdapter,
    _decode_response,
    _decompress_response,
    _pinned_http_get,
    _read_response_bytes,
    run_research_fetch,
)
from app.models import ResearchSearchHit, ResearchTaskInput
from app.planner import build_research_plan
from app.source_profile import infer_source_quality


def test_decompression_should_distinguish_encoding_size_and_corruption_failures() -> None:
    with pytest.raises(ValueError, match="unsupported content encoding"):
        _decompress_response(b"data", "br", max_bytes=100)
    with pytest.raises(ValueError, match="invalid compressed response"):
        _decompress_response(b"not-gzip", "gzip", max_bytes=100)
    with pytest.raises(ValueError, match="decompressed size limit"):
        _decompress_response(gzip.compress(b"x" * 101), "gzip", max_bytes=100)


def test_pinned_http_get_should_connect_to_validated_ip_not_resolve_hostname_again(monkeypatch) -> None:
    connected: list[str] = []

    monkeypatch.setattr(
        "app.fetch_adapters.socket.getaddrinfo",
        lambda *args, **kwargs: [(2, 1, 6, "", ("93.184.216.34", 80))],
    )

    class Response:
        status = 200

        def getheaders(self):
            return [("Content-Type", "text/plain")]

        def read(self, size: int):
            if getattr(self, "done", False):
                return b""
            self.done = True
            return b"grounded"

    class Connection:
        def __init__(self, host: str, port: int, timeout: int):
            connected.append(host)

        def request(self, method: str, path: str, headers: dict[str, str]):
            assert headers["Host"] == "example.com"

        def getresponse(self):
            return Response()

        def close(self):
            return None

    monkeypatch.setattr("app.fetch_adapters.http.client.HTTPConnection", Connection)

    raw, headers, final_url = _pinned_http_get(
        "http://example.com/research",
        headers={"User-Agent": "test"},
        timeout_seconds=2,
        token_budget=10,
    )

    assert connected == ["93.184.216.34"]
    assert raw == b"grounded"
    assert headers["Content-Type"] == "text/plain"
    assert final_url == "http://example.com/research"


def test_pinned_http_get_should_strip_credentials_when_redirect_changes_port(monkeypatch) -> None:
    requests: list[dict[str, str]] = []
    responses = [
        (302, [("Location", "http://example.com:8080/next")], b""),
        (200, [("Content-Type", "text/plain")], b"grounded"),
    ]

    monkeypatch.setattr(
        "app.fetch_adapters.socket.getaddrinfo",
        lambda *args, **kwargs: [(2, 1, 6, "", ("93.184.216.34", 80))],
    )

    class Response:
        def __init__(self, status: int, headers: list[tuple[str, str]], body: bytes) -> None:
            self.status = status
            self.headers = headers
            self.body = body
            self.read_once = False

        def getheaders(self):
            return self.headers

        def read(self, size: int):
            if self.read_once:
                return b""
            self.read_once = True
            return self.body

    class Connection:
        def __init__(self, host: str, port: int, timeout: int) -> None:
            del host, port, timeout

        def request(self, method: str, path: str, headers: dict[str, str]) -> None:
            del method, path
            requests.append(dict(headers))

        def getresponse(self):
            status, headers, body = responses.pop(0)
            return Response(status, headers, body)

        def close(self) -> None:
            return None

    monkeypatch.setattr("app.fetch_adapters.http.client.HTTPConnection", Connection)

    raw, _, final_url = _pinned_http_get(
        "http://example.com/start",
        headers={"Authorization": "Bearer secret", "X-API-Key": "secret-key"},
        timeout_seconds=2,
        token_budget=10,
    )

    assert raw == b"grounded"
    assert final_url == "http://example.com:8080/next"
    assert requests[0]["Authorization"] == "Bearer secret"
    assert "Authorization" not in requests[1]
    assert "X-API-Key" not in requests[1]


def test_http_transport_should_not_retry_non_retryable_4xx(monkeypatch) -> None:
    attempts = 0
    monkeypatch.setattr("app.fetch_adapters.validate_public_http_url", lambda _url: None)

    def reject(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        raise ValueError("HTTP_STATUS_404")

    monkeypatch.setattr("app.fetch_adapters._pinned_http_get", reject)
    result = HttpUrlSnapshotTransport(max_retries=3).read(
        "https://example.com/missing", "query", 100
    )

    assert attempts == 1
    assert result["fetch_failure_code"] == "NON_RETRYABLE_HTTP_404"
    assert result["transport_attempt_count"] == 1


def test_http_transport_should_report_actual_attempt_count_after_retry(monkeypatch) -> None:
    attempts = 0
    monkeypatch.setattr("app.fetch_adapters.validate_public_http_url", lambda _url: None)
    monkeypatch.setattr("app.fetch_adapters.time.sleep", lambda _seconds: None)

    def flaky(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise TimeoutError("temporary")
        return b"grounded evidence", {"Content-Type": "text/plain"}, "https://example.com"

    monkeypatch.setattr("app.fetch_adapters._pinned_http_get", flaky)
    result = HttpUrlSnapshotTransport(max_retries=3).read(
        "https://example.com", "query", 100
    )

    assert result["fetch_status"] == "FETCHED"
    assert result["transport_attempt_count"] == 2


class FakeUrlSnapshotTransport:
    def __init__(self, content_by_url: dict[str, dict[str, object]]) -> None:
        self.content_by_url = content_by_url
        self.calls: list[tuple[str, str, int]] = []

    def read(self, url: str, query: str, token_budget: int) -> dict[str, object]:
        self.calls.append((url, query, token_budget))
        return self.content_by_url[url]


class FailingFetchAdapter:
    def can_fetch(self, task_input, hit) -> bool:
        del task_input
        return hit.hit_id == "hit-url"

    def fetch_hit(self, task_input, plan, hit, fetch_index):
        del task_input, plan, hit, fetch_index
        raise RuntimeError("api_key=adapter-secret")


class ChunkedResponse:
    def __init__(self, payload: bytes, chunk_size: int = 1024) -> None:
        self.payload = payload
        self.chunk_size = chunk_size
        self.offset = 0

    def read(self, size: int = -1) -> bytes:
        if self.offset >= len(self.payload):
            return b""
        limit = min(size if size >= 0 else self.chunk_size, self.chunk_size)
        chunk = self.payload[self.offset : self.offset + limit]
        self.offset += len(chunk)
        return chunk


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-fetch-adapter-1",
            "workspace_id": "ws-1",
            "target_id": "run-fetch-adapter-1",
            "source_scope": [
                {
                    "source_id": "src-workspace",
                    "title": "Workspace Evidence",
                    "summary": "Workspace summary about verifier loops.",
                    "sample_text": "Workspace full text says verifier loops need local and global checks.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "How should verifier loops read evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _workspace_hit() -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id="hit-workspace",
        source_id="src-workspace",
        source_title="Workspace Evidence",
        query="verifier loops",
        rank=1,
        snippet="Workspace snippet.",
        confidence_score=0.9,
        retrieval_reason="workspace match",
        search_angle="direct",
        adapter="workspace",
        provider="workspace",
    )


def _url_hit(hit_id: str = "hit-url", rank: int = 2) -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id=hit_id,
        source_id=f"web-{hit_id}",
        source_title="External Evidence",
        query="verifier loops external",
        rank=rank,
        snippet="External snippet about verifier loops.",
        confidence_score=0.81,
        retrieval_reason="external search match",
        search_angle="coverage_gap",
        url=f"https://example.com/{hit_id}",
        provider="fake-web",
        adapter="external",
    )


def test_workspace_fetch_adapter_should_open_workspace_document() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    document = WorkspaceFetchAdapter().fetch_hit(task_input, plan, _workspace_hit(), fetch_index=1)

    assert document is not None
    assert document.source_id == "src-workspace"
    assert document.query == "verifier loops"
    assert "local and global checks" in document.snapshot_text
    assert document.adapter == "workspace"
    assert document.snapshot_status == "WORKSPACE"
    assert document.fetch_status == "WORKSPACE_READY"
    assert document.fetch_method == "WORKSPACE"
    assert document.content_origin == "WORKSPACE_TEXT"
    assert document.content_type_label == "WORKSPACE_TEXT"
    assert document.fetch_failure_code == ""
    assert document.transport_chain == ["WORKSPACE"]
    assert document.transport_resolution == "WORKSPACE"
    assert document.transport_fallback_code == ""
    assert document.transport_attempt_count == 1
    assert document.snapshot_archive_ready is False
    assert document.source_quality == "WORKSPACE_SOURCE"


def test_url_fetch_adapter_without_transport_should_create_fallback_document() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)

    document = UrlFetchAdapter().fetch_hit(task_input, plan, _url_hit(), fetch_index=1)

    assert document is not None
    assert document.url == "https://example.com/hit-url"
    assert "External snippet about verifier loops" in document.snapshot_text
    assert "https://example.com/hit-url" in document.snapshot_text
    assert document.adapter == "external_url"
    assert document.snapshot_status == "FALLBACK"
    assert document.fetch_status == "FALLBACK_USED"
    assert document.fetch_method == "NO_TRANSPORT"
    assert document.content_origin == "SEARCH_SNIPPET_FALLBACK"
    assert document.content_type_label == "SEARCH_SNIPPET_FALLBACK"
    assert document.fetch_error_reason == "no url snapshot transport configured"
    assert document.fetch_failure_code == "NO_TRANSPORT"
    assert document.fetch_attempts == ["NO_TRANSPORT"]
    assert document.transport_chain == ["NO_TRANSPORT"]
    assert document.transport_resolution == "NO_TRANSPORT"
    assert document.transport_fallback_code == "NO_TRANSPORT"
    assert document.transport_attempt_count == 1
    assert document.snapshot_archive_ready is False
    assert document.source_domain == "example.com"


def test_url_fetch_adapter_with_transport_should_use_snapshot_content() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    hit = _url_hit()
    transport = FakeUrlSnapshotTransport(
        {
            hit.url: {
                "text": "Fetched page snapshot with verifier loop details.",
                "snapshot_key": "research/ws-1/run-1/snapshot/page.json",
                "fetch_status": "FETCHED",
                "fetch_method": "FAKE_FETCH",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["FAKE_FETCH"],
                "content_type_label": "WEBPAGE",
            }
        }
    )

    document = UrlFetchAdapter(transport=transport).fetch_hit(task_input, plan, hit, fetch_index=1)

    assert document is not None
    assert document.snapshot_text == "Fetched page snapshot with verifier loop details."
    assert document.snapshot_status == "FETCHED"
    assert document.snapshot_key == "research/ws-1/run-1/snapshot/page.json"
    assert document.fetch_status == "FETCHED"
    assert document.fetch_method == "FAKE_FETCH"
    assert document.content_origin == "FETCHED_SNAPSHOT"
    assert document.content_type_label == "WEBPAGE"
    assert document.fetch_failure_code == ""
    assert document.fetch_attempts == ["FAKE_FETCH"]
    assert document.transport_chain == ["FAKE_FETCH"]
    assert document.transport_resolution == "FAKE_FETCH"
    assert document.transport_fallback_code == ""
    assert document.transport_attempt_count == 1
    assert document.snapshot_archive_ready is True
    assert transport.calls == [(hit.url, hit.query, 800)]


def test_url_fetch_adapter_with_composite_transport_should_record_fallback_chain() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    hit = _url_hit("hit-composite")
    composite_transport = CompositeUrlSnapshotTransport(
        [
            FakeUrlSnapshotTransport(
                {
                    hit.url: {
                        "snapshot_status": "FETCH_FAILED",
                        "fetch_status": "FALLBACK_USED",
                        "fetch_method": "JINA",
                        "content_origin": "SEARCH_SNIPPET_FALLBACK",
                        "fetch_error_reason": "jina fetch failed after retries",
                        "fetch_attempts": ["JINA"],
                        "content_type_label": "WEBPAGE",
                        "fetch_failure_code": "FETCH_RETRY_EXHAUSTED",
                        "transport_fallback_code": "FETCH_RETRY_EXHAUSTED",
                    }
                }
            ),
            FakeUrlSnapshotTransport(
                {
                    hit.url: {
                        "text": "HTTP fallback recovered readable verifier loop content.",
                        "snapshot_status": "HTTP_FETCHED",
                        "snapshot_key": "snap-http-1",
                        "fetch_status": "FETCHED",
                        "fetch_method": "HTTP",
                        "content_origin": "FETCHED_SNAPSHOT",
                        "fetch_error_reason": "",
                        "fetch_attempts": ["HTTP"],
                        "content_type_label": "WEBPAGE",
                    }
                }
            ),
        ]
    )

    document = UrlFetchAdapter(transport=composite_transport).fetch_hit(task_input, plan, hit, fetch_index=1)

    assert document is not None
    assert document.snapshot_text == "HTTP fallback recovered readable verifier loop content."
    assert document.fetch_attempts == ["JINA", "HTTP"]
    assert document.transport_chain == ["JINA", "HTTP"]
    assert document.transport_resolution == "HTTP"
    assert document.transport_fallback_reason == "jina fetch failed after retries"
    assert document.content_type_label == "WEBPAGE"
    assert document.fetch_failure_code == ""
    assert document.transport_fallback_code == "FETCH_RETRY_EXHAUSTED"
    assert document.transport_attempt_count == 2
    assert document.snapshot_archive_ready is True


def test_url_fetch_adapter_should_preserve_structured_unsupported_content_failure() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    hit = _url_hit("hit-pdf")
    transport = FakeUrlSnapshotTransport(
        {
            hit.url: {
                "snapshot_status": "FETCH_FAILED",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "content_type_label": "PDF",
                "fetch_error_reason": "unsupported binary content type: application/pdf",
                "fetch_failure_code": "UNSUPPORTED_CONTENT_TYPE",
                "fetch_attempts": ["HTTP"],
                "transport_fallback_code": "UNSUPPORTED_CONTENT_TYPE",
            }
        }
    )

    document = UrlFetchAdapter(transport=transport).fetch_hit(task_input, plan, hit, fetch_index=1)

    assert document is not None
    assert document.fetch_status == "FALLBACK_USED"
    assert document.fetch_method == "HTTP"
    assert document.content_origin == "SEARCH_SNIPPET_FALLBACK"
    assert document.content_type_label == "PDF"
    assert document.fetch_failure_code == "UNSUPPORTED_CONTENT_TYPE"
    assert document.transport_fallback_code == "UNSUPPORTED_CONTENT_TYPE"
    assert document.transport_attempt_count == 1
    assert document.snapshot_archive_ready is False


def test_fetch_recovery_should_prioritize_target_source_and_matching_query() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "COUNTERFACTUAL_RECHECK"
    plan.stop_contract["recovery_target_sources"] = ["Workspace Evidence"]
    plan.stop_contract["recovery_target_queries"] = ["verifier loops"]
    hits = [_url_hit("hit-url-1", 1), _workspace_hit()]
    adapter = CompositeFetchAdapter([WorkspaceFetchAdapter(), UrlFetchAdapter()])

    documents = adapter.fetch(task_input, plan, hits)

    assert documents[0].source_title == "External Evidence"
    assert documents[0].query == "verifier loops external"


def test_composite_fetch_should_isolate_one_adapter_failure_from_other_hits() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    adapter = CompositeFetchAdapter(
        [FailingFetchAdapter(), WorkspaceFetchAdapter(), UrlFetchAdapter()],
        max_concurrency=2,
    )

    documents = adapter.fetch(task_input, plan, [_url_hit(), _workspace_hit()])

    assert [document.hit_id for document in documents] == ["hit-workspace"]


def test_requirement_targeted_fetch_should_prioritize_matching_query() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    plan.stop_contract["recovery_target_requirement_types"] = ["CONSTRAINT_FINDING"]
    plan.stop_contract["recovery_target_queries"] = [
        "How should verifier loops read evidence? :: verified evidence search :: Evidence finding"
    ]
    targeted_hit = _url_hit("hit-targeted", 2).model_copy(
        update={
            "query": "How should verifier loops read evidence? :: verified evidence search :: Evidence finding",
            "search_angle": "source_scoped",
        }
    )
    hits = [_workspace_hit(), targeted_hit]

    documents = run_research_fetch(task_input, plan, hits)

    assert documents[0].hit_id == "hit-targeted"


def test_read_response_bytes_should_not_stop_at_legacy_small_limit() -> None:
    payload = b"a" * 12_000 + b"tail-evidence"

    raw = _read_response_bytes(ChunkedResponse(payload, chunk_size=1500), token_budget=800)

    assert raw.endswith(b"tail-evidence")
    assert len(raw) == len(payload)


def test_decode_response_should_use_gb18030_when_utf8_would_mojibake() -> None:
    text = "国务院政策文件显示证据链完整"
    raw = text.encode("gb18030")

    decoded = _decode_response(raw, "text/html")

    assert decoded == text


def test_decode_response_should_use_meta_charset() -> None:
    text = "<html><head><meta charset=gb2312></head><body>中文证据</body></html>"
    raw = text.encode("gb2312")

    decoded = _decode_response(raw, "text/html")

    assert "中文证据" in decoded


def test_source_quality_should_downgrade_placeholder_repository_content() -> None:
    quality, score = infer_source_quality(
        url="https://github.com/example/placeholder",
        provider="web",
        adapter="external",
        title="Placeholder Repo",
        content_text="TODO coming soon",
    )

    assert quality == "GENERAL_WEB"
    assert score <= 0.45


def test_source_quality_should_preserve_substantive_repository_content() -> None:
    quality, score = infer_source_quality(
        url="https://github.com/example/substantive",
        provider="web",
        adapter="external",
        title="Substantive Repo",
        content_text=" ".join(["architecture module testing evidence workflow"] * 12),
    )

    assert quality == "REPOSITORY_SOURCE"
    assert score >= 0.8
