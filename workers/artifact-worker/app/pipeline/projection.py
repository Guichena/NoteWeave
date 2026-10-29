"""保留现有 artifact-content-v1、引用与候选文件清单。"""

import hashlib

from app.artifact_content_ir import build_content_ir
from app.candidate_file_manifest import build_required_files
from app.models import ArtifactSectionDraft
from app.pipeline.evidence import facts
from app.pipeline.rules import units


def project(task, ir, evidence, sections, files):
    lookup = {s["id"]: s for s in evidence["sentences"]}
    for section, item in zip(sections, units(ir)):
        section["source_refs"] = list(dict.fromkeys(lookup[a].get("title", lookup[a]["source_id"])
                    for fact in facts(item) for a in fact["anchors"]))
    markdown = "# " + ir["title"] + "\n\n" + "\n\n".join("## " + s["heading"] + "\n\n" + s["body"] for s in sections)
    content = build_content_ir(artifact_type=ir["kind"], title=ir["title"],
                              sections=[ArtifactSectionDraft(**s) for s in sections], markdown=markdown)
    sha = hashlib.sha256(markdown.encode()).hexdigest()
    payload = {"markdown": markdown, "sections": sections, "content_ir": content.model_dump(mode="json"),
               "ir": ir, "evidence": evidence,
               "candidate": {"candidate_id": hashlib.sha256(f"{task.task_id}:{task.input_snapshot_id}:{sha}".encode()).hexdigest(),
                 "task_id": task.task_id, "input_snapshot_id": task.input_snapshot_id, "catalog_digest": task.catalog_digest,
                 "content_sha256": sha, "content_ir_digest": content.content_digest,
                 "required_files": build_required_files(markdown, {"format": "MARKDOWN"}) + files}}
    for file in files:
        if file["role"] == "PRIMARY_PDF":
            payload["export_trace"] = {"status": "COMPILED", "format": "PDF", "file_name": file["file_name"]}
    citations = []
    seen = set()
    for fact in facts(ir):
        for anchor in fact["anchors"]:
            sentence = lookup[anchor]
            identity = (sentence["source_id"], sentence.get("source_snapshot_id", ""), sentence["chunk_id"])
            if identity not in seen:
                seen.add(identity)
                citations.append({k: sentence[k] for k in ("source_id", "source_snapshot_id", "chunk_id", "title", "location") if k in sentence})
    return payload, citations
