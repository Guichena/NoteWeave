"""把技能目录从 reference/ 同步到 Java Host 和 Artifact Worker。

技能目录只在 reference/artifact-skill-catalog-v2.json 维护：Java 读取它做展示和校验，
Worker 读取同一份字节执行（两边按 SHA-256 摘要核对）。新增产物时只改这个文件，然后运行：

    python scripts/sync-artifact-skill-catalog.py          # 同步
    python scripts/sync-artifact-skill-catalog.py --check  # 只检查 Java 与 Worker 的副本是否和源文件一致，不一致时返回 1
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "reference" / "artifact-skill-catalog-v2.json"
TARGETS = [
    ROOT / "backend" / "src" / "main" / "resources" / "artifact-skill-catalog-v2.json",
    ROOT / "workers" / "artifact-worker" / "app" / "artifact-skill-catalog-v2.json",
]


def main(argv: list[str]) -> int:
    content = SOURCE.read_bytes()
    catalog = json.loads(content)
    digest = hashlib.sha256(content).hexdigest()
    declared = [entry["skill_key"] for entry in catalog["skills"] if "definition" in entry]
    if "--check" in argv:
        same = all(target.exists() and target.read_bytes() == content for target in TARGETS)
        print(f"catalog digest {digest}: {'in sync' if same else 'OUT OF SYNC'}")
        return 0 if same else 1
    for target in TARGETS:
        target.write_bytes(content)
    print(f"synced {len(catalog['skills'])} skills ({len(declared)} declared in catalog: {', '.join(declared) or '-'})")
    print(f"catalog digest {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
