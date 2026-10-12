"""运行 retrieval-eval-v1：上传固定语料，按问题集提问，保存每个回答的证据清单和耗时。

默认只打印计划；传入 --execute 才会创建工作台、上传语料并产生真实模型调用。
记录保存在 experiments/retrieval/runs/<问题集>/<case_key>.json，由 score_retrieval_eval.py 打分。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/deepresearch"))

from run_minimal_eval import ApiClient, EvalRunError, authenticate  # noqa: E402

EVAL_DIR = ROOT / "experiments/retrieval"
DATASET = EVAL_DIR / "dataset.json"
RUNS_DIR = EVAL_DIR / "runs"
UPLOAD_CHUNK_SIZE = 1024 * 1024
TERMINAL_ANSWER_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}


def corpus_digest(path: Path) -> str:
    """统一换行符后计算摘要，Windows 检出时的 CRLF 不影响校验。"""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def verify_corpus(dataset: dict[str, Any]) -> None:
    """语料与冻结时的摘要不一致时拒绝运行，避免不同版本的结果混在一起比较。"""
    for item in dataset["corpus"]:
        digest = corpus_digest(EVAL_DIR / item["path"])
        if digest != item["sha256"]:
            raise EvalRunError(f"corpus {item['path']} changed: expected {item['sha256']}, got {digest}")


def selected_sets(dataset: dict[str, Any], requested: set[str]) -> list[str]:
    names = list(dataset["sets"])
    unknown = requested - set(names)
    if unknown:
        raise EvalRunError(f"unknown sets: {sorted(unknown)}")
    return [name for name in names if not requested or name in requested]


def put_bytes(client: ApiClient, path: str, content: bytes) -> None:
    request = urllib.request.Request(client.base_url + path, data=content, method="PUT", headers={
        "Authorization": f"Bearer {client.token}", "Content-Type": "application/octet-stream"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            response.read()
    except urllib.error.HTTPError as error:
        raise EvalRunError(f"PUT {path} failed ({error.code})") from error


def upload_corpus(client: ApiClient, workspace_id: str, dataset: dict[str, Any]) -> list[str]:
    source_ids = []
    for item in dataset["corpus"]:
        content = (EVAL_DIR / item["path"]).read_bytes()
        total_chunks = max(1, math.ceil(len(content) / UPLOAD_CHUNK_SIZE))
        upload = client.request("POST", f"/api/v2/workspaces/{workspace_id}/uploads", {
            "file_name": Path(item["path"]).name,
            "file_size": len(content),
            "mime_type": "text/markdown",
            "chunk_size": UPLOAD_CHUNK_SIZE,
            "total_chunks": total_chunks,
        })
        for index in range(total_chunks):
            put_bytes(client, f"/api/v2/uploads/{upload['upload_id']}/chunks/{index}",
                      content[index * UPLOAD_CHUNK_SIZE:(index + 1) * UPLOAD_CHUNK_SIZE])
        completed = client.request("POST", f"/api/v2/uploads/{upload['upload_id']}/complete", {})
        source_ids.append(str(completed["source_id"]))
    return source_ids


def wait_until(check, timeout_seconds: int, poll_seconds: int, description: str):
    deadline = time.monotonic() + timeout_seconds
    while True:
        value = check()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise EvalRunError(f"timed out waiting for {description}")
        time.sleep(poll_seconds)


def sources_indexed(client: ApiClient, workspace_id: str, source_ids: list[str]) -> bool:
    sources = client.request("GET", f"/api/v2/workspaces/{workspace_id}/sources")
    states = {str(source["source_id"]): source for source in sources}
    failed = [sid for sid in source_ids if str(states.get(sid, {}).get("status", "")).upper() == "FAILED"]
    if failed:
        raise EvalRunError(f"sources failed to process: {failed}")
    return all(
        str(states.get(sid, {}).get("status", "")).upper() == "READY"
        and str(states.get(sid, {}).get("index_status", "")).upper() == "INDEXED"
        for sid in source_ids
    )


def wiki_stable(client: ApiClient, workspace_id: str, last: dict[str, int]) -> bool:
    """Wiki 页面数连续两次不变且大于 0 时认为编译完成。"""
    count = int(client.request("GET", f"/api/v2/workspaces/{workspace_id}/wiki-index").get("page_count") or 0)
    stable = count > 0 and last.get("count") == count
    last["count"] = count
    return stable


def ask(client: ApiClient, workspace_id: str, conversation_id: str, content: str, mode: str,
        source_ids: list[str], timeout_seconds: int, poll_seconds: int) -> dict[str, Any]:
    started = time.monotonic()
    receipt = client.request("POST", f"/api/v2/conversations/{conversation_id}/messages", {
        "content": content,
        "answer_mode": mode,
        "client_request_id": f"retrieval-eval-{uuid.uuid4()}",
        "source_scope_source_ids": source_ids,
    })
    run_id = str(receipt["answer_run_id"])
    run = wait_until(
        lambda: (lambda snapshot: snapshot if snapshot.get("status") in TERMINAL_ANSWER_STATUSES else None)(
            client.request("GET", f"/api/v2/workspaces/{workspace_id}/answer-runs/{run_id}")),
        timeout_seconds, poll_seconds, f"answer run {run_id}")
    latency_ms = round((time.monotonic() - started) * 1000)
    try:
        evidence = client.request("GET", f"/api/v2/workspaces/{workspace_id}/answer-runs/{run_id}/evidence")
    except EvalRunError as error:
        evidence = {"unavailable": str(error)}
    return {"run_id": run_id, "receipt": receipt, "run": run, "evidence": evidence, "latency_ms": latency_ms}


def run_case(client: ApiClient, workspace_id: str, set_name: str, mode: str, case: dict[str, Any],
             source_ids: list[str], timeout_seconds: int, poll_seconds: int) -> Path:
    conversation = client.request("POST", f"/api/v2/workspaces/{workspace_id}/conversations", {
        "title": f"retrieval-eval {case['case_key']}", "conversation_type": "WORKSPACE_CHAT"})
    turns = [ask(client, workspace_id, str(conversation["conversation_id"]), content, mode, source_ids,
                 timeout_seconds, poll_seconds) for content in case["turns"]]
    last = turns[-1]
    # 3A、3·S7 的字段按接口约定可能出现在回答详情或证据清单中，两处都读
    def field(name: str) -> Any:
        return last["run"].get(name, (last["evidence"] or {}).get(name))

    payload = {
        "schema_version": "retrieval-eval-run.v1",
        "set": set_name,
        "case_key": case["case_key"],
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "final_status": last["run"].get("status"),
        "latency_ms": last["latency_ms"],
        "evidence": last["evidence"],
        "retrieval_rewrite": field("retrieval_rewrite"),
        "evidence_sufficiency": field("evidence_sufficiency"),
        "retrieval_degraded": last["receipt"].get("retrieval_degraded"),
        "retrieval_degradation_reasons": last["receipt"].get("retrieval_degradation_reasons"),
        "turns": turns,
    }
    output = RUNS_DIR / set_name / f"{case['case_key']}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:3000")
    parser.add_argument("--set", action="append", default=[], dest="sets")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--ingest-timeout-seconds", type=int, default=1800)
    parser.add_argument("--poll-seconds", type=int, default=3)
    args = parser.parse_args(argv)
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    verify_corpus(dataset)
    sets = selected_sets(dataset, set(args.sets))
    if not args.execute:
        print(json.dumps({"mode": "DRY_RUN", "sets": {name: len(dataset["sets"][name]["cases"]) for name in sets}},
                         ensure_ascii=False, indent=2))
        return 0

    username = os.environ.get("NOTEWEAVE_EVAL_USERNAME", "").strip()
    email = os.environ.get("NOTEWEAVE_EVAL_EMAIL", "").strip()
    password = os.environ.get("NOTEWEAVE_EVAL_PASSWORD", "")
    if not username or not email or not password:
        raise EvalRunError("set NOTEWEAVE_EVAL_USERNAME, NOTEWEAVE_EVAL_EMAIL and NOTEWEAVE_EVAL_PASSWORD")
    client = ApiClient(args.base_url)
    authenticate(client, username, email, password)
    workspace = client.request("POST", "/api/v2/workspaces", {
        "name": f"Retrieval eval {datetime.now(timezone.utc):%Y%m%d-%H%M%S}",
        "description": "retrieval-eval-v1 automated evaluation workspace",
    })
    workspace_id = str(workspace["workspace_id"])
    needs_wiki = any(dataset["sets"][name]["answer_mode"] == "WIKI" for name in sets)
    if needs_wiki:
        # 先打开 Wiki 再上传，资料处理完成后会编译成页面
        client.request("PUT", f"/api/v2/workspaces/{workspace_id}/wiki-settings", {"wiki_enabled": True})
    source_ids = upload_corpus(client, workspace_id, dataset)
    wait_until(lambda: sources_indexed(client, workspace_id, source_ids),
               args.ingest_timeout_seconds, args.poll_seconds * 5, "corpus indexing")
    if needs_wiki:
        last: dict[str, int] = {}
        wait_until(lambda: wiki_stable(client, workspace_id, last),
                   args.ingest_timeout_seconds, args.poll_seconds * 10, "wiki compilation")

    outputs = []
    for name in sets:
        definition = dataset["sets"][name]
        for case in definition["cases"]:
            outputs.append(str(run_case(client, workspace_id, name, definition["answer_mode"], case, source_ids,
                                        args.timeout_seconds, args.poll_seconds).relative_to(ROOT)))
    print(json.dumps({"workspace_id": workspace_id, "run_files": outputs}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvalRunError, KeyError, ValueError) as error:
        sys.stderr.write(f"run_retrieval_eval failed: {error}\n")
        raise SystemExit(2)
