"""默认检查准备情况；只有 --execute 才请求解析器。"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time

try:
    from .adapters import AdapterError, DoclingHttpAdapter, MineruCliAdapter
    from .contracts import ContractError, MAX_INPUT_BYTES, file_sha256, loads, local_path, read_bytes, validate_document, validate_manifest
    from .metrics import measure, normalize
except ImportError:
    from adapters import AdapterError, DoclingHttpAdapter, MineruCliAdapter
    from contracts import ContractError, MAX_INPUT_BYTES, file_sha256, loads, local_path, read_bytes, validate_document, validate_manifest
    from metrics import measure, normalize


def _readiness(sample, root):
    reasons, data = [], {}
    try:
        source = local_path(root, sample["input_path"])
        if not source.is_file():
            reasons.append("INPUT_MISSING")
        elif not 0 < source.stat().st_size <= MAX_INPUT_BYTES:
            reasons.append("INPUT_SIZE_LIMIT")
        elif sample["sha256"] is None:
            reasons.append("INPUT_HASH_MISSING")
        elif file_sha256(source) != sample["sha256"]:
            reasons.append("INPUT_HASH_MISMATCH")
        else:
            data["source"] = source
        for field, missing in (("gold_text_path", "GOLD_TEXT_MISSING"), ("gold_layout_path", "GOLD_LAYOUT_MISSING")):
            if sample[field] is None:
                reasons.append(missing)
                continue
            path = local_path(root, sample[field])
            if not path.is_file():
                reasons.append(missing)
                continue
            raw = read_bytes(path)
            if field == "gold_text_path":
                text = raw.decode("utf-8-sig")
                if not normalize(text):
                    reasons.append("GOLD_TEXT_EMPTY")
                data["gold_text"] = text
            else:
                layout = validate_document(loads(raw))
                if layout["parser"] != "GOLD" or layout["sample_id"] != sample["sample_id"] or layout["input_sha256"] != sample["sha256"]:
                    reasons.append("GOLD_LAYOUT_IDENTITY_MISMATCH")
                elif not layout["complete"] or not layout["elements"]:
                    reasons.append("GOLD_LAYOUT_INCOMPLETE")
                else:
                    data["gold_layout"] = layout
    except (OSError, ValueError, UnicodeError, RecursionError):
        reasons.append("SAMPLE_OR_ANNOTATION_INVALID")
    return list(dict.fromkeys(reasons)), data


def _parser_result(name, reasons):
    return {"parser": name, "status": "NOT_RUN", "reason_codes": list(reasons), "parser_version": None,
        "version_reason": "PARSER_NOT_EXECUTED", "raw_output_path": None, "raw_output_sha256": None,
        "normalized_output_path": None, "elapsed_seconds": None, "peak_rss_bytes": None,
        "resource_measurement_reason": "REMOTE_RESOURCE_METRICS_UNAVAILABLE" if name == "DOCLING" else "SUBPROCESS_PEAK_RSS_UNOBSERVED",
        "complete_document": None, "metrics": None}


def evaluate(manifest_path: Path, output_directory: Path, *, execute=False, adapters=None, max_cer_cells=4_000_000):
    manifest = validate_manifest(loads(read_bytes(manifest_path, 4 * 1024 * 1024)))
    if type(max_cer_cells) is not int or not 1 <= max_cer_cells <= 100_000_000:
        raise ContractError("编辑距离预算必须在 1 至 100000000 之间")
    root = (manifest_path.parent / manifest["sample_root"]).resolve()
    adapters = adapters or {"DOCLING": DoclingHttpAdapter(token=os.environ.get("NOTEWEAVE_PARSE_INTERNAL_AUTH_TOKEN")), "MINERU": MineruCliAdapter()}
    if set(adapters) != {"DOCLING", "MINERU"}:
        raise ContractError("必须分别配置 Docling 和 MinerU")
    global_reasons = []
    if len(manifest["samples"]) != manifest["expected_sample_count"]:
        global_reasons.append("SAMPLE_COUNT_MISMATCH")
    prepared = [(sample, *_readiness(sample, root)) for sample in manifest["samples"]]
    if any(reasons for _, reasons, _ in prepared):
        global_reasons.append("SAMPLES_OR_ANNOTATIONS_NOT_READY")
    if not execute:
        global_reasons.append("EXECUTION_DEFERRED")
    results = []
    for sample, reasons, data in prepared:
        parser_results = []
        for name, adapter in adapters.items():
            row = _parser_result(name, list(dict.fromkeys(global_reasons + reasons)))
            parser_results.append(row)
            if global_reasons or reasons:
                continue
            unavailable = adapter.unavailable_reason()
            if unavailable:
                row["reason_codes"] = [unavailable]
                continue
            output_directory.mkdir(parents=True, exist_ok=True)
            raw = output_directory / (sample["sample_id"] + "-" + name.lower() + (".raw.zip" if name == "MINERU" else ".raw.json"))
            normalized_path = output_directory / (sample["sample_id"] + "-" + name.lower() + ".normalized.json")
            if raw.exists() or normalized_path.exists():
                row["reason_codes"] = ["OUTPUT_ALREADY_EXISTS"]
                continue
            start = time.perf_counter()
            adapter_elapsed = None
            try:
                parsed = validate_document(adapter.parse(sample, data["source"], raw))
                adapter_elapsed = time.perf_counter() - start
                if parsed["parser"] != name or parsed["sample_id"] != sample["sample_id"] or parsed["input_sha256"] != sample["sha256"]:
                    raise ContractError("解析产物身份不符")
                if file_sha256(data["source"]) != sample["sha256"]:
                    raise AdapterError("INPUT_CHANGED_DURING_PARSE", "解析期间输入文件发生变化")
                normalized_path.write_text(json.dumps(parsed, ensure_ascii=False, allow_nan=False, indent=2), encoding="utf-8")
                row.update(status="COMPLETED", complete_document=parsed["complete"], parser_version=parsed["parser_version"],
                    version_reason=None if parsed["parser_version"] else "REMOTE_PARSER_VERSION_UNAVAILABLE",
                    normalized_output_path=normalized_path.name, metrics=measure(data["gold_text"], data["gold_layout"], parsed, max_cer_cells),
                    reason_codes=[] if parsed["complete"] else ["TRUNCATED_DOCUMENT"])
            except Exception as error:
                row.update(status="FAILED", reason_codes=[error.code if isinstance(error, AdapterError) else "PARSER_OUTPUT_OR_EXECUTION_INVALID"], metrics=None)
            finally:
                row["elapsed_seconds"] = adapter_elapsed if adapter_elapsed is not None else time.perf_counter() - start
                if raw.is_file():
                    row["raw_output_path"], row["raw_output_sha256"] = raw.name, file_sha256(raw)
        results.append({"sample_id": sample["sample_id"], "input_sha256": sample["sha256"], "readiness_reasons": reasons, "parsers": parser_results})
    statuses = [row["status"] for sample in results for row in sample["parsers"]]
    status = "NOT_RUN" if not statuses or all(item == "NOT_RUN" for item in statuses) else (
        "COMPLETED" if all(item == "COMPLETED" for item in statuses) else "PARTIAL")
    return {"schema_version": 1, "suite_id": manifest["suite_id"], "status": status,
        "created_at": datetime.now(timezone.utc).isoformat(), "manifest_sha256": file_sha256(manifest_path),
        "expected_sample_count": 30, "registered_sample_count": len(prepared),
        "ready_sample_count": sum(not reasons for _, reasons, _ in prepared), "reason_codes": global_reasons,
        "samples": results}


def main(argv=None):
    parser = argparse.ArgumentParser(description="检查 30 份资料与人工标注；只有显式 --execute 才运行解析器")
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("manifest.json"))
    parser.add_argument("--output", type=Path, required=True, help="新输出目录，已存在的解析产物不会覆盖")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--docling-url", default="http://localhost:18103")
    parser.add_argument("--docling-timeout", type=int, default=180)
    parser.add_argument("--mineru-executable", default="mineru-kit")
    parser.add_argument("--mineru-timeout", type=int, default=1800)
    parser.add_argument("--mineru-tier", choices=("flash", "standard"), default="standard")
    parser.add_argument("--max-cer-cells", type=int, default=4_000_000)
    arguments = parser.parse_args(argv)
    if (arguments.output / "results.json").exists():
        parser.error("结果文件已存在，请使用新的输出目录")
    if not 1 <= arguments.docling_timeout <= 86400 or not 1 <= arguments.mineru_timeout <= 86400:
        parser.error("解析超时必须在 1 至 86400 秒之间")
    adapters = {"DOCLING": DoclingHttpAdapter(arguments.docling_url, os.environ.get("NOTEWEAVE_PARSE_INTERNAL_AUTH_TOKEN"), arguments.docling_timeout),
        "MINERU": MineruCliAdapter(arguments.mineru_executable, arguments.mineru_timeout, arguments.mineru_tier)}
    try:
        result = evaluate(arguments.manifest, arguments.output, execute=arguments.execute, adapters=adapters, max_cer_cells=arguments.max_cer_cells)
        arguments.output.mkdir(parents=True, exist_ok=True)
        report = arguments.output / "results.json"
        with report.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, allow_nan=False, indent=2)
        print(json.dumps({"status": result["status"], "registered_samples": result["registered_sample_count"],
            "ready_samples": result["ready_sample_count"], "reason_codes": result["reason_codes"], "report": str(report)}, ensure_ascii=False))
        if arguments.execute and result["status"] == "NOT_RUN":
            return 2
        return 0 if result["status"] in {"NOT_RUN", "COMPLETED"} else 1
    except (OSError, ValueError, RecursionError) as error:
        parser.exit(2, "清单或输出无效: " + str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
