"""Validate the durable proof emitted by the G3 response-loss proxy."""

from __future__ import annotations

import json
from pathlib import Path


def main() -> None:
    control = Path("/control")
    if (control / "g3-proxy-error.json").exists():
        raise RuntimeError("G3 proxy recorded an invariant failure")
    proof = json.loads((control / "g3-proof.json").read_text(encoding="utf-8"))
    if proof["request_sha256"] != proof["first_request_sha256"]:
        raise RuntimeError("G3 replay request digest changed")
    if proof["request_size_bytes"] != proof["first_request_size_bytes"]:
        raise RuntimeError("G3 replay request size changed")
    if not proof["idempotency_key"] or proof["idempotency_key"] != proof["first_idempotency_key"]:
        raise RuntimeError("G3 idempotency header changed")
    if proof["first_response_status"] != 200 or proof["second_response_status"] != 200:
        raise RuntimeError("G3 did not observe two successful Backend completion responses")
    first = proof["first_receipt"]
    second = proof["second_receipt"]
    if first.get("idempotent_replay") is not False or second.get("idempotent_replay") is not True:
        raise RuntimeError("G3 receipt replay flags are invalid")
    for key in ("schema_version", "completion_id", "execution_id", "task_id", "completion_digest", "receipt_digest"):
        if not first.get(key) or first[key] != second.get(key):
            raise RuntimeError(f"G3 receipt identity changed: {key}")
    print(
        "MA4G_G3_PROXY_PROOF_VERIFIED "
        f"sha256={proof['request_sha256']} receipt={first['receipt_digest']}",
        flush=True,
    )


if __name__ == "__main__":
    main()

