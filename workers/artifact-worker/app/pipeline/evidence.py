"""把任务范围内的资料切句编号，保留来源与视频时间字段。"""

import re


def build_evidence(passages, task):
    scope = {s.source_id: s for s in task.source_scope}
    sentences = []
    seen = set()
    for passage in passages:
        source = scope.get(passage.get("source_id"))
        if source is None or (source.source_snapshot_id and passage.get("source_snapshot_id") != source.source_snapshot_id):
            raise ValueError("检索结果不属于当前任务的冻结资料范围")
        content = passage.get("content", "")
        if not isinstance(content, str):
            raise ValueError("素材正文必须是文本")
        for text in re.split(r"(?<=[。！？!?])\s*|(?<=\.)\s+(?=[A-Z])|\n+", content):
            text = text.strip()
            identity = (passage.get("chunk_id"), text)
            if not text or identity in seen:
                continue
            seen.add(identity)
            sentences.append({"id": f"s{len(sentences) + 1:04d}", "text": text,
                              **{k: passage[k] for k in ("source_id", "source_snapshot_id", "chunk_id", "title", "location",
                                 "part", "start_ms", "end_ms", "frame_ids", "source_url") if k in passage}})
    if not sentences:
        raise ValueError("当前任务没有可用于生成产物的资料正文")
    return {"sentences": sentences}


def facts(value):
    if isinstance(value, dict):
        if "text" in value and "anchors" in value:
            yield value
        for child in value.values():
            yield from facts(child)
    elif isinstance(value, list):
        for child in value:
            yield from facts(child)


def evidence_errors(value, evidence):
    sentences = {v["id"]: v for v in evidence["sentences"]}
    errors = []
    number = re.compile(r"[+-]?[0-9]+(?:[.,][0-9]+)*(?:%|％)?")
    for fact in facts(value):
        anchors = fact.get("anchors")
        if not isinstance(anchors, list) or not anchors or len(anchors) != len(set(anchors)) or any(a not in sentences for a in anchors):
            errors.append("事实引用了不存在或重复的素材编号")
            continue
        original = " ".join(sentences[a]["text"] for a in anchors)
        if set(number.findall(fact["text"])) - set(number.findall(original)):
            errors.append("事实中的数字与引用原句不一致")
    frame_ids = {f for s in sentences.values() for f in s.get("frame_ids", [])}
    def visit(node):
        if isinstance(node, dict):
            if "frame_id" in node and node["frame_id"] not in frame_ids:
                errors.append("插图引用了素材范围外的关键帧")
            for v in node.values(): visit(v)
        elif isinstance(node, list):
            for v in node: visit(v)
    visit(value)
    return errors
