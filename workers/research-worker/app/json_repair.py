from __future__ import annotations

import json
import re


def parse_json_payload(text: str, *, allow_truncated: bool = False) -> object | None:
    """Parse common LLM JSON outputs with small, deterministic repairs.

    allow_truncated：输出被长度上限截断时退回到最后一个完整元素并补齐括号。只给成文这类
    能逐段校验、保留部分内容也有用的场景打开，抽取等场景仍按无效 JSON 处理。
    """
    candidates = _json_candidates(text)
    for candidate in candidates:
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            pass
        repaired = _repair_json(candidate)
        try:
            return json.loads(repaired)
        except json.JSONDecodeError:
            continue
    # 输出被长度上限截断时，退回到最后一个完整的元素并补齐括号，保留已经写完的内容
    if not allow_truncated:
        return None
    stripped = text.strip()
    start = min((index for index in (stripped.find("{"), stripped.find("[")) if index >= 0), default=-1)
    if start >= 0:
        return _close_truncated(stripped[start:])
    return None


def _close_truncated(text: str) -> object | None:
    checkpoints: list[tuple[int, str]] = []
    stack: list[str] = []
    in_string = False
    escaped = False
    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char in "{[":
            stack.append("}" if char == "{" else "]")
        elif char in "}]":
            if stack:
                stack.pop()
            checkpoints.append((index + 1, "".join(reversed(stack))))
        elif char == ",":
            checkpoints.append((index, "".join(reversed(stack))))
    for end, closers in reversed(checkpoints[-200:]):
        try:
            return json.loads(_repair_json(text[:end] + closers))
        except json.JSONDecodeError:
            continue
    return None


def _json_candidates(text: str) -> list[str]:
    stripped = text.strip()
    if not stripped:
        return []

    candidates = [stripped]
    fenced_match = re.search(r"```(?:json)?\s*(.*?)```", stripped, flags=re.IGNORECASE | re.DOTALL)
    if fenced_match:
        candidates.insert(0, fenced_match.group(1).strip())

    object_candidate = _slice_between(stripped, "{", "}")
    if object_candidate:
        candidates.append(object_candidate)
    array_candidate = _slice_between(stripped, "[", "]")
    if array_candidate:
        candidates.append(array_candidate)
    return candidates


def _slice_between(text: str, left: str, right: str) -> str:
    start = text.find(left)
    end = text.rfind(right)
    if start == -1 or end == -1 or end <= start:
        return ""
    return text[start:end + 1]


def _repair_json(text: str) -> str:
    without_comments = _strip_line_comments_outside_strings(text)
    without_trailing_commas = re.sub(r",\s*([}\]])", r"\1", without_comments)
    return without_trailing_commas.strip()


def _strip_line_comments_outside_strings(text: str) -> str:
    """Remove JSONC line comments without corrupting ``https://`` string values."""
    result: list[str] = []
    in_string = False
    escaped = False
    index = 0
    while index < len(text):
        char = text[index]
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            result.append(char)
            index += 1
            continue
        if char == "/" and index + 1 < len(text) and text[index + 1] == "/":
            newline = text.find("\n", index + 2)
            if newline < 0:
                break
            result.append("\n")
            index = newline + 1
            continue
        result.append(char)
        index += 1
    return "".join(result)
