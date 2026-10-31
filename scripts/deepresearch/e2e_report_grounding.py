"""端到端验证：在问答对话里发起深度研究，完成后依据研究报告追问（REPORT 检索通道）。

真实调用研究与回答模型。凭据从环境变量读取（与评测脚本相同，经 run_with_env.py 注入），不打印。
用法：python3 run_with_env.py scripts/deepresearch/e2e_report_grounding.py --base-url http://127.0.0.1:8081 --out <结果 JSON>
可用 --attach <workspace_id>:<conversation_id>:<research_run_id> 接上已完成的研究，只验证追问部分。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_minimal_eval import ApiClient, EvalRunError, authenticate, wait_for_terminal  # noqa: E402

QUESTION = "Java 21 的虚拟线程在什么情况下会被固定（pinning）在载体线程上？这会带来什么影响？"
FOLLOW_UP = "按这份报告，写代码时怎样避免虚拟线程被固定？"
ANSWER_TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}


def submit(client: ApiClient, conversation_id: str, content: str, mode: str, **retrieval: object) -> dict:
    body: dict[str, object] = {"content": content, "answer_mode": mode,
                               "client_request_id": f"e2e-{mode.lower()}-{time.time_ns()}"}
    body.update(retrieval)
    return client.request("POST", f"/api/v2/conversations/{conversation_id}/messages", body)


def expect_error(action, code: str) -> str:
    try:
        action()
    except EvalRunError as error:
        if code not in str(error):
            raise
        return code
    raise AssertionError(f"期望 {code}，请求却成功了")


def wait_answer(client: ApiClient, workspace_id: str, run_id: str, timeout: int = 600) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        run = client.request("GET", f"/api/v2/workspaces/{workspace_id}/answer-runs/{run_id}")
        if run.get("status") in ANSWER_TERMINAL:
            return run
        if time.monotonic() >= deadline:
            raise AssertionError(f"回答 {run_id} 在 {timeout} 秒内没有结束，状态 {run.get('status')}")
        time.sleep(3)


def messages(client: ApiClient, workspace_id: str, conversation_id: str) -> list[dict]:
    return client.request("GET", f"/api/v2/workspaces/{workspace_id}/conversations/{conversation_id}/messages?limit=200")


def follow_up(client: ApiClient, workspace_id: str, conversation_id: str, label: str, **retrieval: object) -> dict:
    receipt = submit(client, conversation_id, FOLLOW_UP, "QA", **retrieval)
    run = wait_answer(client, workspace_id, str(receipt["answer_run_id"]))
    manifest = client.request("GET", f"/api/v2/workspaces/{workspace_id}/answer-runs/{receipt['answer_run_id']}/evidence")
    assistant = next(m for m in messages(client, workspace_id, conversation_id)
                     if m.get("message_id") == receipt["assistant_message_id"])
    evidence = manifest.get("evidence") or []
    result = {
        "label": label,
        "answer_run_id": receipt["answer_run_id"],
        "assistant_message_id": receipt["assistant_message_id"],
        "status": run.get("status"),
        "error_code": run.get("error_code"),
        "plan_version": run.get("retrieval_plan_version"),
        "degradation": run.get("retrieval_degradation_reasons"),
        "sufficiency": manifest.get("evidence_sufficiency"),
        "evidence_kinds": sorted({str(item.get("kind")) for item in evidence}),
        "evidence": [{"id": item.get("evidence_id"), "title": item.get("title"), "location": item.get("location")}
                     for item in evidence],
        "citations": assistant.get("citations") or [],
        "content": run.get("content") or assistant.get("content"),
    }
    print(f"[{label}] {result['status']} 证据 {len(evidence)} 条 {result['evidence_kinds']} "
          f"引用 {len(result['citations'])} 条 计划 {result['plan_version']}", flush=True)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--attach", default="")
    parser.add_argument("--timeout-seconds", type=int, default=3600)
    args = parser.parse_args()
    client = ApiClient(args.base_url)
    authenticate(client, os.environ["NOTEWEAVE_EVAL_USERNAME"], os.environ["NOTEWEAVE_EVAL_EMAIL"],
                 os.environ["NOTEWEAVE_EVAL_PASSWORD"])
    report: dict[str, object] = {"question": QUESTION, "follow_up": FOLLOW_UP}

    if args.attach:
        workspace_id, conversation_id, research_run_id = args.attach.split(":")
    else:
        workspace_id = str(client.request("POST", "/api/v2/workspaces", {
            "name": f"e2e-report-grounding-{time.strftime('%m%d-%H%M%S')}",
            "description": "端到端验证：深度研究后依据报告追问"})["workspace_id"])
        conversation_id = str(client.request("POST", f"/api/v2/workspaces/{workspace_id}/conversations", {
            "title": "虚拟线程固定问题", "conversation_type": "WORKSPACE_CHAT"})["conversation_id"])
        # 研究依赖对话里的工作台；工作台没有资料时应当仍能联网研究
        receipt = submit(client, conversation_id, QUESTION, "DEEP_RESEARCH")
        research_run_id = str(receipt["research_run_id"])
        print(f"研究已发起 workspace={workspace_id} conversation={conversation_id} run={research_run_id}", flush=True)
        started = time.monotonic()
        detail = wait_for_terminal(client, workspace_id, research_run_id, args.timeout_seconds, 15)
        report["research_seconds"] = round(time.monotonic() - started)
        print(f"研究结束 {detail.get('status')} {detail.get('completion_terminal_state')} "
              f"用时 {report['research_seconds']} 秒", flush=True)
    report.update({"workspace_id": workspace_id, "conversation_id": conversation_id, "research_run_id": research_run_id})
    detail = client.request("GET", f"/api/v2/workspaces/{workspace_id}/research-runs/{research_run_id}")
    report["research_status"] = detail.get("status")
    report["research_terminal_state"] = detail.get("completion_terminal_state")
    report["report_title"] = detail.get("final_report_title")
    research_message = next(m for m in messages(client, workspace_id, conversation_id)
                            if m.get("research_run_id") == research_run_id and m.get("role") == "ASSISTANT")
    report["research_message"] = {"message_id": research_message.get("message_id"),
                                  "context_status": research_message.get("context_status"),
                                  "content_head": str(research_message.get("content"))[:200]}
    assert detail.get("status") == "COMPLETED", f"研究没有完成：{detail.get('status')}"
    assert str(research_message.get("content")).startswith("研究报告已完成"), "报告没有写回对话"

    results = [follow_up(client, workspace_id, conversation_id, "显式指定研究报告",
                         retrieval_strategy="AUTO", retrieval_channels=["REPORT"], grounding_refs=[research_run_id]),
               follow_up(client, workspace_id, conversation_id, "自动取对话里的研究",
                         retrieval_strategy="AUTO", retrieval_channels=["REPORT"]),
               follow_up(client, workspace_id, conversation_id, "研究报告加工作台资料",
                         retrieval_strategy="AUTO", retrieval_channels=["WORKSPACE", "REPORT"],
                         grounding_refs=[research_run_id])]
    report["follow_ups"] = results
    first = results[0]
    assert first["status"] == "COMPLETED", f"追问没有完成：{first['status']} {first['error_code']}"
    assert first["evidence_kinds"] == ["RESEARCH_EVIDENCE"], first["evidence_kinds"]
    assert all(str(item["location"]).startswith("http") for item in first["evidence"]), "研究证据缺少网页地址"
    assert len(first["citations"]) == len(first["evidence"]), "引用条数与证据条数不一致"
    assert str(first["plan_version"]).endswith("+research-report-v1"), first["plan_version"]

    note = client.request("POST", f"/api/v2/workspaces/{workspace_id}/knowledge-items", {
        "item_type": "NOTE", "title": "避免虚拟线程固定", "content": str(first["content"]),
        "source_message_id": first["assistant_message_id"]})
    report["note_item_id"] = note.get("item_id")
    print(f"回答已保存为笔记 {note.get('item_id')}", flush=True)

    report["rejections"] = {
        "foreign_ref": expect_error(lambda: submit(client, conversation_id, FOLLOW_UP, "QA", retrieval_strategy="AUTO",
                                                   retrieval_channels=["REPORT"], grounding_refs=["0" * 36]),
                                    "RETRIEVAL_GROUNDING_REF_INVALID"),
        "note_mode": expect_error(lambda: submit(client, conversation_id, FOLLOW_UP, "NOTE", retrieval_strategy="AUTO",
                                                 retrieval_channels=["REPORT"], grounding_refs=[research_run_id]),
                                  "RETRIEVAL_CHANNEL_UNSUPPORTED"),
    }
    client.request("DELETE", f"/api/v2/workspaces/{workspace_id}/conversations/{conversation_id}"
                             f"/messages/{research_message['message_id']}")
    report["rejections"]["after_research_turn_deleted"] = expect_error(
        lambda: submit(client, conversation_id, FOLLOW_UP, "QA", retrieval_strategy="AUTO", retrieval_channels=["REPORT"]),
        "RESEARCH_REPORT_NOT_FOUND")
    print(f"拒绝场景 {report['rejections']}", flush=True)

    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"E2E_OK {args.out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
