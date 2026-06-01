#!/usr/bin/env python3
"""Small benchmark adapters for NoteWeave.

The script is intentionally stdlib-only so it can run in a clean repo checkout.
It normalizes external benchmark rows into a shared JSONL shape and can export
team RAG cases into the current NoteWeave admin API request format.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import random
import statistics
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / "benchmarks" / "registry.json"
DEFAULT_CONFIG_PATH = ROOT / "configs" / "sampling.default.json"
RUN_ROUNDS_PATH = ROOT / "configs" / "run_rounds.json"
BASELINES_PATH = ROOT / "configs" / "baselines.json"
METRICS_PATH = ROOT / "configs" / "metrics.json"


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def dump_json(data: Any, path: Path | None) -> None:
    text = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True)
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


def iter_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                item = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSONL row: {exc}") from exc
            if not isinstance(item, dict):
                raise SystemExit(f"{path}:{line_no}: expected object row")
            yield item


def write_jsonl(rows: Iterable[dict[str, Any]], path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
            count += 1
    return count


def stable_id(prefix: str, *parts: Any) -> str:
    raw = "|".join("" if part is None else str(part) for part in parts)
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]
    return f"{prefix}:{digest}"


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, tuple):
        return list(value)
    return [value]


def first_present(row: dict[str, Any], keys: list[str], default: Any = None) -> Any:
    for key in keys:
        if key in row and row[key] not in (None, ""):
            return row[key]
    return default


def stringify(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def stratified_sample(
    rows: list[dict[str, Any]],
    ratio: float,
    max_cases: int | None,
    seed: int,
    stratify_fields: list[str],
) -> list[dict[str, Any]]:
    if ratio >= 1.0 and (max_cases is None or len(rows) <= max_cases):
        return list(rows)

    groups: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        key_parts = []
        for field in stratify_fields:
            value = row.get(field)
            if isinstance(value, list):
                value = ",".join(sorted(str(v) for v in value))
            key_parts.append(str(value if value not in (None, "") else "unknown"))
        groups["|".join(key_parts)].append(row)

    rng = random.Random(seed)
    selected: list[dict[str, Any]] = []
    for key in sorted(groups):
        group = list(groups[key])
        rng.shuffle(group)
        take = max(1, math.ceil(len(group) * ratio))
        selected.extend(group[:take])

    rng.shuffle(selected)
    if max_cases is not None:
        selected = selected[:max_cases]
    return sorted(selected, key=lambda item: json.dumps(item, ensure_ascii=False, sort_keys=True))


def enterprise_to_normalized(row: dict[str, Any]) -> dict[str, Any]:
    question_id = first_present(row, ["question_id", "id", "qid"])
    question = first_present(row, ["question", "query", "query_text"])
    if not question:
        raise ValueError("EnterpriseRAG row missing question/query")

    answer = first_present(row, ["answer", "gold_answer", "expected_answer", "reference_answer"])
    question_type = first_present(row, ["question_type", "type", "category"], "unknown")
    category = first_present(row, ["category", "question_category"], question_type)
    source_types = [str(value) for value in as_list(first_present(row, ["source_types", "sources", "source"], []))]
    document_ids = as_list(first_present(row, ["document_ids", "expected_doc_ids", "gold_document_ids", "expected_document_ids"], []))
    answer_facts = [str(value) for value in as_list(first_present(row, ["answer_facts", "facts"], []))]
    if not answer_facts and isinstance(answer, str) and answer:
        answer_facts = [answer]

    expected_sources = []
    for document_id in document_ids:
        expected_sources.append(
            {
                "external_doc_id": str(document_id),
                "source_type": source_types[0] if len(source_types) == 1 else None,
                "document_id": None,
                "chunk_id": None,
                "wiki_page_id": None,
                "citation_id": None,
            }
        )

    return {
        "case_id": question_id or stable_id("enterprise_rag_bench", question, answer),
        "suite": "enterprise_rag_bench",
        "lane": "team",
        "scenario": "team_knowledge_rag",
        "category": str(category),
        "conversation": [{"role": "user", "content": str(question)}],
        "query": str(question),
        "expected_answer": stringify(answer),
        "expected_sources": expected_sources,
        "answer_facts": answer_facts,
        "tags": {
            "question_type": str(question_type),
            "source_types": source_types,
            "original_question_id": question_id,
            "has_expected_sources": bool(expected_sources),
        },
        "raw": row,
    }


def command_plan(args: argparse.Namespace) -> None:
    registry = load_json(args.registry)
    config = load_json(args.config)
    seed = int(config.get("seed", 0))
    overrides = config.get("suite_overrides", {})
    rows = []
    for bench in registry.get("benchmarks", []):
        suite_id = bench["id"]
        policy = dict(bench.get("recommended_sample", {}))
        policy.update(overrides.get(suite_id, {}))
        total = bench.get("source_total_cases")
        ratio = float(policy.get("ratio", 0.05))
        max_cases = policy.get("max_cases")
        planned = None
        if isinstance(total, int):
            planned = max(1, math.ceil(total * ratio))
            if isinstance(max_cases, int):
                planned = min(planned, max_cases)
        rows.append(
            {
                "suite": suite_id,
                "priority": bench.get("priority"),
                "lane": bench.get("lane"),
                "fit_score": bench.get("fit_score"),
                "total_cases": total,
                "sample_ratio": ratio,
                "max_cases": max_cases,
                "planned_cases": planned,
                "stratify_by": policy.get("stratify_by", []),
                "seed": seed,
            }
        )
    dump_json({"snapshot_date": registry.get("snapshot_date"), "plan": rows}, args.output)


def command_normalize_enterprise_rag(args: argparse.Namespace) -> None:
    rows = list(iter_jsonl(args.input))
    sampled = stratified_sample(
        rows,
        ratio=args.ratio,
        max_cases=args.max_cases,
        seed=args.seed,
        stratify_fields=args.stratify_by,
    )
    normalized = []
    errors = []
    for row in sampled:
        try:
            normalized.append(enterprise_to_normalized(row))
        except Exception as exc:  # noqa: BLE001 - CLI should report bad rows without a traceback.
            errors.append({"row": row, "error": str(exc)})
    count = write_jsonl(normalized, args.output)
    report = {"input_rows": len(rows), "sampled_rows": len(sampled), "written_rows": count, "errors": errors}
    dump_json(report, args.report)


def load_doc_id_map(path: Path | None) -> dict[str, int]:
    if not path:
        return {}
    mapping = load_json(path)
    if not isinstance(mapping, dict):
        raise SystemExit("--doc-id-map must be a JSON object of external id -> NoteWeave documentId")
    return {str(key): int(value) for key, value in mapping.items()}


def to_noteweave_expected_sources(case: dict[str, Any], doc_map: dict[str, int]) -> list[dict[str, Any]]:
    expected = []
    for source in case.get("expected_sources", []):
        external_id = source.get("external_doc_id")
        document_id = source.get("document_id")
        if document_id is None and external_id is not None:
            document_id = doc_map.get(str(external_id))
        item = {
            "sourceType": source.get("source_type") or "DOCUMENT_CHUNK",
            "sourceId": source.get("source_id"),
            "documentId": document_id,
            "chunkId": source.get("chunk_id"),
            "wikiPageId": source.get("wiki_page_id"),
        }
        if item["sourceId"] is not None or item["documentId"] is not None or item["chunkId"] is not None or item["wikiPageId"] is not None:
            expected.append({key: value for key, value in item.items() if value is not None})
    return expected


def command_export_noteweave_rageval(args: argparse.Namespace) -> None:
    doc_map = load_doc_id_map(args.doc_id_map)
    exported = []
    for case in iter_jsonl(args.input):
        if case.get("lane") != "team" and not args.include_personal:
            continue
        expected_sources = to_noteweave_expected_sources(case, doc_map)
        tags = dict(case.get("tags") or {})
        tags.update(
            {
                "benchSuite": case.get("suite"),
                "benchCaseId": case.get("case_id"),
                "lane": case.get("lane"),
                "scenario": case.get("scenario"),
                "category": case.get("category"),
                "externalExpectedSources": case.get("expected_sources", []),
            }
        )
        exported.append(
            {
                "name": f"{case.get('suite')}::{case.get('case_id')}",
                "queryText": case.get("query"),
                "expectedAnswer": case.get("expected_answer"),
                "expectedSourceJson": json.dumps(expected_sources, ensure_ascii=False, sort_keys=True)
                if expected_sources
                else None,
                "tagsJson": json.dumps(tags, ensure_ascii=False, sort_keys=True),
                "enabled": True,
            }
        )
    count = write_jsonl(exported, args.output)
    dump_json({"written_rows": count, "output": str(args.output)}, args.report)


def validate_case(case: dict[str, Any]) -> list[str]:
    errors = []
    for field in ("case_id", "suite", "lane", "scenario", "query", "tags"):
        if field not in case or case[field] in (None, ""):
            errors.append(f"missing_required_field:{field}")
    if case.get("lane") not in ("team", "personal"):
        errors.append("invalid_lane")
    if "expected_sources" in case and not isinstance(case["expected_sources"], list):
        errors.append("expected_sources_must_be_array")
    if "conversation" in case and not isinstance(case["conversation"], list):
        errors.append("conversation_must_be_array")
    if not isinstance(case.get("tags", {}), dict):
        errors.append("tags_must_be_object")
    return errors


def command_validate_cases(args: argparse.Namespace) -> None:
    report = {
        "files": [],
        "total_rows": 0,
        "valid_rows": 0,
        "invalid_rows": 0,
        "errors": [],
    }
    for path in args.inputs:
        file_rows = 0
        file_errors = 0
        for row_no, case in enumerate(iter_jsonl(path), start=1):
            file_rows += 1
            errors = validate_case(case)
            if errors:
                file_errors += 1
                report["errors"].append(
                    {
                        "file": str(path),
                        "row": row_no,
                        "case_id": case.get("case_id"),
                        "errors": errors,
                    }
                )
            else:
                report["valid_rows"] += 1
        report["files"].append({"path": str(path), "rows": file_rows, "invalid_rows": file_errors})
        report["total_rows"] += file_rows
        report["invalid_rows"] += file_errors
    dump_json(report, args.output)


def command_export_personal_manifest(args: argparse.Namespace) -> None:
    rows = []
    for case in iter_jsonl(args.input):
        if case.get("lane") != "personal":
            continue
        tags = dict(case.get("tags") or {})
        sources = []
        for source in case.get("expected_sources", []):
            sources.append(
                {
                    "externalId": source.get("external_doc_id"),
                    "sourceType": source.get("source_type") or "SOURCE",
                    "expectedCitationId": source.get("citation_id"),
                    "noteweaveDocumentId": source.get("document_id"),
                    "noteweaveChunkId": source.get("chunk_id"),
                }
            )
        rows.append(
            {
                "caseId": case.get("case_id"),
                "suite": case.get("suite"),
                "lane": "personal",
                "scenario": case.get("scenario"),
                "project": {
                    "name": f"bench::{case.get('suite')}::{case.get('case_id')}",
                    "topic": case.get("query"),
                    "tags": tags,
                },
                "sources": sources,
                "artifact": {
                    "type": tags.get("artifact_type", "REPORT"),
                    "instruction": case.get("query"),
                    "expectedFacts": case.get("answer_facts", []),
                    "expectedAnswer": case.get("expected_answer"),
                },
                "evaluation": {
                    "primaryMetrics": [
                        "source_recall",
                        "citation_precision",
                        "artifact_groundedness",
                        "answer_completeness",
                    ],
                    "expectedSources": case.get("expected_sources", []),
                    "notes": "This manifest describes the personal workflow. It is not imported into rag_eval_case.",
                },
                "rawCase": case if args.include_raw else None,
            }
        )
    count = write_jsonl(rows, args.output)
    dump_json({"written_rows": count, "output": str(args.output)}, args.report)


def find_round(rounds: dict[str, Any], round_id: str) -> dict[str, Any]:
    for item in rounds.get("rounds", []):
        if item.get("id") == round_id:
            return item
    raise SystemExit(f"round not found: {round_id}")


def command_make_run_manifest(args: argparse.Namespace) -> None:
    rounds = load_json(args.rounds)
    registry = load_json(args.registry)
    baselines = load_json(args.baselines)
    metrics = load_json(args.metrics)
    sampling = load_json(args.sampling)
    round_config = find_round(rounds, args.round_id)
    suite_ids = set(round_config.get("suites", []))
    benchmarks = [bench for bench in registry.get("benchmarks", []) if bench.get("id") in suite_ids]
    if "seed_cases" in suite_ids:
        benchmarks.insert(
            0,
            {
                "id": "seed_cases",
                "title": "Local NoteWeave seed cases",
                "lane": ["team", "personal"],
                "priority": "LOCAL",
                "source_urls": {},
            },
        )
    variants = round_config.get("variants", [])
    variant_details = [
        item for item in baselines.get("internal_baselines", [])
        if item.get("id") in variants
    ]
    manifest = {
        "round": round_config,
        "generated_from": {
            "registry": str(args.registry),
            "rounds": str(args.rounds),
            "baselines": str(args.baselines),
            "metrics": str(args.metrics),
            "sampling": str(args.sampling),
        },
        "run_now": False,
        "benchmarks": benchmarks,
        "variants": variant_details,
        "metrics_catalog": metrics,
        "sampling_seed": sampling.get("seed"),
        "expected_inputs": {
            "normalized_cases": f"bench-eval/data/{args.round_id.lower()}.*.normalized.jsonl",
            "team_cases": f"bench-eval/data/{args.round_id.lower()}.*.noteweave_cases.jsonl",
            "personal_manifest": f"bench-eval/data/{args.round_id.lower()}.*.personal_manifest.jsonl",
        },
        "expected_outputs": {
            "responses": f"bench-eval/results/{args.round_id.lower()}.*.responses.jsonl",
            "scores": f"bench-eval/results/{args.round_id.lower()}.*.scores.jsonl",
            "summary": f"bench-eval/results/{args.round_id.lower()}.*.summary.json",
        },
        "notes": [
            "This is a plan artifact only.",
            "Do not execute benchmark runs from this manifest until the sample and variant are confirmed.",
            "Team and personal lanes must be reported separately.",
        ],
    }
    dump_json(manifest, args.output)


def overlap_score(expected: str | None, actual: str | None) -> float:
    if not expected or not actual:
        return 0.0
    expected_terms = {term for term in expected.lower().split() if len(term) > 2}
    actual_terms = {term for term in actual.lower().split() if len(term) > 2}
    if not expected_terms:
        return 0.0
    return len(expected_terms & actual_terms) / len(expected_terms)


def source_recall(expected_sources: list[dict[str, Any]], actual_sources: list[Any]) -> float:
    expected_ids = {
        str(source.get("external_doc_id") or source.get("document_id") or source.get("chunk_id") or source.get("citation_id"))
        for source in expected_sources
        if source.get("external_doc_id") or source.get("document_id") or source.get("chunk_id") or source.get("citation_id")
    }
    actual_ids = {str(item) for item in actual_sources if item is not None}
    if not expected_ids:
        return 0.0
    return len(expected_ids & actual_ids) / len(expected_ids)


def command_score(args: argparse.Namespace) -> None:
    cases = {case["case_id"]: case for case in iter_jsonl(args.cases)}
    scored = []
    for response in iter_jsonl(args.responses):
        case_id = response.get("case_id")
        case = cases.get(case_id)
        if not case:
            scored.append({"case_id": case_id, "error": "case_not_found"})
            continue
        answer = stringify(response.get("answer") or response.get("answer_snapshot"))
        actual_sources = as_list(first_present(response, ["source_ids", "citations", "retrieved_document_ids"], []))
        scored.append(
            {
                "case_id": case_id,
                "suite": case.get("suite"),
                "lane": case.get("lane"),
                "scenario": case.get("scenario"),
                "category": case.get("category"),
                "answer_overlap": round(overlap_score(case.get("expected_answer"), answer), 4),
                "source_recall": round(source_recall(case.get("expected_sources", []), actual_sources), 4),
                "latency_ms": response.get("latency_ms"),
                "error": response.get("error"),
            }
        )
    count = write_jsonl(scored, args.output)
    dump_json({"written_rows": count, "output": str(args.output)}, args.report)


def command_summarize(args: argparse.Namespace) -> None:
    rows = list(iter_jsonl(args.input))
    groups: dict[tuple[str, str, str, str], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        key = (
            str(row.get("suite", "unknown")),
            str(row.get("lane", "unknown")),
            str(row.get("scenario", "unknown")),
            str(row.get("category", "unknown")),
        )
        groups[key].append(row)

    summary = []
    for (suite, lane, scenario, category), group in sorted(groups.items()):
        answer_scores = [float(row["answer_overlap"]) for row in group if row.get("answer_overlap") is not None]
        source_scores = [float(row["source_recall"]) for row in group if row.get("source_recall") is not None]
        latencies = [float(row["latency_ms"]) for row in group if row.get("latency_ms") is not None]
        summary.append(
            {
                "suite": suite,
                "lane": lane,
                "scenario": scenario,
                "category": category,
                "case_count": len(group),
                "avg_answer_overlap": round(statistics.mean(answer_scores), 4) if answer_scores else None,
                "avg_source_recall": round(statistics.mean(source_scores), 4) if source_scores else None,
                "avg_latency_ms": round(statistics.mean(latencies), 2) if latencies else None,
                "error_count": sum(1 for row in group if row.get("error")),
            }
        )
    dump_json({"groups": summary}, args.output)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="NoteWeave benchmark adapters")
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="Print sampling plan from benchmark registry")
    plan.add_argument("--registry", type=Path, default=REGISTRY_PATH)
    plan.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    plan.add_argument("--output", type=Path)
    plan.set_defaults(func=command_plan)

    normalize = sub.add_parser("normalize-enterprise-rag", help="Normalize EnterpriseRAG-style questions JSONL")
    normalize.add_argument("--input", type=Path, required=True)
    normalize.add_argument("--output", type=Path, required=True)
    normalize.add_argument("--ratio", type=float, default=0.1)
    normalize.add_argument("--max-cases", type=int)
    normalize.add_argument("--seed", type=int, default=20260527)
    normalize.add_argument("--stratify-by", nargs="*", default=["question_type"])
    normalize.add_argument("--report", type=Path)
    normalize.set_defaults(func=command_normalize_enterprise_rag)

    export = sub.add_parser("export-noteweave-rageval", help="Export normalized team cases to current NoteWeave RAG eval request JSONL")
    export.add_argument("--input", type=Path, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--doc-id-map", type=Path)
    export.add_argument("--include-personal", action="store_true")
    export.add_argument("--report", type=Path)
    export.set_defaults(func=command_export_noteweave_rageval)

    validate = sub.add_parser("validate-cases", help="Validate normalized benchmark JSONL shape")
    validate.add_argument("--inputs", type=Path, nargs="+", required=True)
    validate.add_argument("--output", type=Path)
    validate.set_defaults(func=command_validate_cases)

    personal = sub.add_parser("export-personal-manifest", help="Export normalized personal cases into a workflow manifest JSONL")
    personal.add_argument("--input", type=Path, required=True)
    personal.add_argument("--output", type=Path, required=True)
    personal.add_argument("--include-raw", action="store_true")
    personal.add_argument("--report", type=Path)
    personal.set_defaults(func=command_export_personal_manifest)

    manifest = sub.add_parser("make-run-manifest", help="Generate a planned run manifest from configs without executing it")
    manifest.add_argument("--round-id", required=True)
    manifest.add_argument("--rounds", type=Path, default=RUN_ROUNDS_PATH)
    manifest.add_argument("--registry", type=Path, default=REGISTRY_PATH)
    manifest.add_argument("--baselines", type=Path, default=BASELINES_PATH)
    manifest.add_argument("--metrics", type=Path, default=METRICS_PATH)
    manifest.add_argument("--sampling", type=Path, default=DEFAULT_CONFIG_PATH)
    manifest.add_argument("--output", type=Path)
    manifest.set_defaults(func=command_make_run_manifest)

    score = sub.add_parser("score", help="Score response JSONL against normalized cases")
    score.add_argument("--cases", type=Path, required=True)
    score.add_argument("--responses", type=Path, required=True)
    score.add_argument("--output", type=Path, required=True)
    score.add_argument("--report", type=Path)
    score.set_defaults(func=command_score)

    summarize = sub.add_parser("summarize", help="Summarize scored JSONL")
    summarize.add_argument("--input", type=Path, required=True)
    summarize.add_argument("--output", type=Path)
    summarize.set_defaults(func=command_summarize)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
