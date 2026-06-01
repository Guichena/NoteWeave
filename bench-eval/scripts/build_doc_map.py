#!/usr/bin/env python3
"""Build a noteweave document-id map from a simple JSONL or JSON file.

This helper is preparation-only. It does not query the NoteWeave backend.
It just normalizes a local mapping of external benchmark document ids to
internal NoteWeave ids so later exports can populate `expectedSourceJson`.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable


def iter_rows(path: Path) -> Iterable[dict[str, Any]]:
    if path.suffix.lower() == ".jsonl":
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                text = line.strip()
                if not text:
                    continue
                try:
                    row = json.loads(text)
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_no}: invalid JSONL row: {exc}") from exc
                if not isinstance(row, dict):
                    raise SystemExit(f"{path}:{line_no}: expected object row")
                yield row
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        for row in data:
            if not isinstance(row, dict):
                raise SystemExit(f"{path}: expected list of objects")
            yield row
        return
    if isinstance(data, dict):
        yield data
        return
    raise SystemExit(f"{path}: unsupported JSON shape")


def command_build(args: argparse.Namespace) -> None:
    mapping = {}
    for row in iter_rows(args.input):
        external_id = row.get(args.external_key)
        internal_id = row.get(args.internal_key)
        if external_id in (None, "") or internal_id in (None, ""):
            continue
        mapping[str(external_id)] = int(internal_id)
    text = json.dumps(mapping, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build a document-id mapping file")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--external-key", default="external_doc_id")
    parser.add_argument("--internal-key", default="document_id")
    parser.add_argument("--output", type=Path)
    parser.set_defaults(func=command_build)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
