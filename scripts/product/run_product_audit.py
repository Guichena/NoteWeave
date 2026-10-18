"""产品全链路走查：用固定语料新建工作台，依次走问答、笔记、Wiki、各个产物技能和记忆，保存每一步的真实输出和耗时。

和 E1、E2 不同，这里不打分，目的是让人能逐条读到用户真正看到的回答和产物，找出体验和质量问题。
默认只打印计划；传入 --execute 才会创建工作台、上传语料并产生真实模型调用。
记录写入 experiments/product-audit/runs-<时间>/，summary.md 汇总每一步的状态、耗时和输出开头。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/deepresearch"))
sys.path.insert(0, str(ROOT / "scripts/retrieval"))
from run_minimal_eval import ApiClient, EvalRunError, authenticate  # noqa: E402
from run_retrieval_eval import ask, sources_indexed, upload_corpus, wait_until, wiki_stable  # noqa: E402

AUDIT_DIR = ROOT / "experiments/product-audit"
CORPUS_DATASET = ROOT / "experiments/retrieval/dataset.json"
TERMINAL_JOB_STATUSES = {"COMPLETED", "SUCCEEDED", "FAILED", "CANCELLED"}
# 需要外部链接或视频的技能不在固定语料上走查
SKIPPED_SKILLS = ("bilibili", "video")

QUESTIONS = [
    ("QA", "问答链路的检索分几步？每一步做什么？"),
    ("QA", "Memory 机制里记忆是怎么写入和淘汰的？"),
    ("QA", "Wiki 模式和普通问答在检索上有什么区别？"),
    ("QA", "系统支持哪些付费方式？"),
    ("NOTE", "帮我整理一份关于检索重排的学习笔记"),
    ("WIKI", "Memory 和 Wiki 之间是什么关系？"),
]
FOLLOW_UP = ("QA", "问答链路的检索分几步？每一步做什么？", "那重排这一步用的是什么模型？")


def write(run_dir: Path, name: str, payload: object) -> None:
    (run_dir / f"{name}.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def answer_text(result: dict[str, Any]) -> str:
    run = result.get("run") or {}
    return str(run.get("content") or run.get("answer") or "")


def run_question(client: ApiClient, workspace_id: str, mode: str, question: str, source_ids: list[str],
                 follow_up: str | None = None) -> dict[str, Any]:
    conversation = client.request("POST", f"/api/v2/workspaces/{workspace_id}/conversations", {
        "title": question[:40], "conversation_type": "WORKSPACE_CHAT"})
    conversation_id = str(conversation["conversation_id"])
    turns = [ask(client, workspace_id, conversation_id, question, mode, source_ids, 300, 2)]
    if follow_up:
        turns.append(ask(client, workspace_id, conversation_id, follow_up, mode, source_ids, 300, 2))
    return {"mode": mode, "question": question, "follow_up": follow_up, "turns": turns}


def skill_inputs(skill: dict[str, Any]) -> dict[str, Any] | None:
    """按技能声明的输入取默认值；必填项没有默认值时返回 None，跳过这个技能。"""
    schema = skill.get("input_schema") or {}
    properties = schema.get("properties") or {}
    inputs = {key: spec["default"] for key, spec in properties.items() if isinstance(spec, dict) and "default" in spec}
    missing = [key for key in schema.get("required") or [] if key not in inputs]
    return None if missing else inputs


def run_skill(client: ApiClient, workspace_id: str, skill: dict[str, Any], source_ids: list[str],
              timeout_seconds: int) -> dict[str, Any]:
    started = time.monotonic()
    inputs = skill_inputs(skill)
    if inputs is None:
        return {"skill_key": skill["skill_key"], "status": "SKIPPED_REQUIRED_INPUT"}
    created = client.request("POST", f"/api/v2/workspaces/{workspace_id}/artifact-jobs", {
        "skill_key": skill["skill_key"],
        "user_requirement": f"技能：{skill.get('display_name', '')}\n输出语言：中文",
        "inputs": inputs,
        "source_scope_source_ids": source_ids,
    })
    job_id = str(created["artifact_job_id"])

    def finished() -> dict[str, Any] | None:
        jobs = client.request("GET", f"/api/v2/workspaces/{workspace_id}/artifact-jobs")
        job = next((item for item in jobs if item.get("artifact_job_id") == job_id), None)
        return job if job and str(job.get("status", "")).upper() in TERMINAL_JOB_STATUSES else None

    try:
        job = wait_until(finished, timeout_seconds, 5, f"artifact job {job_id}")
    except EvalRunError as error:
        return {"skill_key": skill["skill_key"], "status": "TIMEOUT", "error": str(error),
                "latency_ms": round((time.monotonic() - started) * 1000)}
    version: dict[str, Any] = {}
    try:
        versions = client.request("GET", f"/api/v2/workspaces/{workspace_id}/artifact-jobs/{job_id}/versions")
        if versions:
            latest = max(int(item.get("version_no", 0)) for item in versions)
            version = client.request(
                "GET", f"/api/v2/workspaces/{workspace_id}/artifact-jobs/{job_id}/versions/{latest}")
    except EvalRunError as error:
        version = {"unavailable": str(error)}
    return {"skill_key": skill["skill_key"], "status": job.get("status"), "job": job, "version": version,
            "latency_ms": round((time.monotonic() - started) * 1000)}


def summary_line(kind: str, name: str, status: str, latency_ms: object, text: str) -> str:
    preview = " ".join(text.split())[:160].replace("|", "\\|")
    return f"| {kind} | {name} | {status} | {latency_ms} | {preview} |"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--skill-timeout-seconds", type=int, default=900)
    args = parser.parse_args(argv)
    if not args.execute:
        print(f"计划：{len(QUESTIONS)} 个问题、1 组追问、全部可用技能和记忆列表；加 --execute 真实运行")
        return 0

    client = ApiClient(args.base_url)
    authenticate(client, os.environ["NOTEWEAVE_EVAL_USERNAME"], os.environ["NOTEWEAVE_EVAL_EMAIL"],
                 os.environ["NOTEWEAVE_EVAL_PASSWORD"])
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    run_dir = AUDIT_DIR / f"runs-{stamp}"
    run_dir.mkdir(parents=True, exist_ok=True)
    workspace = client.request("POST", "/api/v2/workspaces", {
        "name": f"product-audit-{stamp}", "description": "全链路走查"})
    workspace_id = str(workspace["workspace_id"])
    client.request("PUT", f"/api/v2/workspaces/{workspace_id}/wiki-settings", {"wiki_enabled": True})
    dataset = json.loads(CORPUS_DATASET.read_text(encoding="utf-8"))
    started = time.monotonic()
    source_ids = upload_corpus(client, workspace_id, dataset)
    wait_until(lambda: sources_indexed(client, workspace_id, source_ids), 1800, 5, "sources indexed")
    ingest_ms = round((time.monotonic() - started) * 1000)
    last: dict[str, int] = {}
    try:
        wait_until(lambda: wiki_stable(client, workspace_id, last), 1200, 20, "wiki pages stable")
    except EvalRunError as error:
        last["error"] = str(error)  # type: ignore[assignment]
    lines = ["# 产品走查记录", "", f"工作台 {workspace_id}，资料入库耗时 {ingest_ms} ms，Wiki 页面 {last.get('count')}。", "",
             "| 类型 | 名称 | 状态 | 耗时 ms | 输出开头 |", "|---|---|---|---|---|"]

    for index, (mode, question) in enumerate(QUESTIONS, start=1):
        try:
            result = run_question(client, workspace_id, mode, question, source_ids)
        except EvalRunError as error:
            result = {"mode": mode, "question": question, "error": str(error), "turns": []}
        write(run_dir, f"answer-{index:02d}", result)
        turn = (result["turns"] or [{}])[-1]
        lines.append(summary_line(mode, question, str((turn.get("run") or {}).get("status", "ERROR")),
                                  turn.get("latency_ms", "-"), answer_text(turn) if turn else result.get("error", "")))
    mode, question, follow_up = FOLLOW_UP
    try:
        result = run_question(client, workspace_id, mode, question, source_ids, follow_up)
    except EvalRunError as error:
        result = {"mode": mode, "question": question, "error": str(error), "turns": []}
    write(run_dir, "answer-follow-up", result)
    turn = (result["turns"] or [{}])[-1]
    lines.append(summary_line("追问", follow_up, str((turn.get("run") or {}).get("status", "ERROR")),
                              turn.get("latency_ms", "-"), answer_text(turn) if turn else result.get("error", "")))

    skills = client.request("GET", "/api/v2/skills")
    for skill in skills:
        key = str(skill.get("skill_key", ""))
        if any(marker in key for marker in SKIPPED_SKILLS) or str(skill.get("status", "")).upper() not in {"ACTIVE", "ENABLED", ""}:
            continue
        try:
            result = run_skill(client, workspace_id, skill, source_ids, args.skill_timeout_seconds)
        except EvalRunError as error:
            result = {"skill_key": key, "status": "ERROR", "error": str(error)}
        write(run_dir, f"skill-{key}", result)
        version = result.get("version") or {}
        lines.append(summary_line("产物", key, str(result.get("status")), result.get("latency_ms", "-"),
                                  str(version.get("content_markdown") or result.get("error") or
                                      (result.get("job") or {}).get("progress_message", ""))))

    for name, path in (("memory-items", f"/api/v2/workspaces/{workspace_id}/memory/items"),
                       ("wiki-index", f"/api/v2/workspaces/{workspace_id}/wiki-index"),
                       ("wiki-issues", f"/api/v2/workspaces/{workspace_id}/wiki-issues"),
                       ("sources", f"/api/v2/workspaces/{workspace_id}/sources")):
        try:
            write(run_dir, name, client.request("GET", path))
        except EvalRunError as error:
            write(run_dir, name, {"unavailable": str(error)})
    (run_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"workspace_id": workspace_id, "run_dir": str(run_dir.relative_to(ROOT))}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
