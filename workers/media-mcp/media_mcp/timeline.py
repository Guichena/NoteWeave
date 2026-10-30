from __future__ import annotations


def align(segments: list[dict], frames: list[dict], part: int, duration_ms: int) -> list[dict]:
    """关键帧放入包含其 PTS 的语音段；静音区间单独成段，避免错误归属到其他语音。"""
    rows = []
    for segment in segments:
        start, end = segment["start_ms"], segment["end_ms"]
        if type(start) is not int or type(end) is not int:
            raise ValueError("转写段时间范围无效")
        # ASR 偶尔产出零长或超出末尾的片段，跳过该段而不是让整次解析失败。
        if not 0 <= start < end <= duration_ms + 1000:
            continue
        if start >= duration_ms:
            continue
        rows.append({"part": part, "start_ms": start, "end_ms": min(end, duration_ms),
                     "text": segment["text"], "frames": []})
    rows.sort(key=lambda row: row["start_ms"])
    for frame in frames:
        target = next((row for row in rows if row["start_ms"] <= frame["at_ms"] < row["end_ms"]), None)
        if target is None:
            target = {"part": part, "start_ms": frame["at_ms"], "end_ms": min(frame["at_ms"] + 1, duration_ms),
                      "text": "", "frames": []}
            rows.append(target)
        target["frames"].append(frame)
    return sorted(rows, key=lambda row: (row["part"], row["start_ms"]))
