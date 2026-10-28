"""只按形态选择渲染器，沿用 Worker 现有导出目录。"""

import hashlib
import re

from app.config import resolve_mcp_sandbox_root
from app.pipeline.renderers import interactive, markdown, table, pptx, pdf


def render(ir, skill, task, *, evidence=None, frame_reader=None):
    files = []
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task.task_id):
        raise ValueError("产物任务编号不符合导出路径要求")
    root = resolve_mcp_sandbox_root() / "bilibili-render-pdf" / "exports" / task.task_id
    def add_file(role, path, sequence=0):
        media = {".pdf": "application/pdf", ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".png": "image/png"}[path.suffix]
        content = path.read_bytes()
        files.append({"role": role, "variant": "", "sequence_no": sequence, "file_name": path.name,
                      "media_type": media, "size_bytes": len(content), "checksum_sha256": hashlib.sha256(content).hexdigest()})
    if ir["kind"] == "DECK":
        sections, outputs = pptx.render(ir, task.task_id, evidence, frame_reader)
        for role, path, sequence in outputs:
            add_file(role, path, sequence)
        return sections, files
    if ir["kind"] == "TABLE":
        sections, csv = table.render(ir)
        if "CSV" in skill.renderers:
            root.mkdir(parents=True, exist_ok=True)
            (root / "content.csv").write_bytes(csv)
            files.append({"role": "PRIMARY_CSV", "variant": "", "sequence_no": 0, "file_name": "content.csv",
                          "media_type": "text/csv; charset=UTF-8", "size_bytes": len(csv),
                          "checksum_sha256": hashlib.sha256(csv).hexdigest()})
    else:
        renderer = markdown if ir["kind"] == "DOC" else interactive
        sections = renderer.render(ir)
    if "PDF" in skill.renderers:
        add_file("PRIMARY_PDF", pdf.render(ir, root, evidence, frame_reader))
    return sections, files
