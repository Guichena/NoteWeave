"""共享入口、冻结身份、配置隔离与鉴权回归。"""

import asyncio
from starlette.requests import Request
from starlette.responses import JSONResponse
from app.config import load_settings
from app.main import app
from app.models import ArtifactTaskInput


def test_artifact_worker_imports() -> None:
    settings = load_settings()
    assert settings.worker_type == "artifact"
    assert app.title == "NoteWeave Artifact Worker"


def test_artifact_worker_preserves_input_snapshot_and_upstream_revision_refs() -> None:
    task_input = ArtifactTaskInput.model_validate(
        {
            "task_id": "task-1",
            "workspace_id": "workspace-1",
            "target_id": "artifact-1",
            "input_snapshot_id": "snapshot-1",
            "replay_availability": "METADATA_ONLY",
            "source_scope": [],
            "upstream_refs": [
                {
                    "ref_type": "RESEARCH_REPORT",
                    "ref_id": "research-run-1",
                    "revision_id": "source-snapshot-1",
                }
            ],
            "context_snapshot": {"context_snapshot_id": ""},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "report_draft",
                "task_neighborhood": "ARTIFACT_SKILL_REPORT_DRAFT",
            },
            "input_payload": {"skill_key": "report_draft"},
        }
    )

    assert task_input.input_snapshot_id == "snapshot-1"
    assert task_input.replay_availability == "METADATA_ONLY"
    assert task_input.upstream_refs[0].ref_type == "RESEARCH_REPORT"
    assert task_input.upstream_refs[0].revision_id == "source-snapshot-1"


def test_artifact_worker_should_use_only_its_namespaced_llm_configuration(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY", "artifact-key")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_BASE_URL", "https://artifact.example/v1")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MODEL", "artifact-model")
    monkeypatch.setenv("NOTEWEAVE_LLM_API_KEY", "backend-key-must-not-leak")
    monkeypatch.setenv("NOTEWEAVE_LLM_BASE_URL", "https://backend.example/v1")
    monkeypatch.setenv("NOTEWEAVE_LLM_MODEL", "backend-model")

    settings = load_settings()

    assert settings.llm_api_key == "artifact-key"
    assert settings.llm_base_url == "https://artifact.example/v1"
    assert settings.llm_model == "artifact-model"

    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY")
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_BASE_URL")
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_MODEL")
    isolated_settings = load_settings()

    assert isolated_settings.llm_api_key == ""
    assert isolated_settings.llm_base_url == ""
    assert isolated_settings.llm_model == ""


def test_formal_artifact_worker_routes_should_exclude_http_task_start_and_keep_control_routes() -> None:
    registered_routes = {route.path for route in app.routes}

    assert "/tasks/{task_id}/run" not in registered_routes
    assert "/tasks/{task_id}/resume" not in registered_routes
    assert "/callbacks/acquisition/ack" not in registered_routes
    assert "/internal/source-transcriptions" in registered_routes
    assert "/tasks/{task_id}/exports/{file_name}" in registered_routes


def test_artifact_worker_formal_routes_should_require_internal_token_when_configured() -> None:
    from app import main as main_module

    original_token = main_module.settings.internal_auth_token
    main_module.settings.internal_auth_token = "worker-shared-secret"
    try:
        unauthorized_request = Request({
            "type": "http",
            "method": "GET",
            "path": "/internal/source-video-info",
            "headers": [],
        })
        unauthorized = asyncio.run(
            main_module.protect_internal_routes(
                unauthorized_request,
                lambda _: JSONResponse({"ok": True}),
            )
        )
        authenticated_request = Request({
            "type": "http",
            "method": "GET",
            "path": "/internal/source-video-info",
            "headers": [(b"x-noteweave-internal-token", b"worker-shared-secret")],
        })

        async def authenticated_next(_):
            return JSONResponse({"ok": True})

        authenticated = asyncio.run(
            main_module.protect_internal_routes(authenticated_request, authenticated_next)
        )

        assert unauthorized.status_code == 401
        assert authenticated.status_code == 200
    finally:
        main_module.settings.internal_auth_token = original_token


def test_artifact_worker_formal_routes_should_fail_closed_when_token_is_unset() -> None:
    from app import main as main_module

    original_token = main_module.settings.internal_auth_token
    main_module.settings.internal_auth_token = ""
    try:
        request = Request({
            "type": "http",
            "method": "POST",
            "path": "/tasks/task-1/exports/content.csv",
            "headers": [],
        })
        response = asyncio.run(
            main_module.protect_internal_routes(request, lambda _: JSONResponse({"ok": True}))
        )
        assert response.status_code == 503
    finally:
        main_module.settings.internal_auth_token = original_token
