"""只按形态选择渲染器，沿用 Worker 现有导出目录。"""

import hashlib
import re

from app.config import resolve_mcp_sandbox_root
from app.pipeline.renderers import interactive, markdown, table


def render(ir, skill, task):
    files = []
    if ir["kind"] == "TABLE":
        sections, csv = table.render(ir)
        if "CSV" in skill.renderers:
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task.task_id):
                raise ValueError("产物任务编号不符合导出路径要求")
            root = resolve_mcp_sandbox_root() / "bilibili-render-pdf" / "exports" / task.task_id
            root.mkdir(parents=True, exist_ok=True)
            (root / "content.csv").write_bytes(csv)
            files.append({"role": "PRIMARY_CSV", "variant": "", "sequence_no": 0, "file_name": "content.csv",
                          "media_type": "text/csv; charset=UTF-8", "size_bytes": len(csv),
                          "checksum_sha256": hashlib.sha256(csv).hexdigest()})
    else:
        renderer = markdown if ir["kind"] == "DOC" else interactive
        sections = renderer.render(ir)
    return sections, files
