"""验证退休入口不可再访问，媒体与文件访问边界仍有效。"""

import pytest
from fastapi.testclient import TestClient
from app import main


@pytest.mark.parametrize("path", ["/debug/run-task", "/debug/artifact-skills", "/internal/default-actions",
    "/internal/resolve-action", "/tasks/example/resume", "/callbacks/acquisition/ack"])
def test_retired_endpoints_are_absent_even_with_valid_auth(monkeypatch, path):
    monkeypatch.setattr(main.settings, "internal_auth_token", "cleanup-test-token")
    client = TestClient(main.app)
    for method in [client.get, client.post]:
        assert method(path, headers={"X-NoteWeave-Internal-Token": "cleanup-test-token"}).status_code == 404


def test_media_and_download_endpoints_still_require_internal_auth(monkeypatch):
    monkeypatch.setattr(main.settings, "internal_auth_token", "cleanup-test-token")
    monkeypatch.setattr(main.app.state, "artifact_consumer_runtime", None, raising=False)
    client = TestClient(main.app)
    assert client.get("/health").status_code == 200
    assert client.post("/internal/source-video-info", json={"source_url": "example"}).status_code == 401
    assert client.get("/tasks/task/exports/content.csv").status_code == 401
    monkeypatch.setattr(main.settings, "internal_auth_token", "")
    assert client.get("/tasks/task/exports/content.csv").status_code == 503


def test_csv_download_uses_the_same_controlled_directory(monkeypatch, tmp_path):
    monkeypatch.setattr(main.settings, "internal_auth_token", "cleanup-test-token")
    monkeypatch.setattr(main, "resolve_mcp_sandbox_root", lambda settings: tmp_path)
    folder = tmp_path / "bilibili-render-pdf/exports/task"
    folder.mkdir(parents=True)
    content = b"name,value\ncache,30\n"
    (folder / "content.csv").write_bytes(content)
    response = TestClient(main.app).get("/tasks/task/exports/content.csv",
                                      headers={"X-NoteWeave-Internal-Token": "cleanup-test-token"})
    assert response.status_code == 200
    assert response.content == content
    assert response.headers["content-type"].startswith("text/csv")
