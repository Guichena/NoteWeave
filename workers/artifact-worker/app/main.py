from fastapi import BackgroundTasks, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from fastapi.responses import FileResponse
from pathlib import Path
import re
import hmac
import logging
from threading import Lock
from contextlib import asynccontextmanager
from pydantic import BaseModel, Field

from app.artifact_repository import (
    configure_artifact_repository_backend,
    get_artifact_version_detail,
    get_retrieval_entry_detail,
    get_artifact_repository_backend_info,
    list_artifact_versions,
    list_retrieval_entries,
    rollback_artifact_version,
)
from app.artifact_skill_catalog import list_artifact_skill_definitions
from app.capability_approval_queue import (
    approve_capability_request,
    get_approval_request,
    list_approval_requests,
)
from app.capability_provider import (
    get_capability_mapping_detail,
    get_capability_provider_detail,
    debug_capability_mapping_snapshot,
    debug_list_capability_providers,
    debug_provider_discovery_snapshot,
    debug_provider_health_snapshot,
    run_capability_provider_discovery_scan,
    run_capability_provider_health_checks,
)
from app.capability_wait_queue import (
    configure_waiting_task_store,
    get_waiting_task,
    list_waiting_tasks,
    wake_waiting_task,
)
from app.config import load_settings, resolve_mcp_sandbox_root
from app.error_sanitizer import sanitize_error_message
from app.callback import (
    ArtifactAcquisitionAckExecutionResponse,
    ArtifactWorkerExecutionResponse,
    acknowledge_acquisition_operation_with_callbacks,
    resume_waiting_artifact_task_with_callbacks,
    run_artifact_task_with_callbacks,
)
from app.acquisition_runtime import (
    acknowledge_acquisition_operation,
    clear_acquisition_runtime,
    configure_acquisition_runtime_store,
    dispatch_acquisition_operation,
    get_acquisition_callback_receipt_detail,
    get_acquisition_operation_detail,
    list_acquisition_callback_receipts,
    list_acquisition_operations,
)
from app.models import (
    ArtifactTaskInput,
    CustomMcpServerRegistration,
    CustomProductionActionRegistration,
    CustomPromptRecipeRegistration,
    CustomSkillDefinitionRegistration,
    CustomSkillGraphTemplateRegistration,
    CustomStyleProfileRegistration,
)
from app.registry import (
    configure_custom_artifact_config_store,
    get_custom_artifact_config_store_info,
    list_custom_mcp_blueprints as registry_list_custom_mcp_blueprints,
    list_custom_mcp_servers as registry_list_custom_mcp_servers,
    list_default_actions,
    list_default_capability_bindings,
    list_default_prompt_recipes,
    list_default_skill_definitions,
    list_default_skill_graph_templates,
    list_default_style_profiles,
    register_custom_action,
    register_custom_mcp_server,
    register_custom_prompt_recipe,
    register_custom_skill_definition,
    register_custom_skill_graph_template,
    register_custom_style_profile,
    reset_custom_actions,
    reset_custom_mcp_servers as registry_reset_custom_mcp_servers,
    reset_custom_prompt_recipes,
    reset_custom_skill_definitions,
    reset_custom_skill_graph_templates,
    reset_custom_style_profiles,
)
from app.runner import run_artifact_task
from app.action_resolver import resolve_requested_action
from app.writeback_runtime import (
    acknowledge_writeback_request,
    clear_writeback_runtime,
    dispatch_writeback_request,
    execute_writeback_request,
    get_writeback_receipt_detail,
    get_writeback_request_detail,
    list_writeback_receipts,
    list_writeback_requests,
)
from app.system_mcp_executor import recover_system_mcp_acquisition_operations

settings = load_settings()
configure_artifact_repository_backend(
    settings.artifact_repository_backend,
    storage_path=settings.artifact_repository_file_path,
)
configure_custom_artifact_config_store(settings.artifact_customization_file_path)
configure_acquisition_runtime_store(settings.artifact_runtime_state_file_path)
configure_waiting_task_store(settings.artifact_wait_queue_file_path)


@asynccontextmanager
async def artifact_worker_lifespan(_: FastAPI):
    recover_system_mcp_acquisition_operations(java_base_url=settings.java_base_url)
    yield


app = FastAPI(title="NoteWeave Artifact Worker", lifespan=artifact_worker_lifespan)
logger = logging.getLogger(__name__)
_dispatched_task_lock = Lock()
_dispatched_task_ids: set[str] = set()


@app.middleware("http")
async def protect_debug_routes(request: Request, call_next):
    if request.url.path.startswith("/debug") and not settings.debug_routes_enabled:
        return JSONResponse(status_code=404, content={"detail": "Not Found"})
    protected_prefixes = ("/tasks/", "/callbacks/", "/internal/", "/debug/")
    if request.url.path.startswith(protected_prefixes) and not settings.internal_auth_token:
        return JSONResponse(
            status_code=503,
            content={"detail": "internal service authentication is not configured"},
        )
    if request.url.path.startswith(protected_prefixes):
        supplied_token = request.headers.get("X-NoteWeave-Internal-Token", "")
        if not hmac.compare_digest(supplied_token, settings.internal_auth_token):
            return JSONResponse(
                status_code=401,
                content={"detail": "internal service authentication failed"},
            )
    return await call_next(request)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "worker_type": settings.worker_type}


@app.get("/tasks/{task_id}/exports/{file_name}", response_class=FileResponse)
def download_task_export(task_id: str, file_name: str) -> FileResponse:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task_id):
        raise HTTPException(status_code=404, detail="artifact export not found")
    if not re.fullmatch(r"[^/\\]{1,160}\.pdf", file_name):
        raise HTTPException(status_code=404, detail="artifact export not found")
    export_root = (
        resolve_mcp_sandbox_root(settings)
        / "bilibili-render-pdf"
        / "exports"
        / task_id
    ).resolve()
    export_path = (export_root / file_name).resolve()
    if not export_path.is_relative_to(export_root) or not export_path.is_file():
        raise HTTPException(status_code=404, detail="artifact export not found")
    return FileResponse(export_path, media_type="application/pdf", filename=file_name)


class DebugArtifactRunResponse(BaseModel):
    events: list[dict[str, object]]
    result: dict[str, object]


class ArtifactTaskDispatchResponse(BaseModel):
    task_id: str
    status: str


class DebugDefaultActionCatalogResponse(BaseModel):
    actions: list[dict[str, object]]


class DebugArtifactSkillCatalogResponse(BaseModel):
    skills: list[dict[str, object]]


class DebugPromptRecipeCatalogResponse(BaseModel):
    recipes: list[dict[str, object]]


class DebugStyleProfileCatalogResponse(BaseModel):
    profiles: list[dict[str, object]]


class DebugSkillDefinitionCatalogResponse(BaseModel):
    skills: list[dict[str, object]]


class DebugSkillGraphCatalogResponse(BaseModel):
    graphs: list[dict[str, object]]


class DebugCapabilityBindingCatalogResponse(BaseModel):
    bindings: list[dict[str, object]]


class DebugCustomSkillDefinitionRegistrationResponse(BaseModel):
    skill: dict[str, object]


class DebugCustomSkillGraphRegistrationResponse(BaseModel):
    graph: dict[str, object]


class DebugActionResolutionResponse(BaseModel):
    action_resolution: dict[str, object]


class DebugCustomActionRegistrationResponse(BaseModel):
    action: dict[str, object]


class DebugCustomPromptRecipeRegistrationResponse(BaseModel):
    recipe: dict[str, object]


class DebugCustomStyleProfileRegistrationResponse(BaseModel):
    profile: dict[str, object]


class DebugCustomActionResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomMcpServerRegistrationResponse(BaseModel):
    server: dict[str, object]


class DebugCustomMcpServerListResponse(BaseModel):
    servers: list[dict[str, object]]


class DebugCustomMcpServerResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomMcpBlueprintListResponse(BaseModel):
    blueprints: list[dict[str, object]]


class DebugCustomPromptRecipeResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomStyleProfileResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomSkillDefinitionResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomSkillGraphResetResponse(BaseModel):
    reset: dict[str, object]


class DebugCustomArtifactConfigStoreResponse(BaseModel):
    store: dict[str, object]


class DebugArtifactVersionListResponse(BaseModel):
    versions: list[dict[str, object]]


class DebugArtifactVersionDetailResponse(BaseModel):
    version: dict[str, object]


class DebugRetrievalEntryDetailResponse(BaseModel):
    entry: dict[str, object]


class DebugRetrievalEntryListResponse(BaseModel):
    entries: list[dict[str, object]]


class DebugArtifactRollbackResponse(BaseModel):
    receipt: dict[str, object]


class DebugCapabilityProviderListResponse(BaseModel):
    providers: list[dict[str, object]]


class DebugCapabilityProviderDetailResponse(BaseModel):
    provider: dict[str, object]


class DebugCapabilityProviderHealthResponse(BaseModel):
    snapshot: dict[str, object]


class DebugCapabilityProviderDiscoveryResponse(BaseModel):
    snapshot: dict[str, object]


class DebugCapabilityMappingResponse(BaseModel):
    snapshot: dict[str, object]


class DebugCapabilityMappingDetailResponse(BaseModel):
    mapping: dict[str, object]


class DebugCapabilityApprovalRequestListResponse(BaseModel):
    requests: list[dict[str, object]]


class DebugCapabilityApprovalRequestDetailResponse(BaseModel):
    request: dict[str, object]


class DebugCapabilityApprovalRequestApproveResponse(BaseModel):
    request: dict[str, object]


class DebugArtifactRepositoryBackendResponse(BaseModel):
    backend: dict[str, object]


class DebugWaitingTaskListResponse(BaseModel):
    tasks: list[dict[str, object]]


class DebugWaitingTaskDetailResponse(BaseModel):
    task: dict[str, object]


class DebugWaitingTaskWakeResponse(BaseModel):
    events: list[dict[str, object]]
    result: dict[str, object]


class DebugAcquisitionOperationListResponse(BaseModel):
    operations: list[dict[str, object]]


class DebugAcquisitionOperationDetailResponse(BaseModel):
    operation: dict[str, object]


class DebugAcquisitionCallbackReceiptListResponse(BaseModel):
    receipts: list[dict[str, object]]


class DebugAcquisitionCallbackReceiptDetailResponse(BaseModel):
    receipt: dict[str, object]


class DebugAcquisitionRuntimeResetResponse(BaseModel):
    reset: dict[str, object]


class DebugAcquisitionDispatchResponse(BaseModel):
    operation: dict[str, object]
    dispatch: dict[str, object]


class DebugAcquisitionAckResponse(BaseModel):
    operation: dict[str, object]
    receipt: dict[str, object]
    resume_attempts: list[dict[str, object]]


class DebugWritebackRequestListResponse(BaseModel):
    requests: list[dict[str, object]]


class DebugWritebackRequestDetailResponse(BaseModel):
    request: dict[str, object]


class DebugWritebackReceiptListResponse(BaseModel):
    receipts: list[dict[str, object]]


class DebugWritebackReceiptDetailResponse(BaseModel):
    receipt: dict[str, object]


class DebugWritebackExecuteResponse(BaseModel):
    request: dict[str, object]
    receipt: dict[str, object]


class DebugWritebackDispatchResponse(BaseModel):
    request: dict[str, object]
    dispatch: dict[str, object]


class DebugWritebackAckResponse(BaseModel):
    request: dict[str, object]
    receipt: dict[str, object]


class DebugWritebackResetResponse(BaseModel):
    reset: dict[str, object]


class ArtifactWaitingTaskResumeRequest(BaseModel):
    request_id: str = Field(default="", max_length=256)


class ArtifactAcquisitionAckRequest(BaseModel):
    callback_token: str = Field(min_length=1, max_length=512)
    final_status: str = Field(min_length=1, max_length=32)
    result_locator: str = Field(default="", max_length=2048)
    error_code: str = Field(default="", max_length=128)
    error_message: str = Field(default="", max_length=4000)
    provider_payload: dict[str, object] = Field(default_factory=dict)


def _to_public_input_schema(input_schema: object) -> object:
    if not isinstance(input_schema, dict):
        return input_schema
    public_schema = dict(input_schema)
    properties = public_schema.get("properties")
    if not isinstance(properties, dict):
        return public_schema

    public_properties = dict(properties)
    canonical_url_schema = None
    for alias_key in ("url", "video_url", "bilibili_url"):
        alias_schema = public_properties.get(alias_key)
        if isinstance(alias_schema, dict):
            canonical_url_schema = dict(alias_schema)
            break
    public_properties.pop("video_url", None)
    public_properties.pop("bilibili_url", None)
    if canonical_url_schema is not None:
        public_properties["url"] = canonical_url_schema
    public_schema["properties"] = public_properties
    return public_schema


def _strip_legacy_action_keys(value: object) -> object:
    if isinstance(value, dict):
        public_record: dict[str, object] = {}
        for key, item in value.items():
            if key == "action_checks":
                public_record["contract_checks"] = _strip_legacy_action_keys(item)
                continue
            if key in {
                "action_key",
                "action_resolution",
                "action_scope",
                "action_basis",
                "skill_graph_basis",
                "requested_action_key",
                "resolved_action_key",
                "explicit_requested_action_key",
                "effective_action_key",
            }:
                continue
            public_record[key] = _strip_legacy_action_keys(item)
        return public_record
    if isinstance(value, list):
        return [_strip_legacy_action_keys(item) for item in value]
    return value


def _to_public_artifact_version_record(version: dict[str, object]) -> dict[str, object]:
    public_record = dict(version)
    public_record.pop("action_key", None)
    execution_plan_summary = public_record.get("execution_plan_summary")
    if isinstance(execution_plan_summary, dict):
        public_execution_plan_summary = dict(execution_plan_summary)
        public_execution_plan_summary.pop("action_key", None)
        public_record["execution_plan_summary"] = public_execution_plan_summary
    return _strip_legacy_action_keys(public_record)


def _to_public_artifact_result_record(result: dict[str, object]) -> dict[str, object]:
    public_result = dict(result)
    job_snapshot = public_result.get("job_snapshot")
    if isinstance(job_snapshot, dict):
        public_job_snapshot = dict(job_snapshot)
        public_job_snapshot.pop("action_key", None)
        public_result["job_snapshot"] = public_job_snapshot

    result_payload = public_result.get("result_payload")
    if isinstance(result_payload, dict):
        public_result_payload = dict(result_payload)
        execution_plan = public_result_payload.get("execution_plan")
        if isinstance(execution_plan, dict):
            public_execution_plan = dict(execution_plan)
            public_execution_plan.pop("action_key", None)
            public_result_payload["execution_plan"] = public_execution_plan
        public_result["result_payload"] = public_result_payload
    return _strip_legacy_action_keys(public_result)


def _to_public_waiting_task_record(record: dict[str, object]) -> dict[str, object]:
    public_record = dict(record)
    task_input = public_record.get("task_input")
    if isinstance(task_input, dict):
        public_task_input = dict(task_input)
        input_payload = public_task_input.get("input_payload")
        if isinstance(input_payload, dict):
            public_input_payload = dict(input_payload)
            public_input_payload.pop("action_key", None)
            public_task_input["input_payload"] = public_input_payload
        public_record["task_input"] = public_task_input
    return _strip_legacy_action_keys(public_record)


def _to_public_artifact_skill_record(skill: dict[str, object]) -> dict[str, object]:
    public_record = dict(skill)
    public_record.pop("requires_url_input", None)
    public_record.pop("url_input_keys", None)
    public_record["input_schema"] = _to_public_input_schema(public_record.get("input_schema"))
    return public_record


def _to_public_prompt_recipe_record(recipe: dict[str, object]) -> dict[str, object]:
    public_record = dict(recipe)
    public_record.pop("supported_actions", None)
    return public_record


def _to_public_skill_graph_record(graph: dict[str, object]) -> dict[str, object]:
    public_record = dict(graph)
    public_record.pop("action_type", None)
    return public_record


def _to_public_custom_mcp_server_record(server: dict[str, object]) -> dict[str, object]:
    public_record = dict(server)
    tools = public_record.get("tools")
    if isinstance(tools, list):
        public_tools: list[dict[str, object]] = []
        for tool in tools:
            if not isinstance(tool, dict):
                continue
            public_tool = dict(tool)
            public_tool.pop("allowed_actions", None)
            public_tool.pop("supported_actions", None)
            public_tools.append(public_tool)
        public_record["tools"] = public_tools
    return public_record


def _to_public_capability_binding_record(binding: dict[str, object]) -> dict[str, object]:
    public_record = dict(binding)
    public_record.pop("allowed_actions", None)
    return public_record


def _to_public_capability_provider_record(provider: dict[str, object]) -> dict[str, object]:
    public_record = dict(provider)
    public_record.pop("supported_actions", None)
    public_record.pop("action_basis", None)
    return public_record


def _to_public_capability_mapping_record(mapping: dict[str, object]) -> dict[str, object]:
    public_record = dict(mapping)
    public_record.pop("supported_actions", None)
    public_record.pop("action_basis", None)
    return public_record


def _to_public_provider_snapshot(snapshot: dict[str, object]) -> dict[str, object]:
    public_snapshot = dict(snapshot)
    providers = public_snapshot.get("providers")
    if isinstance(providers, list):
        public_snapshot["providers"] = [
            _to_public_capability_provider_record(provider)
            for provider in providers
            if isinstance(provider, dict)
        ]
    return public_snapshot


def _to_public_mapping_snapshot(snapshot: dict[str, object]) -> dict[str, object]:
    public_snapshot = dict(snapshot)
    mappings = public_snapshot.get("mappings")
    if isinstance(mappings, list):
        public_snapshot["mappings"] = [
            _to_public_capability_mapping_record(mapping)
            for mapping in mappings
            if isinstance(mapping, dict)
        ]
    return public_snapshot


@app.post("/debug/run-task", response_model=DebugArtifactRunResponse)
def debug_run_task(task_input: ArtifactTaskInput) -> DebugArtifactRunResponse:
    events, result = run_artifact_task(task_input)
    return DebugArtifactRunResponse(
        events=[event.model_dump(mode="json") for event in events],
        result=_to_public_artifact_result_record(result.model_dump(mode="json")),
    )


@app.post(
    "/tasks/{task_id}/run",
    response_model=ArtifactTaskDispatchResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def run_task_from_java(
    task_id: str,
    background_tasks: BackgroundTasks,
    delivery_token: str = Header(default="", alias="X-NoteWeave-Outbox-Delivery-Token"),
) -> ArtifactTaskDispatchResponse:
    with _dispatched_task_lock:
        if task_id in _dispatched_task_ids:
            return ArtifactTaskDispatchResponse(task_id=task_id, status="ALREADY_ACCEPTED")
        _dispatched_task_ids.add(task_id)
    background_tasks.add_task(_run_dispatched_artifact_task, task_id, delivery_token)
    return ArtifactTaskDispatchResponse(task_id=task_id, status="ACCEPTED")


def _run_dispatched_artifact_task(task_id: str, delivery_token: str = "") -> None:
    try:
        run_artifact_task_with_callbacks(task_id, delivery_token=delivery_token)
    except Exception as exc:
        logger.warning(
            "Artifact task reached a reported failure terminal state: task_id=%s error=%s",
            task_id,
            sanitize_error_message(str(exc)),
        )
    finally:
        with _dispatched_task_lock:
            _dispatched_task_ids.discard(task_id)


@app.post("/tasks/{task_id}/resume", response_model=ArtifactWorkerExecutionResponse)
def resume_task_from_java(
    task_id: str,
    request: ArtifactWaitingTaskResumeRequest,
    delivery_token: str = Header(default="", alias="X-NoteWeave-Outbox-Delivery-Token"),
) -> ArtifactWorkerExecutionResponse:
    return resume_waiting_artifact_task_with_callbacks(
        task_id,
        request_id=request.request_id,
        delivery_token=delivery_token,
    )


@app.post("/callbacks/acquisition/ack", response_model=ArtifactAcquisitionAckExecutionResponse)
def ack_acquisition_callback(
    request: ArtifactAcquisitionAckRequest,
) -> ArtifactAcquisitionAckExecutionResponse:
    return acknowledge_acquisition_operation_with_callbacks(
        callback_token=request.callback_token,
        final_status=request.final_status,
        result_locator=request.result_locator,
        error_code=request.error_code,
        error_message=request.error_message,
        provider_payload=request.provider_payload,
    )


@app.get("/internal/default-actions", response_model=DebugDefaultActionCatalogResponse)
def debug_list_default_actions() -> DebugDefaultActionCatalogResponse:
    return DebugDefaultActionCatalogResponse(
        actions=[action.model_dump(mode="json") for action in list_default_actions()]
    )


@app.get("/debug/artifact-skills", response_model=DebugArtifactSkillCatalogResponse)
def debug_get_artifact_skills() -> DebugArtifactSkillCatalogResponse:
    return DebugArtifactSkillCatalogResponse(
        skills=[
            _to_public_artifact_skill_record(skill.model_dump(mode="json"))
            for skill in list_artifact_skill_definitions()
        ]
    )


@app.post("/internal/resolve-action", response_model=DebugActionResolutionResponse)
def debug_resolve_action(task_input: ArtifactTaskInput) -> DebugActionResolutionResponse:
    return DebugActionResolutionResponse(
        action_resolution=resolve_requested_action(task_input).model_dump(mode="json")
    )


@app.post("/internal/register-custom-action", response_model=DebugCustomActionRegistrationResponse)
def debug_register_custom_action(
    registration: CustomProductionActionRegistration,
) -> DebugCustomActionRegistrationResponse:
    action = register_custom_action(registration)
    return DebugCustomActionRegistrationResponse(action=action.model_dump(mode="json"))


@app.post("/internal/reset-custom-actions", response_model=DebugCustomActionResetResponse)
def debug_reset_custom_actions() -> DebugCustomActionResetResponse:
    reset_custom_actions()
    return DebugCustomActionResetResponse(reset={"status": "OK"})


@app.get("/debug/style-profiles", response_model=DebugStyleProfileCatalogResponse)
def debug_get_style_profiles() -> DebugStyleProfileCatalogResponse:
    return DebugStyleProfileCatalogResponse(
        profiles=[profile.model_dump(mode="json") for profile in list_default_style_profiles()]
    )


@app.get("/debug/skill-definitions", response_model=DebugSkillDefinitionCatalogResponse)
def debug_get_skill_definitions() -> DebugSkillDefinitionCatalogResponse:
    return DebugSkillDefinitionCatalogResponse(
        skills=[skill.model_dump(mode="json") for skill in list_default_skill_definitions()]
    )


@app.get("/debug/skill-graphs", response_model=DebugSkillGraphCatalogResponse)
def debug_get_skill_graphs() -> DebugSkillGraphCatalogResponse:
    return DebugSkillGraphCatalogResponse(
        graphs=[
            _to_public_skill_graph_record(graph.model_dump(mode="json"))
            for graph in list_default_skill_graph_templates()
        ]
    )


@app.post(
    "/debug/register-custom-skill-definition",
    response_model=DebugCustomSkillDefinitionRegistrationResponse,
)
def debug_register_custom_skill_definition(
    registration: CustomSkillDefinitionRegistration,
) -> DebugCustomSkillDefinitionRegistrationResponse:
    skill = register_custom_skill_definition(registration)
    return DebugCustomSkillDefinitionRegistrationResponse(skill=skill.model_dump(mode="json"))


@app.post(
    "/debug/reset-custom-skill-definitions",
    response_model=DebugCustomSkillDefinitionResetResponse,
)
def debug_reset_custom_skill_definitions() -> DebugCustomSkillDefinitionResetResponse:
    reset_custom_skill_definitions()
    return DebugCustomSkillDefinitionResetResponse(reset={"status": "OK"})


@app.post(
    "/debug/register-custom-skill-graph",
    response_model=DebugCustomSkillGraphRegistrationResponse,
)
def debug_register_custom_skill_graph(
    registration: CustomSkillGraphTemplateRegistration,
) -> DebugCustomSkillGraphRegistrationResponse:
    graph = register_custom_skill_graph_template(registration)
    return DebugCustomSkillGraphRegistrationResponse(
        graph=_to_public_skill_graph_record(graph.model_dump(mode="json"))
    )


@app.post(
    "/debug/reset-custom-skill-graphs",
    response_model=DebugCustomSkillGraphResetResponse,
)
def debug_reset_custom_skill_graphs() -> DebugCustomSkillGraphResetResponse:
    reset_custom_skill_graph_templates()
    return DebugCustomSkillGraphResetResponse(reset={"status": "OK"})


@app.get("/debug/custom-mcp-servers", response_model=DebugCustomMcpServerListResponse)
def debug_get_custom_mcp_servers() -> DebugCustomMcpServerListResponse:
    return DebugCustomMcpServerListResponse(
        servers=[
            _to_public_custom_mcp_server_record(server.model_dump(mode="json"))
            for server in registry_list_custom_mcp_servers()
        ]
    )


@app.get("/debug/custom-mcp-blueprints", response_model=DebugCustomMcpBlueprintListResponse)
def debug_get_custom_mcp_blueprints() -> DebugCustomMcpBlueprintListResponse:
    return DebugCustomMcpBlueprintListResponse(
        blueprints=[
            _to_public_custom_mcp_server_record(blueprint.model_dump(mode="json"))
            for blueprint in registry_list_custom_mcp_blueprints()
        ]
    )


@app.get("/debug/capability-bindings", response_model=DebugCapabilityBindingCatalogResponse)
def debug_get_capability_bindings() -> DebugCapabilityBindingCatalogResponse:
    return DebugCapabilityBindingCatalogResponse(
        bindings=[
            _to_public_capability_binding_record(binding.model_dump(mode="json"))
            for binding in list_default_capability_bindings()
        ]
    )


@app.post(
    "/debug/register-custom-mcp-server",
    response_model=DebugCustomMcpServerRegistrationResponse,
)
def debug_register_custom_mcp_server(
    registration: CustomMcpServerRegistration,
) -> DebugCustomMcpServerRegistrationResponse:
    server = register_custom_mcp_server(registration)
    return DebugCustomMcpServerRegistrationResponse(
        server=_to_public_custom_mcp_server_record(server.model_dump(mode="json"))
    )


@app.post(
    "/debug/reset-custom-mcp-servers",
    response_model=DebugCustomMcpServerResetResponse,
)
def debug_reset_custom_mcp_servers() -> DebugCustomMcpServerResetResponse:
    registry_reset_custom_mcp_servers()
    return DebugCustomMcpServerResetResponse(reset={"status": "OK"})


@app.post(
    "/debug/register-custom-style-profile",
    response_model=DebugCustomStyleProfileRegistrationResponse,
)
def debug_register_custom_style_profile(
    registration: CustomStyleProfileRegistration,
) -> DebugCustomStyleProfileRegistrationResponse:
    profile = register_custom_style_profile(registration)
    return DebugCustomStyleProfileRegistrationResponse(profile=profile.model_dump(mode="json"))


@app.post(
    "/debug/reset-custom-style-profiles",
    response_model=DebugCustomStyleProfileResetResponse,
)
def debug_reset_custom_style_profiles() -> DebugCustomStyleProfileResetResponse:
    reset_custom_style_profiles()
    return DebugCustomStyleProfileResetResponse(reset={"status": "OK"})


@app.post(
    "/debug/register-custom-prompt-recipe",
    response_model=DebugCustomPromptRecipeRegistrationResponse,
)
def debug_register_custom_prompt_recipe(
    registration: CustomPromptRecipeRegistration,
) -> DebugCustomPromptRecipeRegistrationResponse:
    recipe = register_custom_prompt_recipe(registration)
    return DebugCustomPromptRecipeRegistrationResponse(
        recipe=_to_public_prompt_recipe_record(recipe.model_dump(mode="json"))
    )


@app.post(
    "/debug/reset-custom-prompt-recipes",
    response_model=DebugCustomPromptRecipeResetResponse,
)
def debug_reset_custom_prompt_recipes() -> DebugCustomPromptRecipeResetResponse:
    reset_custom_prompt_recipes()
    return DebugCustomPromptRecipeResetResponse(reset={"status": "OK"})


@app.get(
    "/debug/custom-artifact-config-store",
    response_model=DebugCustomArtifactConfigStoreResponse,
)
def debug_get_custom_artifact_config_store() -> DebugCustomArtifactConfigStoreResponse:
    return DebugCustomArtifactConfigStoreResponse(
        store=get_custom_artifact_config_store_info()
    )


@app.get("/debug/prompt-recipes", response_model=DebugPromptRecipeCatalogResponse)
def debug_get_prompt_recipes() -> DebugPromptRecipeCatalogResponse:
    return DebugPromptRecipeCatalogResponse(
        recipes=[
            _to_public_prompt_recipe_record(recipe.model_dump(mode="json"))
            for recipe in list_default_prompt_recipes()
        ]
    )


@app.get("/debug/capability-providers", response_model=DebugCapabilityProviderListResponse)
def debug_get_capability_providers() -> DebugCapabilityProviderListResponse:
    return DebugCapabilityProviderListResponse(
        providers=[
            _to_public_capability_provider_record(provider)
            for provider in debug_list_capability_providers()
        ]
    )


@app.get("/debug/capability-provider-detail", response_model=DebugCapabilityProviderDetailResponse)
def debug_get_capability_provider_detail(
    candidate_key: str,
) -> DebugCapabilityProviderDetailResponse:
    return DebugCapabilityProviderDetailResponse(
        provider=_to_public_capability_provider_record(
            get_capability_provider_detail(candidate_key)
        )
    )


@app.get(
    "/debug/capability-provider-discovery",
    response_model=DebugCapabilityProviderDiscoveryResponse,
)
def debug_get_capability_provider_discovery() -> DebugCapabilityProviderDiscoveryResponse:
    return DebugCapabilityProviderDiscoveryResponse(
        snapshot=_to_public_provider_snapshot(debug_provider_discovery_snapshot())
    )


@app.get("/debug/capability-provider-health", response_model=DebugCapabilityProviderHealthResponse)
def debug_get_capability_provider_health() -> DebugCapabilityProviderHealthResponse:
    return DebugCapabilityProviderHealthResponse(
        snapshot=_to_public_provider_snapshot(debug_provider_health_snapshot())
    )


@app.post(
    "/debug/run-capability-provider-discovery",
    response_model=DebugCapabilityProviderDiscoveryResponse,
)
def debug_run_capability_provider_discovery(
    capability_names: list[str] | None = None,
) -> DebugCapabilityProviderDiscoveryResponse:
    return DebugCapabilityProviderDiscoveryResponse(
        snapshot=_to_public_provider_snapshot(
            run_capability_provider_discovery_scan(capability_names)
        )
    )


@app.post("/debug/run-capability-provider-health-checks", response_model=DebugCapabilityProviderHealthResponse)
def debug_run_capability_provider_health_checks(
    capability_names: list[str] | None = None,
) -> DebugCapabilityProviderHealthResponse:
    return DebugCapabilityProviderHealthResponse(
        snapshot=_to_public_provider_snapshot(
            run_capability_provider_health_checks(capability_names)
        )
    )


@app.get("/debug/capability-mappings", response_model=DebugCapabilityMappingResponse)
def debug_get_capability_mappings() -> DebugCapabilityMappingResponse:
    return DebugCapabilityMappingResponse(
        snapshot=_to_public_mapping_snapshot(debug_capability_mapping_snapshot())
    )


@app.get("/debug/capability-mapping-detail", response_model=DebugCapabilityMappingDetailResponse)
def debug_get_capability_mapping_detail(
    mapping_key: str,
) -> DebugCapabilityMappingDetailResponse:
    return DebugCapabilityMappingDetailResponse(
        mapping=_to_public_capability_mapping_record(
            get_capability_mapping_detail(mapping_key)
        )
    )


@app.get("/debug/capability-approval-requests", response_model=DebugCapabilityApprovalRequestListResponse)
def debug_list_capability_approval_requests() -> DebugCapabilityApprovalRequestListResponse:
    return DebugCapabilityApprovalRequestListResponse(requests=list_approval_requests())


@app.get(
    "/debug/capability-approval-request-detail",
    response_model=DebugCapabilityApprovalRequestDetailResponse,
)
def debug_get_capability_approval_request_detail(
    request_id: str,
) -> DebugCapabilityApprovalRequestDetailResponse:
    return DebugCapabilityApprovalRequestDetailResponse(
        request=get_approval_request(request_id)
    )


@app.post(
    "/debug/approve-capability-request",
    response_model=DebugCapabilityApprovalRequestApproveResponse,
)
def debug_approve_capability_request(
    request_id: str,
) -> DebugCapabilityApprovalRequestApproveResponse:
    return DebugCapabilityApprovalRequestApproveResponse(
        request=approve_capability_request(request_id)
    )


@app.get("/debug/artifact-repository-backend", response_model=DebugArtifactRepositoryBackendResponse)
def debug_get_artifact_repository_backend() -> DebugArtifactRepositoryBackendResponse:
    return DebugArtifactRepositoryBackendResponse(
        backend=get_artifact_repository_backend_info()
    )


@app.get("/debug/waiting-tasks", response_model=DebugWaitingTaskListResponse)
def debug_list_waiting_tasks() -> DebugWaitingTaskListResponse:
    return DebugWaitingTaskListResponse(
        tasks=[_to_public_waiting_task_record(record) for record in list_waiting_tasks()]
    )


@app.get("/debug/waiting-task-detail", response_model=DebugWaitingTaskDetailResponse)
def debug_get_waiting_task_detail(task_id: str) -> DebugWaitingTaskDetailResponse:
    record = get_waiting_task(task_id)
    if record is None:
        raise ValueError(f"waiting task not found: {task_id}")
    return DebugWaitingTaskDetailResponse(task=_to_public_waiting_task_record(record))


@app.post("/debug/wake-waiting-task", response_model=DebugWaitingTaskWakeResponse)
def debug_wake_waiting_task(task_id: str, request_id: str = "") -> DebugWaitingTaskWakeResponse:
    events, result = wake_waiting_task(task_id, request_id=request_id)
    return DebugWaitingTaskWakeResponse(
        events=[event.model_dump(mode="json") for event in events],
        result=_to_public_artifact_result_record(result.model_dump(mode="json")),
    )


@app.get("/debug/acquisition-operations", response_model=DebugAcquisitionOperationListResponse)
def debug_list_acquisition_operations(
    task_id: str | None = None,
    status: str | None = None,
    capability_name: str | None = None,
) -> DebugAcquisitionOperationListResponse:
    return DebugAcquisitionOperationListResponse(
        operations=list_acquisition_operations(
            task_id=task_id,
            status=status,
            capability_name=capability_name,
        )
    )


@app.get(
    "/debug/acquisition-operation-detail",
    response_model=DebugAcquisitionOperationDetailResponse,
)
def debug_get_acquisition_operation_detail(
    request_id: str,
) -> DebugAcquisitionOperationDetailResponse:
    return DebugAcquisitionOperationDetailResponse(
        operation=get_acquisition_operation_detail(request_id=request_id)
    )


@app.get(
    "/debug/acquisition-callback-receipts",
    response_model=DebugAcquisitionCallbackReceiptListResponse,
)
def debug_list_acquisition_callback_receipts(
    task_id: str | None = None,
    request_id: str | None = None,
) -> DebugAcquisitionCallbackReceiptListResponse:
    return DebugAcquisitionCallbackReceiptListResponse(
        receipts=list_acquisition_callback_receipts(task_id=task_id, request_id=request_id)
    )


@app.get(
    "/debug/acquisition-callback-receipt-detail",
    response_model=DebugAcquisitionCallbackReceiptDetailResponse,
)
def debug_get_acquisition_callback_receipt_detail(
    receipt_id: str,
) -> DebugAcquisitionCallbackReceiptDetailResponse:
    return DebugAcquisitionCallbackReceiptDetailResponse(
        receipt=get_acquisition_callback_receipt_detail(receipt_id=receipt_id)
    )


@app.post("/debug/reset-acquisition-runtime", response_model=DebugAcquisitionRuntimeResetResponse)
def debug_reset_acquisition_runtime() -> DebugAcquisitionRuntimeResetResponse:
    clear_acquisition_runtime()
    return DebugAcquisitionRuntimeResetResponse(reset={"status": "ok"})


@app.post("/debug/dispatch-acquisition-operation", response_model=DebugAcquisitionDispatchResponse)
def debug_dispatch_acquisition_operation(request_id: str) -> DebugAcquisitionDispatchResponse:
    result = dispatch_acquisition_operation(request_id)
    return DebugAcquisitionDispatchResponse(
        operation=result["operation"],
        dispatch=result["dispatch"],
    )


@app.post("/debug/ack-acquisition-operation", response_model=DebugAcquisitionAckResponse)
def debug_ack_acquisition_operation(
    callback_token: str,
    final_status: str,
    result_locator: str = "",
    error_code: str = "",
    error_message: str = "",
) -> DebugAcquisitionAckResponse:
    result = acknowledge_acquisition_operation(
        callback_token=callback_token,
        final_status=final_status,
        result_locator=result_locator,
        error_code=error_code,
        error_message=error_message,
    )
    return DebugAcquisitionAckResponse(
        operation=result["operation"],
        receipt=result["receipt"],
        resume_attempts=result["resume_attempts"],
    )


@app.get("/debug/artifact-versions", response_model=DebugArtifactVersionListResponse)
def debug_list_artifact_versions(
    target_id: str | None = None,
    skill_key: str | None = None,
    action_key: str | None = None,
    status: str | None = None,
) -> DebugArtifactVersionListResponse:
    return DebugArtifactVersionListResponse(
        versions=[
            _to_public_artifact_version_record(version)
            for version in list_artifact_versions(
                target_id=target_id,
                skill_key=skill_key,
                action_key=action_key,
                status=status,
            )
        ]
    )


@app.get("/debug/artifact-version-detail", response_model=DebugArtifactVersionDetailResponse)
def debug_get_artifact_version_detail(
    target_id: str,
    version_id: str,
) -> DebugArtifactVersionDetailResponse:
    return DebugArtifactVersionDetailResponse(
        version=_to_public_artifact_version_record(
            get_artifact_version_detail(target_id=target_id, version_id=version_id)
        )
    )


@app.get("/debug/retrieval-entries", response_model=DebugRetrievalEntryListResponse)
def debug_list_retrieval_entries() -> DebugRetrievalEntryListResponse:
    return DebugRetrievalEntryListResponse(entries=list_retrieval_entries())


@app.get("/debug/retrieval-entry-detail", response_model=DebugRetrievalEntryDetailResponse)
def debug_get_retrieval_entry_detail(
    target_id: str,
    retrieval_entry_id: str,
) -> DebugRetrievalEntryDetailResponse:
    return DebugRetrievalEntryDetailResponse(
        entry=get_retrieval_entry_detail(
            target_id=target_id,
            retrieval_entry_id=retrieval_entry_id,
        )
    )


@app.post("/debug/artifact-rollback", response_model=DebugArtifactRollbackResponse)
def debug_rollback_artifact_version(target_id: str, version_id: str) -> DebugArtifactRollbackResponse:
    return DebugArtifactRollbackResponse(
        receipt=rollback_artifact_version(target_id=target_id, version_id=version_id)
    )


@app.get("/debug/writeback-requests", response_model=DebugWritebackRequestListResponse)
def debug_list_writeback_requests(
    status: str | None = None,
    allowed_target: str | None = None,
    version_id: str | None = None,
) -> DebugWritebackRequestListResponse:
    return DebugWritebackRequestListResponse(
        requests=list_writeback_requests(
            status=status,
            allowed_target=allowed_target,
            version_id=version_id,
        )
    )


@app.get("/debug/writeback-request-detail", response_model=DebugWritebackRequestDetailResponse)
def debug_get_writeback_request_detail(
    request_id: str,
) -> DebugWritebackRequestDetailResponse:
    return DebugWritebackRequestDetailResponse(
        request=get_writeback_request_detail(request_id=request_id)
    )


@app.get("/debug/writeback-receipts", response_model=DebugWritebackReceiptListResponse)
def debug_list_writeback_receipts(
    request_id: str | None = None,
    version_id: str | None = None,
) -> DebugWritebackReceiptListResponse:
    return DebugWritebackReceiptListResponse(
        receipts=list_writeback_receipts(request_id=request_id, version_id=version_id)
    )


@app.get("/debug/writeback-receipt-detail", response_model=DebugWritebackReceiptDetailResponse)
def debug_get_writeback_receipt_detail(
    receipt_id: str,
) -> DebugWritebackReceiptDetailResponse:
    return DebugWritebackReceiptDetailResponse(
        receipt=get_writeback_receipt_detail(receipt_id=receipt_id)
    )


@app.post("/debug/execute-writeback-request", response_model=DebugWritebackExecuteResponse)
def debug_execute_writeback_request(request_id: str) -> DebugWritebackExecuteResponse:
    result = execute_writeback_request(request_id)
    return DebugWritebackExecuteResponse(
        request=result["request"],
        receipt=result["receipt"],
    )


@app.post("/debug/dispatch-writeback-request", response_model=DebugWritebackDispatchResponse)
def debug_dispatch_writeback_request(request_id: str) -> DebugWritebackDispatchResponse:
    result = dispatch_writeback_request(request_id)
    return DebugWritebackDispatchResponse(
        request=result["request"],
        dispatch=result["dispatch"],
    )


@app.post("/debug/ack-writeback-request", response_model=DebugWritebackAckResponse)
def debug_ack_writeback_request(
    callback_token: str,
    final_status: str,
    target_locator: str = "",
    error_code: str = "",
    error_message: str = "",
) -> DebugWritebackAckResponse:
    result = acknowledge_writeback_request(
        callback_token=callback_token,
        final_status=final_status,
        target_locator=target_locator,
        error_code=error_code,
        error_message=error_message,
    )
    return DebugWritebackAckResponse(
        request=result["request"],
        receipt=result["receipt"],
    )


@app.post("/debug/reset-writeback-runtime", response_model=DebugWritebackResetResponse)
def debug_reset_writeback_runtime() -> DebugWritebackResetResponse:
    clear_writeback_runtime()
    return DebugWritebackResetResponse(reset={"status": "ok"})
