import asyncio

from fastapi.testclient import TestClient
import httpx
import pytest
from starlette.requests import ClientDisconnect

from app.config import Settings
from app.errors import ParseFailure
from app.main import create_app
from conftest import DATA, HEADERS, FakeConverter, FakeDocument, params


def post(app, *, data=DATA, query=None, headers=None):
    with TestClient(app) as client:
        return client.post("/internal/parse", params=query or params(data), content=data,
                           headers=HEADERS if headers is None else headers)


def test_success_uses_local_file_and_exact_metadata(make_app, tmp_path):
    app, fake = make_app()
    response = post(app)
    assert response.status_code == 200
    value = response.json()
    assert set(value) == {"schema_version", "workspace_id", "source_id", "snapshot_id", "sha256",
        "mime_type", "markdown", "page_count", "elements", "parser", "truncated", "warnings"}
    assert {key: value[key] for key in ("workspace_id", "source_id", "snapshot_id", "sha256", "mime_type")} == {
        key: params()[key] for key in ("workspace_id", "source_id", "snapshot_id", "sha256", "mime_type")}
    assert value["schema_version"] == 1 and value["parser"] == "DOCLING"
    assert value["markdown"] == "真实文档正文"
    assert fake.calls[0]["bytes"] == DATA and fake.calls[0]["raises_on_error"] is True
    assert fake.calls[0]["path"].name == "input.pdf"
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("token", [None, "wrong-token"])
def test_wrong_auth_rejected_before_conversion(make_app, tmp_path, token):
    app, fake = make_app()
    headers = {"Content-Type": "application/octet-stream"}
    if token:
        headers["X-NoteWeave-Internal-Token"] = token
    response = post(app, headers=headers)
    assert response.status_code == 401
    assert fake.factory_calls == 0 and not list(tmp_path.iterdir())


def test_unconfigured_auth_rejected_and_health_does_not_expose_config(tmp_path):
    fake = FakeConverter()
    app = create_app(Settings(internal_auth_token="", temp_root=tmp_path), converter_factory=fake.factory)
    with TestClient(app) as client:
        response = client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "INTERNAL_AUTH_NOT_CONFIGURED"
        assert client.get("/health").json() == {"status": "UP", "parser": "DOCLING"}
    assert fake.factory_calls == 0


@pytest.mark.parametrize("file_name", ["https://example.org/input.pdf", "../input.pdf", "C:\\input.pdf", "..", "a\x00.pdf"])
def test_paths_and_urls_rejected(make_app, file_name):
    app, fake = make_app()
    assert post(app, query=params(file_name=file_name)).status_code == 422
    assert fake.factory_calls == 0


@pytest.mark.parametrize("length,status", [(300, 200), (301, 422)])
def test_file_name_length_matches_java_limit(make_app, length, status):
    app, fake = make_app()
    file_name = "a" * (length - len(".pdf")) + ".pdf"
    response = post(app, query=params(file_name=file_name))
    assert response.status_code == status
    assert len(fake.calls) == (1 if status == 200 else 0)


@pytest.mark.parametrize("updates", [{"file_name": "image.png"}, {"mime_type": "text/html"}])
def test_unsupported_or_mismatched_type_rejected(make_app, updates):
    app, fake = make_app()
    assert post(app, query=params(**updates)).status_code == 415
    assert fake.factory_calls == 0


def test_octet_stream_required(make_app):
    app, fake = make_app()
    assert post(app, headers={**HEADERS, "Content-Type": "application/pdf"}).status_code == 415
    assert fake.factory_calls == 0


def test_hash_mismatch_rejected_and_cleaned(make_app, tmp_path):
    app, fake = make_app()
    response = post(app, query=params(sha256="0" * 64))
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "DOCUMENT_HASH_MISMATCH"
    assert not fake.calls and not list(tmp_path.iterdir())


def test_empty_document_rejected(make_app, tmp_path):
    app, fake = make_app()
    assert post(app, data=b"").json()["detail"]["code"] == "EMPTY_DOCUMENT"
    assert not fake.calls and not list(tmp_path.iterdir())


@pytest.mark.parametrize("length,expected", [("bad", 400), ("-1", 413), ("9", 413)])
def test_declared_input_limit_precedes_conversion(make_app, tmp_path, length, expected):
    app, fake = make_app(max_upload_bytes=8)
    response = post(app, headers={**HEADERS, "Content-Length": length})
    assert response.status_code == expected
    assert not fake.calls and not list(tmp_path.iterdir())


def test_stream_hash_uses_all_chunks_without_preloading(make_app, tmp_path):
    app, fake = make_app()
    chunks = [b"first-", b"second-", b"third"]
    async def execute():
        async def body():
            for chunk in chunks:
                yield chunk
                await asyncio.sleep(0)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/internal/parse", params=params(b"".join(chunks)), content=body(), headers=HEADERS)
    assert asyncio.run(execute()).status_code == 200
    assert fake.calls[0]["bytes"] == b"".join(chunks)
    assert not list(tmp_path.iterdir())


def test_actual_stream_limit_stops_before_later_chunks(make_app, tmp_path):
    app, fake = make_app(max_upload_bytes=8)
    delivered = []
    async def execute():
        async def body():
            for chunk in [b"1234", b"56789", b"not-read"]:
                delivered.append(chunk)
                yield chunk
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/internal/parse", params=params(), content=body(), headers=HEADERS)
    assert asyncio.run(execute()).status_code == 413
    assert delivered == [b"1234", b"56789"]
    assert not fake.calls and not list(tmp_path.iterdir())


@pytest.mark.parametrize("fake,code,expected", [
    (FakeConverter(error=RuntimeError("private-sdk-detail")), "DOCLING_CONVERSION_FAILED", 422),
    (FakeConverter(status="partial_success"), "DOCLING_CONVERSION_FAILED", 422),
    (FakeConverter(actual_format="docx"), "DOCUMENT_TYPE_MISMATCH", 415),
    (FakeConverter(FakeDocument(rows=[])), "NO_TEXT_CONTENT", 422),
])
def test_conversion_failures_do_not_return_empty_success(make_app, tmp_path, fake, code, expected):
    app, _ = make_app(fake)
    response = post(app)
    assert response.status_code == expected
    assert response.json()["detail"]["code"] == code
    assert "private-sdk-detail" not in response.text
    assert not list(tmp_path.iterdir())


def test_admission_limit_returns_429_before_factory_and_upload(make_app, tmp_path):
    app, fake = make_app(FakeConverter(blocking=True))
    async def execute():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            first = asyncio.create_task(client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS))
            try:
                assert await asyncio.to_thread(fake.started.wait, 2)
                second = await client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
                assert second.status_code == 429 and second.headers["Retry-After"] == "1"
                assert len(fake.calls) == 1 and len(list(tmp_path.iterdir())) == 1
            finally:
                fake.release.set()
            assert (await first).status_code == 200
    asyncio.run(execute())
    assert fake.factory_calls == 1 and not list(tmp_path.iterdir())


def test_shared_converter_stays_serial_when_two_documents_admitted(make_app, tmp_path):
    app, fake = make_app(FakeConverter(blocking=True), max_concurrency=2)
    async def execute():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            first = asyncio.create_task(client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS))
            second = None
            try:
                assert await asyncio.to_thread(fake.started.wait, 2)
                second = asyncio.create_task(client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS))
                for _ in range(100):
                    if len(list(tmp_path.iterdir())) == 2:
                        break
                    await asyncio.sleep(0.005)
                assert len(list(tmp_path.iterdir())) == 2
                rejected = await client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
                assert rejected.status_code == 429
                assert len(fake.calls) == 1
            finally:
                fake.release.set()
            assert (await first).status_code == 200 and (await second).status_code == 200
    asyncio.run(execute())
    assert fake.factory_calls == 1 and len(fake.calls) == 2 and not list(tmp_path.iterdir())


def test_timeout_keeps_capacity_and_file_until_actual_conversion_finishes(make_app, tmp_path):
    app, fake = make_app(FakeConverter(blocking=True), document_timeout_seconds=0.04)
    async def execute():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            first = await client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
            assert first.status_code == 504 and fake.started.is_set()
            assert fake.calls[0]["path"].exists()
            second = await client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
            assert second.status_code == 429 and len(fake.calls) == 1
            fake.release.set()
            for _ in range(100):
                if not list(tmp_path.iterdir()):
                    break
                await asyncio.sleep(0.01)
            assert not list(tmp_path.iterdir())
            third = await client.post("/internal/parse", params=params(), content=DATA, headers=HEADERS)
            assert third.status_code == 200
    try:
        asyncio.run(execute())
    finally:
        fake.release.set()


def test_upload_timeout_cleans_file_without_converter(make_app, tmp_path):
    app, fake = make_app(upload_timeout_seconds=0.01)
    async def execute():
        async def body():
            yield b"first"
            await asyncio.sleep(0.05)
            yield b"later"
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/internal/parse", params=params(), content=body(), headers=HEADERS)
    assert asyncio.run(execute()).status_code == 504
    assert not fake.calls and not list(tmp_path.iterdir())


def test_interrupted_upload_cleans_partial_file_without_conversion(make_app, tmp_path):
    app, fake = make_app()
    async def execute():
        async def body():
            yield b"partial-bytes"
            raise ClientDisconnect()
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/internal/parse", params=params(), content=body(), headers=HEADERS)
    response = asyncio.run(execute())
    assert response.status_code == 400 and response.json()["detail"]["code"] == "UPLOAD_INTERRUPTED"
    assert not fake.calls and not list(tmp_path.iterdir())


def test_missing_sdk_returns_unavailable_and_cleans_upload(make_app, tmp_path):
    app, fake = make_app()
    def unavailable(_settings):
        raise ParseFailure(503, "DOCLING_UNAVAILABLE", "本地 Docling 依赖尚未就绪")
    app.state.parse_service.converter_factory = unavailable
    response = post(app)
    assert response.status_code == 503 and response.json()["detail"]["code"] == "DOCLING_UNAVAILABLE"
    assert not fake.calls and not list(tmp_path.iterdir())


def test_query_limit_cannot_raise_server_output_cap(make_app):
    app, _ = make_app(max_output_chars=2)
    response = post(app, query=params(max_chars=100))
    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "PARSE_OUTPUT_LIMIT_EXCEEDED"


@pytest.mark.parametrize("file_name,mime_type,actual_format", [
    ("actual.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    ("actual.rtf", "application/rtf", "rtf"), ("actual.doc", "application/msword", "doc"),
    ("actual.epub", "application/epub+zip", "epub"), ("actual.odt", "application/vnd.oasis.opendocument.text", "odt"),
    ("actual.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    ("actual.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    ("actual.html", "text/html", "html"), ("actual.md", "text/markdown", "md"),
    ("actual.txt", "text/plain", "md"), ("actual.csv", "text/csv", "csv")])
def test_declared_formats_are_verified_against_sdk_result(make_app, file_name, mime_type, actual_format):
    app, fake = make_app(FakeConverter(actual_format=actual_format))
    response = post(app, query=params(file_name=file_name, mime_type=mime_type))
    assert response.status_code == 200
    assert fake.calls[0]["path"].suffix == "." + file_name.rsplit(".", 1)[1]
    assert response.json()["mime_type"] == mime_type


def test_generic_json_is_not_misrepresented_as_docling_document_json(make_app):
    app, fake = make_app()
    response = post(app, data=b'{"ordinary": "json"}', query=params(
        b'{"ordinary": "json"}', file_name="data.json", mime_type="application/json"))
    assert response.status_code == 415
    assert response.json()["detail"]["code"] == "UNSUPPORTED_DOCUMENT_TYPE" and fake.factory_calls == 0
