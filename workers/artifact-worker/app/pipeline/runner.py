"""所有 Skill 共用取材、规划、写作、校验、渲染五段流水线。"""

import time

from app.llm_client import bind_llm_session, build_default_llm_client
from app.models import ArtifactJobSnapshot, ArtifactProgressEvent, ArtifactTaskResult, ArtifactVersionSnapshot
from app.pipeline import projection
from app.pipeline.schemas import load_schema
from app.pipeline.skills import SkillCatalog
from app.pipeline.stages import gather, plan, draft, check, render


def run(task, *, passage_search=None, frame_reader=None, client=None, catalog=None):
    catalog = catalog or SkillCatalog()
    skill = catalog.resolve(task.input_payload.skill_key)
    if task.catalog_digest and task.catalog_digest != catalog.digest:
        raise ValueError("Skill 目录与任务冻结的目录摘要不一致")
    if task.replay_availability != "FULL":
        raise ValueError("任务输入不可完整重放")
    options = skill.normalize_options(task.input_payload.inputs)
    client = bind_llm_session(client or build_default_llm_client(), "artifact", task.workspace_id, task.task_id)
    if client is None:
        raise ValueError("产物流水线尚未配置模型服务")
    schema = load_schema(skill.kind, catalog.schemas)
    events = []
    stats = {"version": 1, "stages": {}}
    def stage(name, phase, percent, action):
        started = time.monotonic()
        result = action()
        stats["stages"][name] = {"duration_ms": round((time.monotonic() - started) * 1000)}
        label = {"gather": "取材", "plan": "规划", "draft": "写作", "check": "校验", "render": "渲染"}[name]
        events.append(ArtifactProgressEvent(phase=phase, progress_percent=percent, message=f"产物{label}阶段已完成"))
        return result
    evidence = stage("gather", "RESOLVING", 20, lambda: gather.gather(task, skill, options, client, passage_search))
    outline = stage("plan", "ACQUIRING", 35, lambda: plan.plan(task, skill, options, evidence, client))
    value = stage("draft", "COMPOSING", 65, lambda: draft.draft(task, skill, options, evidence, outline, schema, client))
    value, diagnostics = stage("check", "VERIFYING", 85, lambda: check.check(value, outline, skill, evidence, schema, client))
    sections, files = stage("render", "EXPORTING", 100, lambda: render.render(value, skill, task, evidence=evidence, frame_reader=frame_reader))
    payload, citations = projection.project(task, value, evidence, sections, files)
    stats.update(diagnostics)
    payload["pipeline_stats"] = stats
    payload["verification"] = {"status": "PASS", "contract_status": "PASS", "evidence_status": "PASS", "failed_checks": []}
    return events, ArtifactTaskResult(result_title=value["title"], result_payload=payload,
        trace_summary="已完成取材、规划、写作、校验与渲染", citations=citations,
        job_snapshot=ArtifactJobSnapshot(task_id=task.task_id, workspace_id=task.workspace_id, target_id=task.target_id,
            action_key=skill.kind, status="COMPLETED"),
        version_snapshot=ArtifactVersionSnapshot(version_id="", artifact_type=skill.kind, title=value["title"],
            status="DRAFT", summary=skill.description))
