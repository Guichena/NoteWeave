"""Recompute persisted MA4G envelope and receipt bytes inside the Worker image."""

from __future__ import annotations

import base64
import json
import os
import unicodedata

from app.agent_task_client import _validated_completion_receipt
from app.research_agent_completion_contract import (
    canonical_json_bytes,
    parse_completion_envelope_json,
    serialize_completion_envelope,
)


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        normalized = unicodedata.normalize("NFC", key)
        if normalized in result:
            raise ValueError("duplicate receipt object key after NFC normalization")
        result[normalized] = value
    return result


def main() -> None:
    path = os.environ.get("MA4G_CONTRACT_EXPORT", "/control/contracts.tsv")
    count = 0
    with open(path, "rt", encoding="utf-8", newline="") as handle:
        for raw_line in handle:
            fields = raw_line.rstrip("\n").split("\t")
            if len(fields) != 6:
                raise RuntimeError("persisted contract export has an invalid field count")
            completion_id, size_text, envelope_digest, receipt_digest, envelope_b64, receipt_b64 = fields
            envelope_bytes = base64.b64decode(envelope_b64, validate=True)
            receipt_bytes = base64.b64decode(receipt_b64, validate=True)
            if len(envelope_bytes) != int(size_text):
                raise RuntimeError(f"completion {completion_id} byte length mismatch")
            envelope = parse_completion_envelope_json(envelope_bytes)
            if serialize_completion_envelope(envelope) != envelope_bytes:
                raise RuntimeError(f"completion {completion_id} is not stored as canonical bytes")
            if envelope.envelope_digest != envelope_digest:
                raise RuntimeError(f"completion {completion_id} envelope digest mismatch")
            receipt = json.loads(
                receipt_bytes.decode("utf-8", errors="strict"),
                object_pairs_hook=_reject_duplicate_pairs,
            )
            if not isinstance(receipt, dict) or "idempotent_replay" in receipt:
                raise RuntimeError(f"completion {completion_id} persisted receipt is malformed")
            if canonical_json_bytes(receipt) != receipt_bytes:
                raise RuntimeError(f"completion {completion_id} receipt is not stored as canonical bytes")
            receipt["idempotent_replay"] = False
            validated = _validated_completion_receipt(receipt, envelope)
            if validated.receipt_digest != receipt_digest:
                raise RuntimeError(f"completion {completion_id} receipt digest mismatch")
            count += 1
    if count < 1:
        raise RuntimeError("no persisted completion contracts were exported")
    print(f"MA4G_PERSISTED_CONTRACTS_VERIFIED count={count}", flush=True)


if __name__ == "__main__":
    main()
