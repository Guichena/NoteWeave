import base64
import copy
import hashlib
import json
import sys
from pathlib import Path
import os
from types import SimpleNamespace
from app.agent_task_client import JavaResearchAgentTaskClient, AgentTaskApiError, AgentTaskClaim
from app.evidence_audit_executor import _role_completion
from app.narrative_report import _render_markdown
from app.research_agent_completion_contract import canonical_json_bytes, serialize_completion_envelope

original = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
narrative = copy.deepcopy(original["narrative"])
for section in narrative["sections"]:
    section["paragraphs"] *= 60
ledger, audit = "sha256:" + "a" * 64, "sha256:" + "b" * 64
result = {"result_schema_version": "research-synthesis-candidate.v2", "role": "SYNTHESIS", "status": "CANDIDATE",
          "ledger_digest": ledger, "audit_digest": audit, "narrative": narrative, "markdown": _render_markdown(narrative),
          "limitations": narrative["limitations"], "claims": [{"cell_key": cell["cell_key"], "claim_text": cell["candidate_value"],
          "evidence_keys": cell["evidence_keys"], "guarded": False} for cell in original["input"]["cells"]]}

class OfflineClient(JavaResearchAgentTaskClient):
    def __init__(self):
        super().__init__("http://unused.invalid", "", "offline-worker")
        self.parts, self.attempts = {}, 0
    def _request(self, method, path, payload=None, **kwargs):
        self.attempts += 1
        index = payload["part_index"]
        replay = index in self.parts
        if replay:
            assert self.parts[index] == payload
        self.parts[index] = payload
        if self.attempts == 1:
            raise AgentTaskApiError(503, "OFFLINE_AMBIGUOUS_WRITE")
        return {"data": {"content_digest": payload["content_digest"], "part_index": index,
                         "part_digest": payload["part_digest"], "idempotent_replay": replay}}

snapshot = SimpleNamespace(role="SYNTHESIS", task_id="00000000-0000-0000-0000-000000000001", lease_epoch=1, fencing_token=10,
                           snapshot_digest="sha256:" + "c" * 64,
                           query_policy={"synthesis_input": {"report_upload_version": 1}})
client = OfflineClient()
reference = client.upload_synthesis_result(snapshot, result)
content = b"".join(base64.b64decode(client.parts[index]["content_base64"]) for index in sorted(client.parts))
assert len(content) > 262144 and json.loads(content.decode("utf-8")) == json.loads(canonical_json_bytes(result))
assert reference["artifact_ref"]["content_digest"] == "sha256:" + hashlib.sha256(content).hexdigest()
claim = AgentTaskClaim(snapshot.task_id, 1, 10, "[]", "{}")
completion = _role_completion(claim, snapshot.snapshot_digest, "offline-worker", reference)
assert len(serialize_completion_envelope(completion)) < 4096
Path(sys.argv[2]).write_text(json.dumps({"parts": list(client.parts.values()), "reference": reference, "input": original["input"]}, ensure_ascii=False), encoding="utf-8")
print(json.dumps({"report_bytes": len(content), "upload_parts": len(client.parts), "retry_preserved_content": client.attempts == len(client.parts) + 1,
                  "completion_bytes": len(serialize_completion_envelope(completion)), "provider_calls": 0}))
