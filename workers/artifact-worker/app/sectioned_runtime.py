"""按章节隔离素材并生成正文，引用与一致性调整分别执行。"""

from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from copy import deepcopy
from hashlib import sha256
import json
import re
from types import SimpleNamespace

from app.node_contracts import STATE_FIELDS
from app.node_executor import NodeExecutionError, NodeExecutionResult, NodeExecutor


_TEXT = {"type": "string", "minLength": 1, "pattern": r"\S"}
_SOURCE = {"type": "object", "additionalProperties": False,
    "properties": {"chunk_id": _TEXT, "title": _TEXT, "content": _TEXT},
    "required": ["chunk_id", "title", "content"]}
_SOURCES = {"type": "array", "maxItems": 20, "items": _SOURCE}
_INDEX = {"type": "integer", "minimum": 0, "maximum": 4095}
_PARAGRAPH = {"type": "object", "additionalProperties": False,
    "properties": {"heading": _TEXT, "section_index": _INDEX, "paragraph_index": _INDEX, "text": _TEXT},
    "required": ["heading", "section_index", "paragraph_index", "text"]}
_ISSUE = {"type": "object", "additionalProperties": False,
    "properties": {"section_index": _INDEX, "paragraph_index": _INDEX,
        "issue_kind": {"enum": ["TERM_INCONSISTENCY", "DUPLICATE"]}, "detail": _TEXT},
    "required": ["section_index", "paragraph_index", "issue_kind", "detail"]}
_ISSUES = {"type": "array", "maxItems": 160, "items": _ISSUE}


def _schema(properties):
    return {"type": "object", "additionalProperties": False, "properties": deepcopy(properties),
            "required": list(properties)}


def _node(name, input_properties, output_properties, suffix=""):
    return SimpleNamespace(node_id=name + suffix, node_kind="LLM_STEP", prompt_recipe_id=f"node.{name}.v1",
        input_schema=_schema(input_properties), output_schema=_schema(output_properties))


def _trace(name, steps, **extra):
    return {"node_id": name, "node_kind": "LLM_STEP", "purpose": f"artifact.node.{name}",
        "prompt_recipe_id": f"node.{name}.v1", "status": "COMPLETED", "steps": deepcopy(steps),
        "llm_calls": sum(step.get("llm_calls", 0) for step in steps),
        "validation_errors": [error for step in steps for error in step.get("validation_errors", [])], **extra}


def _material_index(inputs):
    passages = {}
    for passage in inputs["material_passages"]:
        key = passage["chunk_id"]
        if key in passages and any(passages[key].get(field) != passage.get(field) for field in ("title", "content")):
            raise ValueError("同一片段标识对应不同原文，不能生成章节")
        passages[key] = passage
    return passages


def _allowed_sources(heading, inputs):
    matches = [pack for pack in inputs["section_materials"] if pack["heading"] == heading]
    if len(matches) != 1:
        raise ValueError("章节素材包缺失或存在歧义")
    indexed = _material_index(inputs)
    sources = {}
    for assignment in matches[0]["passages"]:
        key = assignment["chunk_id"]
        source = indexed.get(key)
        if source is None:
            raise ValueError("章节素材引用了不存在的原文片段")
        limit = assignment.get("selected_chars")
        if limit is None and "content" in assignment:
            if not source["content"].startswith(assignment["content"]):
                raise ValueError("章节分配的原文与公共片段不一致")
            limit = len(assignment["content"])
        if not isinstance(limit, int) or isinstance(limit, bool) or not 0 <= limit <= len(source["content"]):
            raise ValueError("章节素材长度不符合真实原文范围")
        if limit:
            value = {"chunk_id": key, "title": source["title"], "content": source["content"][:limit]}
            if key not in sources or len(sources[key]["content"]) < limit:
                sources[key] = value
    if len(sources) > 20 or sum(len(source["content"]) for source in sources.values()) > 64_000:
        raise ValueError("章节素材超过执行上限，不能截断后继续生成")
    return sources


def validate_chapter_plan(output, inputs):
    chapters = output["chapter_plan"]
    outline = inputs["generation_context"]["outline"]
    if [chapter["heading"] for chapter in chapters] != outline or len(outline) != len(set(outline)):
        raise ValueError("章节计划必须完整保留冻结大纲的标题与顺序")
    ids = [chapter["section_id"] for chapter in chapters]
    if len(ids) != len(set(ids)):
        raise ValueError("章节计划的章节标识不能重复")
    required = inputs["generation_context"].get("output_contract", {}).get("citation", {}).get("density") == "PER_SECTION"
    for chapter in chapters:
        sources = _allowed_sources(chapter["heading"], inputs)
        if any(key not in sources for key in chapter["chunk_ids"]):
            raise ValueError("章节计划不能引用其他章节或未分配的片段")
        if required and not chapter["chunk_ids"]:
            raise ValueError("必需引用的章节计划不能没有真实片段")


def plan_outline(inputs, llm_client):
    node = _node("outline_planner", {key: STATE_FIELDS[key] for key in (
        "material_passages", "section_materials", "generation_context")},
        {"chapter_plan": STATE_FIELDS["chapter_plan"]})
    return NodeExecutor(llm_client, output_guards={node.prompt_recipe_id: validate_chapter_plan}).execute(node, inputs)


def _selected_sources(chapter, inputs):
    allowed = _allowed_sources(chapter["heading"], inputs)
    if len(chapter["chunk_ids"]) != len(set(chapter["chunk_ids"])) \
            or any(key not in allowed for key in chapter["chunk_ids"]):
        raise ValueError("章节计划含重复或未分配的片段")
    return [allowed[key] for key in chapter["chunk_ids"]]


def _parallel(name, items, worker, llm_client, max_workers):
    if not isinstance(max_workers, int) or isinstance(max_workers, bool) or not 1 <= max_workers <= 8:
        raise ValueError("章节并发数量必须为 1 至 8")
    reasons = []
    thread_count = getattr(llm_client, "current_thread_call_count", None)
    used_workers = min(max_workers, max(1, len(items)))
    if not callable(thread_count):
        used_workers = 1
        reasons.append("ARTIFACT_SECTIONED_THREAD_CALL_ACCOUNTING_UNAVAILABLE")
    results, failures, steps = {}, [], {}
    next_index = 0
    with ThreadPoolExecutor(max_workers=used_workers, thread_name_prefix=f"artifact-{name}") as pool:
        pending = {}

        def submit_next():
            nonlocal next_index
            index = next_index
            next_index += 1
            pending[pool.submit(worker, items[index])] = index

        while next_index < min(used_workers, len(items)):
            submit_next()
        while pending:
            done, _ = wait(pending, return_when=FIRST_COMPLETED)
            for future in sorted(done, key=lambda item: pending[item]):
                index = pending.pop(future)
                if future.cancelled():
                    continue
                try:
                    result = future.result()
                    results[index] = result.output
                    steps[index] = result.trace
                except NodeExecutionError as exc:
                    steps[index] = exc.trace
                    failures.append(index)
                except Exception:
                    steps[index] = {"status": "FAILED", "llm_calls": 0,
                        "reason_code": "ARTIFACT_SECTIONED_CHILD_EXECUTION_FAILED", "validation_errors": []}
                    failures.append(index)
            if failures:
                # 取消尚未启动的请求；已运行的请求必须等待结算，避免遗漏调用记录。
                for future in pending:
                    future.cancel()
            else:
                while next_index < len(items) and len(pending) < used_workers:
                    submit_next()
    trace = _trace(name, [steps[index] for index in sorted(steps)], requested_workers=max_workers,
        used_workers=used_workers, degradation_reasons=reasons)
    if failures:
        trace.update(status="FAILED", reason_code="ARTIFACT_SECTIONED_CHILD_FAILED",
            failed_sections=[items[index]["heading"] for index in sorted(failures)])
        raise NodeExecutionError("章节节点执行失败，未发布部分正文", trace)
    return [results[index] for index in range(len(items))], trace


def write_sections(inputs, llm_client, max_workers=3):
    validate_chapter_plan({"chapter_plan": inputs["chapter_plan"]}, inputs)
    section_schema = deepcopy(STATE_FIELDS["sections"]["items"])
    section_schema["properties"]["body"]["maxLength"] = 16_000
    section_schema["properties"]["source_refs"]["maxItems"] = 0

    def worker(chapter):
        child_inputs = {"chapter": deepcopy(chapter), "generation_context": deepcopy(inputs["generation_context"]),
                        "material_passages": _selected_sources(chapter, inputs)}
        node = _node("section_writer", {"chapter": STATE_FIELDS["chapter_plan"]["items"],
            "generation_context": STATE_FIELDS["generation_context"], "material_passages": _SOURCES},
            {"section": section_schema}, suffix=f"_{chapter['section_id']}")
        def guard(value, projected):
            if value["section"]["heading"] != projected["chapter"]["heading"]:
                raise ValueError("写作只能返回当前章节，不能改写其他章节")
        result = NodeExecutor(llm_client, output_guards={node.prompt_recipe_id: guard}).execute(node, child_inputs)
        result.trace["section_heading"] = chapter["heading"]
        return result

    values, trace = _parallel("section_writer", inputs["chapter_plan"], worker, llm_client, max_workers)
    return NodeExecutionResult({"sections": [value["section"] for value in values]}, trace)


def cite_sections(inputs, llm_client, max_workers=3):
    chapters = {chapter["heading"]: chapter for chapter in inputs["chapter_plan"]}
    if len(chapters) != len(inputs["chapter_plan"]) or len({s["heading"] for s in inputs["sections"]}) != len(inputs["sections"]):
        raise ValueError("引用节点的章节标题不能重复")
    assignment_schema = deepcopy(STATE_FIELDS["citation_assignments"]["items"])
    required = inputs["generation_context"].get("output_contract", {}).get("citation", {}).get("density") == "PER_SECTION"
    if required:
        assignment_schema["properties"]["chunk_ids"]["minItems"] = 1

    def worker(section):
        chapter = chapters.get(section["heading"])
        if chapter is None:
            raise ValueError("引用节点不能处理冻结大纲之外的章节")
        materials = _selected_sources(chapter, inputs)
        node = _node("section_citer", {"section": STATE_FIELDS["sections"]["items"], "material_passages": _SOURCES},
            {"citation_assignment": assignment_schema}, suffix=f"_{chapter['section_id']}")
        def guard(value, projected):
            assignment = value["citation_assignment"]
            if assignment["heading"] != projected["section"]["heading"] \
                    or any(key not in {p["chunk_id"] for p in projected["material_passages"]}
                           for key in assignment["chunk_ids"]):
                raise ValueError("引用节点只能使用本章节已分配的真实片段")
        result = NodeExecutor(llm_client, output_guards={node.prompt_recipe_id: guard}).execute(node,
            {"section": deepcopy(section), "material_passages": materials})
        result.trace["section_heading"] = section["heading"]
        assignment = result.output["citation_assignment"]
        source_titles = {p["chunk_id"]: p["title"] for p in materials}
        result.output = {"section": {**deepcopy(section),
            "source_refs": list(dict.fromkeys(source_titles[key] for key in assignment["chunk_ids"]))},
            "citation_assignment": assignment}
        return result

    values, trace = _parallel("section_citer", inputs["sections"], worker, llm_client, max_workers)
    return NodeExecutionResult({"sections": [value["section"] for value in values],
        "citation_assignments": [value["citation_assignment"] for value in values]}, trace)


def document_content_digest(sections):
    value = [{"heading": section["heading"], "body": section["body"]} for section in sections]
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()


def paragraph_texts(body):
    """返回与一致性诊断索引相同的原始段落，不改空白或正文内容。"""
    return [piece for piece in re.split(r"(\n[ \t]*\n)", body)[::2] if piece.strip()]


def _paragraph_snapshot(sections):
    paragraphs, segments = [], []
    for section_index, section in enumerate(sections):
        pieces = re.split(r"(\n[ \t]*\n)", section["body"])
        mapping = {}
        for piece_index in range(0, len(pieces), 2):
            if not pieces[piece_index].strip():
                continue
            paragraph_index = len(mapping)
            mapping[paragraph_index] = piece_index
            paragraphs.append({"heading": section["heading"], "section_index": section_index,
                "paragraph_index": paragraph_index, "text": pieces[piece_index]})
        segments.append((pieces, mapping))
    if len(paragraphs) > 4096 or sum(len(item["text"]) for item in paragraphs) > 64_000:
        raise ValueError("全文一致性输入超过执行上限，不能截断后判定通过")
    return paragraphs, segments


def _location(item):
    return item["section_index"], item["paragraph_index"]


def _check_consistency(sections, context, llm_client, suffix=""):
    try:
        paragraphs, _ = _paragraph_snapshot(sections)
    except ValueError as exc:
        trace = _trace("document_consistency", [])
        trace.update(status="FAILED", reason_code="ARTIFACT_DOCUMENT_CONTEXT_LIMIT_EXCEEDED")
        raise NodeExecutionError(str(exc), trace) from exc
    known = {_location(item) for item in paragraphs}
    node = _node("document_consistency", {"paragraphs": {"type": "array", "maxItems": 4096,
        "items": _PARAGRAPH}, "generation_context": STATE_FIELDS["generation_context"]},
        {"issues": _ISSUES}, suffix=suffix)
    def guard(value, projected):
        positions = [_location(issue) for issue in value["issues"]]
        if len(positions) != len(set(positions)) or any(position not in known for position in positions):
            raise ValueError("一致性问题必须对应真实且唯一的段落位置")
    return NodeExecutor(llm_client, output_guards={node.prompt_recipe_id: guard}).execute(node,
        {"paragraphs": paragraphs, "generation_context": deepcopy(context)})


def check_document_consistency(inputs, llm_client, repair=True):
    original = deepcopy(inputs["sections"])
    before_digest = document_content_digest(original)
    review = _check_consistency(original, inputs["generation_context"], llm_client)
    steps, first_issues, sections, revisions = [review.trace], review.output["issues"], original, []
    if first_issues and repair:
        paragraphs, segments = _paragraph_snapshot(original)
        locations = {_location(issue) for issue in first_issues}
        problem_paragraphs = [paragraph for paragraph in paragraphs if _location(paragraph) in locations]
        node = _node("paragraph_reviser", {"problem_paragraphs": {"type": "array", "maxItems": 160,
            "items": _PARAGRAPH}, "issues": _ISSUES, "generation_context": STATE_FIELDS["generation_context"]},
            {"patches": {"type": "array", "maxItems": 160, "items": _schema({
                "section_index": _INDEX, "paragraph_index": _INDEX, "text": {"type": "string"}})}})
        def guard(value, projected):
            returned = [_location(patch) for patch in value["patches"]]
            if len(returned) != len(set(returned)) or set(returned) != locations:
                raise ValueError("段落修正必须完整匹配问题段落，不能修改未判问题的正文")
            candidate = _apply_patches(original, segments, value["patches"])
            if any(not section["body"].strip() for section in candidate):
                raise ValueError("段落修正不能删除整个必需章节的正文")
        try:
            revised = NodeExecutor(llm_client, output_guards={node.prompt_recipe_id: guard}).execute(node,
                {"problem_paragraphs": problem_paragraphs, "issues": first_issues,
                 "generation_context": deepcopy(inputs["generation_context"])})
            steps.append(revised.trace)
            sections = _apply_patches(original, segments, revised.output["patches"])
            original_paragraphs = {_location(item): item["text"] for item in problem_paragraphs}
            revisions = [{"section_index": patch["section_index"], "paragraph_index": patch["paragraph_index"]}
                         for patch in revised.output["patches"]
                         if patch["text"] != original_paragraphs[_location(patch)]]
            review = _check_consistency(sections, inputs["generation_context"], llm_client, "_after_revision")
            steps.append(review.trace)
        except NodeExecutionError as exc:
            trace = _trace("document_consistency", [*steps, exc.trace])
            trace.update(status="FAILED", reason_code="ARTIFACT_DOCUMENT_CONSISTENCY_FAILED")
            raise NodeExecutionError("全文一致性调整未完成，未返回部分修正的正文", trace) from exc
    digest = document_content_digest(sections)
    diagnostics = {"status": "FAIL" if review.output["issues"] else "PASS", "issues": review.output["issues"],
        "original_issues": first_issues, "revised_paragraphs": revisions,
        "before_content_digest": before_digest, "content_digest": digest, "checked_content_digest": digest,
        "repair_enabled": repair}
    return NodeExecutionResult({"sections": sections, "consistency_trace": diagnostics},
        _trace("document_consistency", steps))


def _apply_patches(original, segments, patches):
    sections = deepcopy(original)
    changed = set()
    copied = deepcopy(segments)
    for patch in patches:
        index, position = _location(patch)
        pieces, mapping = copied[index]
        pieces[mapping[position]] = patch["text"]
        changed.add(index)
    for index in changed:
        sections[index]["body"] = "".join(copied[index][0])
        if sections[index]["body"] != original[index]["body"]:
            sections[index]["source_refs"] = []
    return sections
