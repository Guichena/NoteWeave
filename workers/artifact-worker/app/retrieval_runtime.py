"""按章节检索冻结素材；本地 BM25 用于未接入宿主或 Provider 降级的任务。"""

from collections import Counter
from dataclasses import dataclass
from hashlib import sha256
import math
import re

from pydantic import BaseModel, ConfigDict, Field

from app.models import ArtifactSkillNodeTrace


class RetrievedPassage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    chunk_id: str = Field(min_length=1)
    source_id: str = Field(min_length=1)
    source_snapshot_id: str
    chunk_no: int = Field(ge=0)
    title: str = Field(min_length=1)
    location: str
    content: str = Field(min_length=1)
    score: float = Field(allow_inf_nan=False)


class RetrievalResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    task_id: str
    input_snapshot_id: str
    passages: list[RetrievedPassage] = Field(max_length=20)
    degraded: bool
    degradation_reasons: list[str]


def _terms(text):
    words = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]+", text.casefold())
    result = []
    for word in words:
        if re.fullmatch(r"[\u4e00-\u9fff]+", word):
            result.extend(word)
            result.extend(word[i:i + 2] for i in range(len(word) - 1))
        else:
            result.append(word)
    return result


def _local_corpus(task, content_objects):
    corpus = {}
    window_sources = set()
    for source in task.source_scope:
        for window in source.material_windows:
            text = str(window.get("content", ""))
            if not text.strip():
                continue
            if sha256(text.encode()).hexdigest() != window.get("checksum_sha256"):
                raise ValueError("冻结素材窗口校验失败")
            key = str(window.get("window_id") or "")
            if not key:
                raise ValueError("冻结素材窗口缺少标识")
            corpus[key] = {"chunk_id": key, "source_id": source.source_id,
                "source_snapshot_id": source.source_snapshot_id, "chunk_no": int(window.get("chunk_no", 0)),
                "title": source.title, "location": str(window.get("location_info", "")), "content": text}
            window_sources.add(source.source_id)
    for obj in content_objects:
        if obj.kind == "MIXED_CONTEXT":
            continue  # 合成上下文重复原资料，不能作为独立命中。
        source_id = next((v.removeprefix("source:") for v in obj.source_trace if v.startswith("source:")), obj.cco_id)
        if source_id in window_sources and not obj.metadata.get("acquisition_payload_applied"):
            continue
        snapshot = next((s.source_snapshot_id for s in task.source_scope if s.source_id == source_id), "")
        # 全文分片后打分，后部段落与前部段落有同等检索机会。
        for index, start in enumerate(range(0, len(obj.plain_text), 750)):
            text = obj.plain_text[start:start + 900].strip()
            if not text:
                continue
            key = "local:" + sha256(f"{source_id}:{snapshot}:{start}:{text}".encode()).hexdigest()
            corpus[key] = {"chunk_id": key, "source_id": source_id, "source_snapshot_id": snapshot,
                "chunk_no": index, "title": obj.title, "location": f"chars:{start}:{start + len(text)}", "content": text}
    return sorted(corpus.values(), key=lambda p: p["chunk_id"])


@dataclass(frozen=True)
class _Bm25Index:
    corpus: list[dict[str, object]]
    counts: list[Counter[str]]
    lengths: list[int]
    average_length: float
    document_frequencies: Counter[str]


def _build_bm25_index(corpus):
    counts = [Counter(_terms(p["title"] + " " + p["content"])) for p in corpus]
    lengths = [sum(c.values()) for c in counts]
    average = sum(lengths) / max(1, len(lengths)) or 1
    frequencies = Counter(term for count in counts for term in count)
    return _Bm25Index(corpus, counts, lengths, average, frequencies)


def _bm25(index, query):
    query_terms = set(_terms(query))
    ranked = []
    for passage, count, length in zip(index.corpus, index.counts, index.lengths):
        score = 0.0
        for term in query_terms:
            tf = count[term]
            if not tf:
                continue
            frequency = index.document_frequencies[term]
            idf = math.log(1 + (len(index.corpus) - frequency + 0.5) / (frequency + 0.5))
            score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / index.average_length))
        ranked.append({**passage, "score": score})
    return sorted(ranked, key=lambda p: (-p["score"], p["chunk_id"]))


def execute_retrieve_node(task, plan, content_objects, passage_search=None):
    node = plan.retrieval_node
    if node.node_kind != "RETRIEVE":
        raise ValueError("素材检索节点类型无效")
    if task.replay_availability != "FULL":
        raise ValueError("任务冻结输入不可完整重放")
    headings = list(dict.fromkeys(plan.outline)) or [plan.action_display_name]
    if len(headings) > 80:
        raise ValueError("章节数超出素材检索上限")
    quotient, remainder = divmod(node.total_context_chars, len(headings))
    scope = {s.source_id: s.source_snapshot_id for s in task.source_scope}
    corpus = _local_corpus(task, content_objects)
    local_index = None

    def rank_local(query):
        nonlocal local_index
        # 首次需要降级时建索引，后续章节复用词频与文档频率。
        if local_index is None:
            local_index = _build_bm25_index(corpus)
        return _bm25(local_index, query)

    packs = []
    union = {}
    all_reasons = []
    for index, heading in enumerate(headings):
        query = "。".join(p for p in [(plan.execution_spec.goal or plan.execution_spec.generation_brief)[:1400],
            "、".join(plan.execution_spec.focus_points)[:350], f"查找与章节 {heading[:200]} 相关的资料内容"] if p)
        budget = quotient + (index < remainder)
        reasons = []
        method = "LOCAL_BM25"
        ranked = []
        # 空冻结资料范围只检索当前本地输入，禁止调用范围为空的工作区索引。
        if passage_search is not None and scope:
            response = RetrievalResponse.model_validate(passage_search(task.task_id, query, node.max_passages_per_section))
            if response.task_id != task.task_id or response.input_snapshot_id != task.input_snapshot_id:
                raise ValueError("素材检索响应不属于当前冻结任务")
            for passage in response.passages:
                if passage.source_id not in scope or passage.source_snapshot_id != scope[passage.source_id]:
                    raise ValueError("素材检索响应超出冻结资料范围")
            ranked = [{**p.model_dump(), "origin": "HOST_FROZEN_CHUNK"} for p in response.passages]
            reasons.extend(response.degradation_reasons)
            if response.degraded and not reasons:
                reasons.append("ARTIFACT_RETRIEVAL_PROVIDER_DEGRADED")
            method = "HOST_HYBRID"
            if response.degraded or not ranked:
                reasons.append("ARTIFACT_RETRIEVAL_LOCAL_BM25_FALLBACK")
                local = rank_local(query)
                ranked.extend(local)
                method = "HOST_HYBRID_WITH_LOCAL_BM25" if ranked[:len(response.passages)] else "LOCAL_BM25"
        else:
            reasons.append("ARTIFACT_RETRIEVAL_LOCAL_INPUT" if not scope else "ARTIFACT_RETRIEVAL_ENDPOINT_UNAVAILABLE")
            ranked = rank_local(query)
        selected = []
        seen = set()
        seen_content = set()
        remaining = budget
        for passage in ranked:
            key = passage["chunk_id"]
            content_key = (passage["source_id"], passage["source_snapshot_id"],
                sha256(passage["content"].encode("utf-8")).hexdigest())
            if key in seen or content_key in seen_content or not passage["content"].strip():
                continue
            seen.add(key)
            seen_content.add(content_key)
            if remaining <= 0 or len(selected) >= node.max_passages_per_section:
                break
            content = passage["content"][:remaining]
            truncated = len(content) < len(passage["content"])
            selected.append({**passage, "content": content, "truncated": truncated})
            remaining -= len(content)
            # 同一片段跨章复用，生成公共源表只保留最长的原文前缀。
            if key not in union or len(union[key]["content"]) < len(content):
                union[key] = {**passage, "content": content, "kind": "RETRIEVED_PASSAGE"}
        if not selected:
            reasons.append("ARTIFACT_RETRIEVAL_NO_MATERIAL")
        elif all(p["score"] <= 0 for p in selected):
            reasons.append("ARTIFACT_RETRIEVAL_NO_QUERY_MATCH")
        reasons = list(dict.fromkeys(reasons))
        all_reasons.extend(reasons)
        packs.append({"heading": heading, "query": query, "method": method, "budget_chars": budget,
            "used_chars": budget - remaining, "passages": selected, "degradation_reasons": reasons})
    trace = {"node_id": node.node_id, "node_kind": "RETRIEVE", "total_budget_chars": node.total_context_chars,
        "unique_passage_count": len(union), "degradation_reasons": list(dict.fromkeys(all_reasons)),
        "sections": [{k: v for k, v in pack.items() if k != "passages"} | {
            "passage_ids": [p["chunk_id"] for p in pack["passages"]],
            "source_ids": list(dict.fromkeys(p["source_id"] for p in pack["passages"]))} for pack in packs]}
    node_trace = ArtifactSkillNodeTrace(node_id=node.node_id, skill_key="material_retrieve",
        output_summary=f"为 {len(packs)} 个章节检索到 {len(union)} 个去重片段",
        verification_status="WARN" if all_reasons else "PASS",
        verification_checks=["冻结资料范围", "章节正文预算", "片段去重"])
    return packs, list(union.values()), trace, node_trace
