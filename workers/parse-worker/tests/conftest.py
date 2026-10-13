"""轻量测试显式提供 Docling 对象，不加载模型或下载依赖。"""

from hashlib import sha256
from pathlib import Path
from types import SimpleNamespace
import threading

import pytest

from app.config import Settings
from app.main import create_app


def enum(value):
    return SimpleNamespace(value=value)


def provenance(page=2, origin="TOPLEFT"):
    return SimpleNamespace(page_no=page, bbox=SimpleNamespace(
        l=10.0, t=20.0 if origin == "TOPLEFT" else 70.0, r=150.0,
        b=70.0 if origin == "TOPLEFT" else 20.0, coord_origin=enum(origin)))


def item(label="text", text="真实文档正文", *, prov=None, **values):
    return SimpleNamespace(label=enum(label), text=text, prov=prov or [], **values)


class FakeTable:
    label = enum("table")

    def __init__(self, text="| 名称 | 数值 |\n| --- | --- |\n| 原文 | 120% |", *, prov=None):
        self.text = text
        self.prov = prov or []
        self.document = None
        self.exports = 0

    def export_to_markdown(self, *, doc):
        assert doc is self.document
        self.exports += 1
        return self.text


class FakeDocument:
    def __init__(self, rows=None, pages=None):
        self.rows = rows if rows is not None else [item()]
        self.pages = pages if pages is not None else {1: object(), 2: object()}
        self.iteration_options = []
        for row in self.rows:
            if isinstance(row, FakeTable):
                row.document = self

    def iterate_items(self, **kwargs):
        self.iteration_options.append(kwargs)
        for row in self.rows:
            yield row, 0


class FakeConverter:
    def __init__(self, document=None, *, status="success", actual_format="pdf", error=None, blocking=False):
        self.document = document or FakeDocument()
        self.status = status
        self.actual_format = actual_format
        self.error = error
        self.blocking = blocking
        self.started = threading.Event()
        self.release = threading.Event()
        self.calls = []
        self.factory_calls = 0

    def factory(self, _settings):
        self.factory_calls += 1
        return self

    def convert(self, source, *, raises_on_error, max_file_size):
        assert isinstance(source, Path)
        self.calls.append({"path": source, "bytes": source.read_bytes(),
            "raises_on_error": raises_on_error, "max_file_size": max_file_size})
        self.started.set()
        if self.blocking:
            assert self.release.wait(5), "测试必须释放阻塞的 Fake converter"
        if self.error:
            raise self.error
        return SimpleNamespace(document=self.document, status=enum(self.status),
                               input=SimpleNamespace(format=enum(self.actual_format)))


DATA = b"explicit-fake-pdf-content"
HEADERS = {"Content-Type": "application/octet-stream", "X-NoteWeave-Internal-Token": "test-token"}


def params(data=DATA, **updates):
    values = {"workspace_id": "workspace-1", "source_id": "source-1", "snapshot_id": "snapshot-1",
        "file_name": "document.pdf", "mime_type": "application/pdf", "sha256": sha256(data).hexdigest()}
    values.update(updates)
    return values


@pytest.fixture
def make_app(tmp_path):
    applications = []

    def make(converter=None, **settings):
        fake = converter or FakeConverter()
        config = Settings(internal_auth_token="test-token", temp_root=tmp_path, **settings)
        app = create_app(config, converter_factory=fake.factory)
        applications.append(app)
        return app, fake

    yield make
    for app in applications:
        converter = app.state.parse_service.converter
        if converter is not None:
            converter.release.set()
        app.state.parse_service.close()
