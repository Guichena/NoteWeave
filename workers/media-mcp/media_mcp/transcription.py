from __future__ import annotations

import json
import sys
from pathlib import Path


def transcribe(path: Path, output: Path, options: dict) -> None:
    """在可终止的子进程中直接使用 faster-whisper，不调用仓库外脚本。"""
    from faster_whisper import WhisperModel

    model = WhisperModel(options["model"], device=options["device"], compute_type="int8",
                         download_root=options["model_cache"])
    segments, _ = model.transcribe(str(path), language=options["language"] or None, beam_size=5)
    rows = []
    count = 0
    for segment in segments:
        text = segment.text.strip()
        count += len(text)
        if count > options["max_chars"]:
            output.write_text(json.dumps({"error": "转写文字超过字符上限"}, ensure_ascii=False), encoding="utf-8")
            return
        if text:
            rows.append({"start_ms": round(segment.start * 1000), "end_ms": round(segment.end * 1000), "text": text})
    output.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    transcribe(Path(sys.argv[1]), Path(sys.argv[2]), json.loads(sys.argv[3]))
