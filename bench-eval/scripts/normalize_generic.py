#!/usr/bin/env python3
"""Generic benchmark normalizer driven by config files.

This is a preparation-only tool. It reads a suite-specific normalizer config
and turns raw benchmark rows into NoteWeave-normalized JSONL cases.
No validation or benchmark execution happens here.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def iter_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSONL: {exc}") from exc
            if not isinstance(row, dict):
                raise SystemExit(f"{path}:{line_no}: expected object row")
            yield row


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


def first_present(row: dict[str, Any], fields: list[str], default: Any = None) -> Any:
    for field in fields:
        value = get_path(row, field)
        if value not in (None, ""):
            return value
    return default


def get_path(row: dict[str, Any], path: str) -> Any:
    current: Any = row
    for part in path.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(part)
        if current is None:
            return None
    return current


def sample_rows(rows: list[dict[str, Any]], ratio: float, max_cases: int | None, seed: int, stratify_by: list[str]) -> list[dict[str, Any]]:
    if ratio >= 1.0 and max_cases is None:
        return list(rows)
    buckets: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        key_bits = []
        for field in stratify_by:
            value = get_path(row, field)
            if isinstance(value, list):
                value = ",".join(sorted(str(item) for item in value))
            key_bits.append(str(value if value not in (None, "") else "unknown"))
        bucket_key = "|".join(key_bits)
        buckets.setdefault(bucket_key, []).append(row)
    rng = random.Random(seed)
    selected: list[dict[str, Any]] = []
    for key in sorted(buckets):
        bucket = list(buckets[key])
        rng.shuffle(bucket)
        take = max(1, math.ceil(len(bucket) * ratio))
        selected.extend(bucket[:take])
    rng.shuffle(selected)
    if max_cases is not None:
        selected = selected[:max_cases]
    return selected


def build_expected_sources(row: dict[str, Any], config: dict[str, Any], doc_map: dict[str, int] | None = None) -> list[dict[str, Any]]:
    source_id_fields = as_list(config.get("source_id_fields"))
    source_type_fields = as_list(config.get("source_type_fields"))
    ids = first_present(row, list(source_id_fields), [])
    ids = as_list(ids)
    source_types: list[str] = []
    for field in source_type_fields:
        value = first_present(row, [field], None)
        if isinstance(value, list):
            source_types = [str(item) for item in value]
            break
        if value not in (None, ""):
            source_types = [str(value)]
            break
    expected: list[dict[str, Any]] = []
    for raw_id in ids:
        source: dict[str, Any] = {
            "external_doc_id": str(raw_id),
            "source_type": source_types[0] if source_types else None,
            "document_id": None,
            "chunk_id": None,
            "wiki_page_id": None,
            "citation_id": None,
        }
        if doc_map and str(raw_id) in doc_map:
            source["document_id"] = doc_map[str(raw_id)]
        if any(source.get(key) is not None for key in ("document_id", "chunk_id", "wiki_page_id")):
            expected.append(source)
    return expected


def normalize_row(row: dict[str, Any], config: dict[str, Any], doc_map: dict[str, int] | None = None) -> dict[str, Any]:
    id_fields = as_list(config.get("id_fields"))
    query_fields = as_list(config.get("query_fields"))
    answer_fields = as_list(config.get("answer_fields"))
    category_fields = as_list(config.get("category_fields"))
    answer_fact_fields = as_list(config.get("answer_fact_fields"))

    case_id = first_present(row, list(id_fields))
    query = first_present(row, list(query_fields))
    answer = first_present(row, list(answer_fields))
    category = first_present(row, list(category_fields), None)
    answer_facts = as_list(first_present(row, list(answer_fact_fields), []))
    conversation = []
    for field in as_list(config.get("conversation_fields")):
        value = get_path(row, field)
        if isinstance(value, list) and value:
            conversation = value
            break

    if not answer_facts and answer:
        answer_facts = [str(answer)]

    expected_sources = build_expected_sources(row, config, doc_map=doc_map)
    tags = dict(config.get("static_tags") or {})
    tags.update(
        {
            "raw_category": category,
            "source_types": [str(item) for item in as_list(first_present(row, list(as_list(config.get("source_type_fields"))), []))] if config.get("source_type_fields") else [],
            "original_case_id": case_id,
        }
    )

    normalized = {
        "case_id": str(case_id) if case_id is not None else stable_id(config["suite"], query, answer),
        "suite": config["suite"],
        "lane": config["lane"],
        "scenario": config["scenario"],
        "category": str(category) if category is not None else None,
        "conversation": conversation or ([{"role": "user", "content": str(query)}] if query else []),
        "query": str(query) if query is not None else "",
        "expected_answer": str(answer) if answer is not None else None,
        "expected_sources": expected_sources,
        "answer_facts": [str(item) for item in answer_facts],
        "tags": tags,
        "raw": row,
    }
    return normalized


def command_normalize(args: argparse.Namespace) -> None:
    config = read_json(args.config)
    doc_map = read_json(args.doc_map) if args.doc_map else None
    rows = list(iter_jsonl(args.input))
    sampled = sample_rows(
        rows,
        ratio=float(args.ratio if args.ratio is not None else config.get("sample", {}).get("ratio", 1.0)),
        max_cases=args.max_cases if args.max_cases is not None else config.get("sample", {}).get("max_cases"),
        seed=int(args.seed if args.seed is not None else 20260527),
        stratify_by=list(args.stratify_by or config.get("sample", {}).get("stratify_by", [])),
    )
    normalized = [normalize_row(row, config, doc_map=doc_map) for row in sampled]
    count = write_jsonl(normalized, args.output)
    report = {
        "suite": config["suite"],
        "input_rows": len(rows),
        "sampled_rows": len(sampled),
        "written_rows": count,
        "output": str(args.output),
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Generic benchmark normalizer")
    sub = parser.add_subparsers(dest="command", required=True)

    normalize = sub.add_parser("normalize", help="Normalize a raw benchmark JSONL using a suite config")
    normalize.add_argument("--config", type=Path, required=True)
    normalize.add_argument("--input", type=Path, required=True)
    normalize.add_argument("--output", type=Path, required=True)
    normalize.add_argument("--doc-map", type=Path)
    normalize.add_argument("--ratio", type=float)
    normalize.add_argument("--max-cases", type=int)
    normalize.add_argument("--seed", type=int)
    normalize.add_argument("--stratify-by", nargs="*")
    normalize.add_argument("--report", type=Path)
    normalize.set_defaults(func=command_normalize)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
