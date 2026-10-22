"""为 research-quality-v1 的运行记录打分并生成汇总报告。

结构化指标（有效单元格完成率、检索效率、实体覆盖）由脚本从原始记录计算；
内容质量由 LLM 评分给出五个维度的 0 到 1 分和是否通过；每份报告默认评三次取多数，分数取平均，
评分按输入缓存在 reports/judge-cache.json，报告没变时沿用。
评分模型通过 NOTEWEAVE_EVAL_JUDGE_BASE_URL、NOTEWEAVE_EVAL_JUDGE_API_KEY、NOTEWEAVE_EVAL_JUDGE_MODEL 配置，
未配置或调用失败时评分记为 NOT_RUN，不计入通过。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
EVAL_DIR = ROOT / "experiments/deep-research/quality-eval"
DATASET = EVAL_DIR / "dataset.json"
RUNS_DIR = EVAL_DIR / "runs"
REPORTS_DIR = EVAL_DIR / "reports"
JUDGE_CACHE = REPORTS_DIR / "judge-cache.json"

DIMENSIONS = ("factual_accuracy", "citation_accuracy", "completeness", "source_quality", "tool_efficiency")
# 通过线：评分模型判定通过，且事实准确与引用准确都不低于该值
PASS_FLOOR = 0.7
HIGH_RISK_MIN_SOURCES = 2
REPORT_CHAR_LIMIT = 24000

JudgeCall = Callable[[list[dict[str, str]]], str]

RUBRIC = """你是研究报告评审。只依据给出的报告、引用列表和你确信的公开知识评分，每个维度 0 到 1 分：
- factual_accuracy：结论是否正确，错误或过时的事实按严重程度扣分；
- citation_accuracy：结论与所附引用原文是否一致，引用无法支撑结论时扣分；
- completeness：must_cover 中每一项是否被实质回答；
- source_quality：是否优先使用官方文档、规范、论文等一手来源；
- tool_efficiency：结合检索次数与有效结论数，检索是否有明显浪费。
证据不足时如实说明缺口不扣分，编造结论严重扣分。冲突类问题要求保留双方证据。
只输出一个 JSON 对象，结构为：
{"scores": {"<维度>": {"score": <0 到 1>, "rationale": "<理由>"}},
 "must_cover": [{"item": "<要点>", "covered": <true 或 false>}],
 "pass": <true 或 false>,
 "summary": "<一句话总结>"}"""


class JudgeOutputError(ValueError):
    pass


def resource(record: dict[str, Any], name: str) -> Any:
    """读取附属接口数据；接口不可用时返回 None。"""
    captured = record.get("resources", {}).get(name) or {}
    return captured.get("data") if captured.get("available") else None


def structural_metrics(case: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    detail = record.get("run_detail") or {}
    plan = resource(record, "plan") or {}
    cells = resource(record, "cells")
    checks = resource(record, "counter-checks") or []
    high_risk = {column.get("label") for column in plan.get("columns", []) if column.get("high_risk")}
    timeline = (detail.get("research_process_summary") or {}).get("search_read_timeline") or {}
    queries = timeline.get("all_search_queries")
    metrics: dict[str, Any] = {
        "terminal_status": detail.get("status"),
        "completion_terminal_state": detail.get("completion_terminal_state"),
        "wall_seconds": record.get("wall_seconds"),
        "search_query_count": len(queries) if isinstance(queries, list) else None,
        "counter_check_not_run": sum(1 for check in checks if check.get("verdict") == "NOT_RUN"),
        "counter_check_refuted": sum(1 for check in checks if check.get("verdict") == "REFUTED"),
    }
    requirements = resource(record,"requirements")
    metrics["requirements_available"] = isinstance(requirements,dict) and requirements.get("enabled") is True
    if metrics["requirements_available"]:
        metrics["requirement_coverage"] = {key: requirements.get(key) for key in (
            "required_count","satisfied_count","explicit_count","explicit_satisfied_count","derived_count",
            "derived_satisfied_count","distinct_fact_count","explicit_definitions_complete")}
        if requirements.get("requirement_answer_policy_version") == 1:
            metrics["requirement_coverage"].update({key: requirements.get(key) for key in (
                "requirement_answer_policy_version", "optional_count", "optional_satisfied_count")})
        metrics["report_requirement_coverage"] = {key: (requirements.get("report_coverage") or {}).get(key)
            for key in ("required_count","answered_count","complete")}
        runtime = requirements.get("runtime_metrics") or {}
        rounds = runtime.get("rounds") or []
        unsubmitted_calls = runtime.get("unsubmitted_synthesis_authorizations",0)
        docs = sum(row.get("document_count",0) for row in rounds)
        duplicated = sum(row.get("duplicate_document_count",0) for row in rounds)
        metrics["completion_cost"] = {
            "llm_calls": sum(row.get("llm_calls",0) for row in rounds) + unsubmitted_calls,
            "input_tokens": sum(row["input_tokens"] for row in rounds) if rounds and not unsubmitted_calls and all(row.get("input_tokens") is not None for row in rounds) else None,
            "output_tokens": sum(row["output_tokens"] for row in rounds) if rounds and not unsubmitted_calls and all(row.get("output_tokens") is not None for row in rounds) else None,
            "llm_calls_per_new_requirement": runtime.get("llm_calls_per_new_requirement"),
            "duplicate_document_rate": round(duplicated/docs,4) if docs else None,
            "unresolved_conflicts": runtime.get("unresolved_conflicts"),"stop_reason":runtime.get("stop_reason"),
        }
    expected = case.get("expected_entities") or []
    if expected:
        labels = [str(row.get("label") or row.get("entity_key") or "").lower() for row in plan.get("rows", [])]
        covered = [name for name in expected
                   if any(name.lower() in label or label in name.lower() for label in labels if label)]
        metrics["expected_entity_coverage"] = round(len(covered) / len(expected), 4)
    if cells is None:
        metrics["cells_available"] = False
        return metrics
    effective = [cell for cell in cells if is_effective_cell(cell, high_risk)]
    query_count = metrics["search_query_count"]
    metrics.update({
        "cells_available": True,
        "planned_cells": len(cells),
        "effective_cells": len(effective),
        "effective_cell_rate": round(len(effective) / len(cells), 4) if cells else None,
        "status_counts": count_by(cells, "cell_status"),
        "queries_per_effective_cell": round(query_count / len(effective), 2) if effective and query_count is not None else None,
    })
    return metrics


def is_effective_cell(cell: dict[str, Any], high_risk_columns: set[str]) -> bool:
    """有效单元格：已验证、至少一条可回溯的结论；高风险列还要求两个独立来源。"""
    if cell.get("cell_status") != "VERIFIED" or not cell.get("equivalent_claims"):
        return False
    if cell.get("column_label") in high_risk_columns:
        return int(cell.get("independent_source_count") or 0) >= HIGH_RISK_MIN_SOURCES
    return True


def count_by(items: list[dict[str, Any]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in items:
        value = str(item.get(key))
        counts[value] = counts.get(value, 0) + 1
    return counts


def judge_messages(case: dict[str, Any], record: dict[str, Any], metrics: dict[str, Any]) -> list[dict[str, str]]:
    detail = record.get("run_detail") or {}
    citations = [
        {"cell": f"{cell.get('entity_label')} / {cell.get('column_label')}",
         "claim": claim.get("claim_text"), "domain": claim.get("source_domain")}
        for cell in resource(record, "cells") or []
        for claim in cell.get("equivalent_claims") or []
    ]
    payload = {
        "question": case["question"],
        "question_type": case.get("question_type"),
        "must_cover": case.get("must_cover", []),
        "process_metrics": {key: metrics.get(key) for key in (
            "search_query_count", "planned_cells", "effective_cells", "completion_terminal_state")},
        "citations": citations,
        "report_markdown": str(detail.get("final_report_markdown") or "")[:REPORT_CHAR_LIMIT],
    }
    return [
        {"role": "system", "content": RUBRIC},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]


def parse_judge_output(text: str, must_cover: list[str]) -> dict[str, Any]:
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise JudgeOutputError("输出中没有 JSON 对象")
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError as error:
        raise JudgeOutputError(f"JSON 解析失败：{error}") from error
    scores = data.get("scores")
    if not isinstance(scores, dict):
        raise JudgeOutputError("缺少 scores 对象")
    normalized: dict[str, float] = {}
    rationales: dict[str, str] = {}
    for dimension in DIMENSIONS:
        entry = scores.get(dimension)
        score = entry.get("score") if isinstance(entry, dict) else entry
        if not isinstance(score, (int, float)) or isinstance(score, bool) or not 0 <= score <= 1:
            raise JudgeOutputError(f"{dimension} 的分数必须是 0 到 1 的数字")
        normalized[dimension] = float(score)
        rationales[dimension] = str(entry.get("rationale", "")) if isinstance(entry, dict) else ""
    if not isinstance(data.get("pass"), bool):
        raise JudgeOutputError("pass 必须是布尔值")
    covered = data.get("must_cover")
    if not isinstance(covered, list) or len(covered) != len(must_cover):
        raise JudgeOutputError(f"must_cover 必须逐项返回 {len(must_cover)} 条")
    return {
        "status": "SCORED",
        "scores": normalized,
        "rationales": rationales,
        "must_cover": covered,
        "judge_pass": data["pass"],
        "summary": str(data.get("summary", "")),
    }


def run_judge(messages: list[dict[str, str]], must_cover: list[str], call: JudgeCall | None) -> dict[str, Any]:
    """调用评分模型；输出不合规时带着错误信息重试一次，仍失败则记为 NOT_RUN。"""
    if call is None:
        return {"status": "NOT_RUN", "reason": "JUDGE_NOT_CONFIGURED"}
    attempt = list(messages)
    error_text = ""
    for _ in range(2):
        try:
            return parse_judge_output(call(attempt), must_cover)
        except JudgeOutputError as error:
            error_text = str(error)
            attempt = messages + [{"role": "user", "content": f"上一次输出不合规：{error_text}。请只输出符合要求的 JSON。"}]
        except (urllib.error.URLError, TimeoutError, OSError, KeyError) as error:
            return {"status": "NOT_RUN", "reason": "JUDGE_CALL_FAILED", "detail": str(error)[:300]}
    return {"status": "NOT_RUN", "reason": "JUDGE_OUTPUT_INVALID", "detail": error_text}


def passed(judge: dict[str, Any]) -> bool | None:
    if judge.get("status") != "SCORED":
        return None
    scores = judge["scores"]
    return (bool(judge["judge_pass"])
            and scores["factual_accuracy"] >= PASS_FLOOR
            and scores["citation_accuracy"] >= PASS_FLOOR)


def openai_compatible_judge() -> JudgeCall | None:
    base_url = os.environ.get("NOTEWEAVE_EVAL_JUDGE_BASE_URL", "").rstrip("/")
    api_key = os.environ.get("NOTEWEAVE_EVAL_JUDGE_API_KEY", "")
    model = os.environ.get("NOTEWEAVE_EVAL_JUDGE_MODEL", "")
    if not base_url or not api_key or not model:
        return None

    def call(messages: list[dict[str, str]]) -> str:
        body = json.dumps({"model": model, "messages": messages, "temperature": 0}).encode("utf-8")
        request = urllib.request.Request(f"{base_url}/chat/completions", data=body, method="POST", headers={
            "Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
        with urllib.request.urlopen(request, timeout=180) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return str(payload["choices"][0]["message"]["content"])

    return call


def configure_judge_from_chat(env: dict[str, str]) -> None:
    """仅在明确选择时沿用聊天配置，凭据只留在当前进程。"""
    endpoint = env.get("NOTEWEAVE_LLM_ENDPOINT", "").strip().rstrip("/")
    model = env.get("NOTEWEAVE_LLM_MODEL", "").strip()
    key = env.get("NOTEWEAVE_LLM_API_KEY", "").strip()
    if not endpoint or not model or not key:
        raise ValueError("聊天评分需要完整的聊天端点、模型和凭据")
    if endpoint.endswith("/chat/completions"):
        endpoint = endpoint.removesuffix("/chat/completions")
    from urllib.parse import urlsplit
    parsed = urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("聊天模型端点不符合评分接口要求")
    env.update(NOTEWEAVE_EVAL_JUDGE_BASE_URL=endpoint, NOTEWEAVE_EVAL_JUDGE_MODEL=model,
               NOTEWEAVE_EVAL_JUDGE_API_KEY=key, NOTEWEAVE_EVAL_JUDGE_CONFIG_SOURCE="CHAT_MODEL")


def score_case(case: dict[str, Any], record: dict[str, Any], call: JudgeCall | None, *,
               samples: int = 1, cache: dict[str, list[dict[str, Any]]] | None = None) -> dict[str, Any]:
    """评分模型在 temperature 0 下对同一份报告也会给出不同结论（2026-10-20 同一批 16 份未变的报告重评时
    5 份翻转），所以每份报告评 samples 次取多数，并按评分输入缓存：报告没变就沿用已有的评分，
    只重跑几题时其他题的结论不会因重评而波动。"""
    if samples < 1:
        raise ValueError("评分次数必须大于零")
    metrics = structural_metrics(case, record)
    messages = judge_messages(case, record, metrics)
    key = hashlib.sha256(json.dumps([os.environ.get("NOTEWEAVE_EVAL_JUDGE_MODEL", ""),
                                    os.environ.get("NOTEWEAVE_EVAL_JUDGE_BASE_URL", "").rstrip("/"), messages],
                                    ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
    judges = list((cache or {}).get(key, []))[:samples] if call is not None else []
    failed: dict[str, Any] | None = None
    while len(judges) < samples:
        judge = run_judge(messages, case.get("must_cover", []), call)
        if judge.get("status") != "SCORED":
            failed = judge
            break
        judges.append(judge)
    if cache is not None and judges:
        cache[key] = judges
    result = {"case_key": case["case_key"], "question_type": case.get("question_type"), "run_id": record.get("run_id"),
              "metrics": metrics}
    if not judges:
        return {**result, "judge": failed, "passed": passed(failed)}
    if len(judges) < samples:
        incomplete = {**(failed or {}), "status": "NOT_RUN", "samples": len(judges),
                      "required_samples": samples, "reason": "JUDGE_SAMPLES_INCOMPLETE"}
        return {**result, "judge": incomplete, "passed": None}
    votes = [bool(passed(judge)) for judge in judges]
    majority = sum(votes) * 2 > len(votes)
    representative = next(judge for judge, vote in zip(judges, votes) if vote == majority) \
        if majority in votes else judges[0]
    combined = {**representative,
                "scores": {dim: round(sum(judge["scores"][dim] for judge in judges) / len(judges), 4)
                           for dim in DIMENSIONS},
                "samples": len(judges), "pass_votes": sum(votes)}
    return {**result, "judge": combined, "passed": majority}


def mean(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 4) if values else None


def aggregate(results: list[dict[str, Any]], dataset: dict[str, Any]) -> dict[str, Any]:
    scored = [result for result in results if result["judge"].get("status") == "SCORED"]
    with_cells = [result["metrics"] for result in results if result["metrics"].get("cells_available")]
    planned = sum(metrics["planned_cells"] for metrics in with_cells)
    effective = sum(metrics["effective_cells"] for metrics in with_cells)
    by_type: dict[str, dict[str, Any]] = {}
    for question_type in dataset.get("question_types", {}):
        group = [result for result in scored if result["question_type"] == question_type]
        by_type[question_type] = {
            "scored": len(group),
            "pass_rate": mean([1.0 if result["passed"] else 0.0 for result in group]),
            **{dim: mean([result["judge"]["scores"][dim] for result in group]) for dim in DIMENSIONS},
        }
    return {
        "dataset_key": dataset["dataset_key"],
        "dataset_version": dataset["dataset_version"],
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cases_total": len(dataset["cases"]),
        "runs_found": len(results),
        "judge_scored": len(scored),
        "judge_not_run": len(results) - len(scored),
        "pass_rate": mean([1.0 if result["passed"] else 0.0 for result in scored]),
        "dimension_means": {dim: mean([result["judge"]["scores"][dim] for result in scored]) for dim in DIMENSIONS},
        # 微平均：所有 Run 的有效单元格之和除以规划单元格之和
        "effective_cell_rate": round(effective / planned, 4) if planned else None,
        "runs_without_cells": len(results) - len(with_cells),
        "by_question_type": by_type,
        "results": results,
    }


def render_markdown(summary: dict[str, Any]) -> str:
    def fmt(value: Any) -> str:
        if value is None:
            return "-"
        return f"{value:.2f}" if isinstance(value, float) else str(value)

    lines = [
        f"# {summary['dataset_key']} 评测报告",
        "",
        f"生成时间 {summary['generated_at']}；数据集版本 {summary['dataset_version']}；"
        f"运行记录 {summary['runs_found']}/{summary['cases_total']}；"
        f"已评分 {summary['judge_scored']}，未评分 {summary['judge_not_run']}。",
        "",
        f"- 模型评分通过率：{fmt(summary['pass_rate'])}（不代表全部需求已完成）",
        f"- 有效单元格完成率：{fmt(summary['effective_cell_rate'])}（{summary['runs_without_cells']} 个 Run 缺少单元格数据）",
        "",
        "| 题型 | 已评分 | 通过率 | " + " | ".join(DIMENSIONS) + " |",
        "|---|---|---|" + "---|" * len(DIMENSIONS),
    ]
    for question_type, row in summary["by_question_type"].items():
        lines.append(f"| {question_type} | {row['scored']} | {fmt(row['pass_rate'])} | "
                     + " | ".join(fmt(row[dim]) for dim in DIMENSIONS) + " |")
    lines += ["", "## 逐题结果", "", "| 用例 | 模型评分通过 | 交付状态 | 需求覆盖 | 报告覆盖 | 有效单元格 | 检索次数 | 评分状态 |", "|---|---|---|---|---|---|---|---|"]
    for result in summary["results"]:
        metrics = result["metrics"]
        cells = f"{metrics['effective_cells']}/{metrics['planned_cells']}" if metrics.get("cells_available") else "-"
        judge = result["judge"]
        status = judge.get("status") + (f" {judge['reason']}" if judge.get("reason") else "")
        coverage = metrics.get("requirement_coverage") or {}
        report = metrics.get("report_requirement_coverage") or {}
        required = f"{coverage['satisfied_count']}/{coverage['required_count']}" if coverage.get("required_count") is not None else "-"
        answered = f"{report['answered_count']}/{report['required_count']}" if report.get("required_count") is not None else "-"
        lines.append(f"| {result['case_key']} | {fmt(result['passed'])} | {metrics.get('completion_terminal_state') or '-'} | {required} | {answered} | {cells} | "
                     f"{fmt(metrics.get('search_query_count'))} | {status} |")
    for result in summary["results"]:
        coverage = result["metrics"].get("requirement_coverage") or {}
        if coverage.get("requirement_answer_policy_version") == 1:
            lines += ["", f"{result['case_key']} 使用答案策略版本 1，需求与报告覆盖按必答项计数；"
                      f"另有补充项覆盖 {coverage.get('optional_satisfied_count', 0)}/{coverage.get('optional_count', 0)}。"
                      "计数规则与旧版本不同，不能直接按比例比较完成率。"]
    if "runtime_comparable" in summary and not summary["runtime_comparable"]:
        lines += ["", "本轮不满足完整冻结运行的对照条件；接回采集、配置身份缺失或运行中断等情况需结合运行清单核查。"]
    identity = summary.get("judge_identity")
    if identity:
        lines += ["", "## 评分配置", "", f"评分模型：{identity['model']}。每题评分次数：{identity['samples']}。"]
        if identity.get("configuration_source") == "CHAT_MODEL":
            lines.append("评分沿用聊天模型配置。")
        if identity.get("independent_by_model_id"):
            lines.append("评分模型标识与已记录的研究角色模型不同。")
        elif identity.get("research_models"):
            lines.append("评分与研究使用相同模型，仅用于开发对照，不作为独立评审结论。")
        else:
            lines.append("缺少研究模型身份记录，无法确认评分独立性。")
    return "\n".join(lines) + "\n"


def load_run_records(dataset: dict[str, Any], runs_dir: Path) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """在调用评分模型前检查题目和数据集版本，防止旧结果混入当前基线。"""
    records = []
    for case in dataset["cases"]:
        path = runs_dir / f"{case['case_key']}.json"
        if not path.exists():
            continue
        record = json.loads(path.read_text(encoding="utf-8"))
        if record.get("dataset_version") != dataset["dataset_version"] or record.get("case_key") != case["case_key"]:
            raise ValueError(f"评测记录的题目或数据集版本不一致，请使用同一轮的独立结果目录：{path}")
        records.append((case, record))
    return records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs-dir", type=Path, default=RUNS_DIR)
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--holdout-manifest", type=Path)
    parser.add_argument("--no-judge", action="store_true", help="只计算结构化指标")
    parser.add_argument("--judge-from-chat", action="store_true", help="明确沿用当前进程中的聊天模型端点与凭据")
    parser.add_argument("--judge-samples", type=int, default=3, help="每份报告评分次数，按多数决定是否通过")
    parser.add_argument("--judge-cache", type=Path, default=JUDGE_CACHE,
                        help="评分缓存；报告与评分输入不变时沿用已有评分")
    args = parser.parse_args(argv)
    if args.judge_from_chat:
        configure_judge_from_chat(os.environ)
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    if args.holdout_manifest:
        from holdout_manifest import verify
        verify(args.dataset, args.holdout_manifest, development=DATASET)
    records = load_run_records(dataset, args.runs_dir)
    call = None if args.no_judge else openai_compatible_judge()
    manifest_path = args.runs_dir / "metadata" / "manifest.json"
    run_manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    research_models = {value for key,value in run_manifest.get("models", {}).items() if key != "NOTEWEAVE_EVAL_JUDGE_MODEL"}
    for container in run_manifest.get("runtime_identity", {}).get("containers", []):
        research_models.update(value for key,value in container.get("public_config", {}).items() if key in {"NOTEWEAVE_LLM_MODEL","NOTEWEAVE_RESEARCH_LLM_MODEL"})
        research_models.update(options["model"] for options in container.get("role_options", {}).values() if isinstance(options,dict) and options.get("model"))
    research_models.update(options["model"] for options in run_manifest.get("role_options", {}).values() if isinstance(options,dict) and options.get("model"))
    judge_model = os.environ.get("NOTEWEAVE_EVAL_JUDGE_MODEL", "")
    independent = bool(judge_model and research_models and judge_model not in research_models)
    if args.holdout_manifest and (call is None or args.judge_samples < 3 or not independent):
        raise ValueError("留出集评分需要独立配置且与研究模型不同的评分模型，并取得至少三次有效评分")
    cache = json.loads(args.judge_cache.read_text(encoding="utf-8")) if args.judge_cache.exists() else {}
    results = []
    for case, record in records:
        results.append(score_case(case, record, call, samples=max(1, args.judge_samples), cache=cache))
    summary = aggregate(results, dataset)
    summary["runs_dir"] = str(args.runs_dir)
    summary["judge_identity"] = {"model": judge_model, "independent_by_model_id": independent,
        "configuration_source": os.environ.get("NOTEWEAVE_EVAL_JUDGE_CONFIG_SOURCE", "EVAL_JUDGE"),
        "research_models": sorted(research_models), "temperature": 0, "samples": args.judge_samples,
        "endpoint_sha256": hashlib.sha256(os.environ.get("NOTEWEAVE_EVAL_JUDGE_BASE_URL", "").rstrip("/").encode()).hexdigest()}
    summary["run_manifest_state"] = run_manifest.get("state", "UNAVAILABLE")
    summary["runtime_comparable"] = run_manifest.get("state") == "COMPLETED" and run_manifest.get("runtime_comparable", False)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    args.judge_cache.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    (REPORTS_DIR / f"quality-{stamp}.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (REPORTS_DIR / f"quality-{stamp}.md").write_text(render_markdown(summary), encoding="utf-8")
    print(json.dumps({key: summary[key] for key in ("runs_found", "judge_scored", "pass_rate", "effective_cell_rate")},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
