"""只提交有界计数和文档摘要；未获得 Provider token 用量时保留未知。"""
import hashlib
import re
import time


def validate_runtime_metrics(raw):
    if (not isinstance(raw, dict) or set(raw) != {"schema_version", "input_tokens", "output_tokens", "duration_ms", "document_fingerprints", "provider_call_count"}
            or raw["schema_version"] != "research-runtime-metrics.v1"
            or any(raw[key] is not None and (type(raw[key]) is not int or not 0 <= raw[key] <= 1_000_000_000)
                   for key in ("input_tokens", "output_tokens", "duration_ms", "provider_call_count"))
            or not isinstance(raw["document_fingerprints"], list) or len(raw["document_fingerprints"]) > 64
            or any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value) for value in raw["document_fingerprints"])):
        raise ValueError("运行指标格式不符合约定")


def runtime_metrics(client, documents, started):
    usage = client.usage_summary() if callable(getattr(client, "usage_summary", None)) else {}
    return {"schema_version": "research-runtime-metrics.v1",
        "provider_call_count": 0 if client is None else usage.get("provider_call_count"),
        "input_tokens": usage.get("reported_input_tokens"), "output_tokens": usage.get("reported_output_tokens"),
        "duration_ms": max(0, int((time.monotonic() - started) * 1000)),
        "document_fingerprints": [hashlib.sha256(re.sub(r"\s+", " ", doc.snapshot_text).strip().encode()).hexdigest()
                                  for doc in documents if doc.snapshot_text][:64]}
