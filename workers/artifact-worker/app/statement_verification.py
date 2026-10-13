"""逐陈述核对实际引用的原文；词项覆盖只作诊断，不替代证据判断。"""

from collections import defaultdict
from hashlib import sha256
import re
from types import SimpleNamespace

from app.node_executor import NodeExecutionError, NodeExecutor


MAX_SECTIONS = 80
MAX_STATEMENTS_PER_SECTION = 80
MAX_PASSAGES_PER_SECTION = 20
MAX_CONTEXT_CHARS = 64_000
MAX_STATEMENT_CHARS = 4000
MAX_TOTAL_PASSAGES = 1600
_LABELS = ["SUPPORTED", "CONTRADICTED", "UNSUPPORTED", "NON_FACTUAL", "UNDETERMINED"]
_CITATION_LABELS = ["SUPPORTED", "PARTIAL", "CONTRADICTED", "UNSUPPORTED", "UNDETERMINED"]
_TEXT = {"type": "string", "minLength": 1, "pattern": r"\S"}
_HIGH_RISK = re.compile(
    r"[0-9A-Za-z]|[一二三四五六七八九十百千万亿]+(?:倍|人|次|秒|年|月|天|项|个|个百分点)"
    r'|[《》"\u201c\u201d]|(?:公司|集团|大学|协会|机构|委员会|团队|部门|项目|平台|系统|协议|框架)'
    r"|^[\u4e00-\u9fff]{2,6}(?:是|为|负责|表示|认为|指出|担任|开发|发布|成立|位于)"
)
# 直接复制只放行含义明确的通用描述；任何未识别词语仍交给模型，避免遗漏中文实体。
_GENERIC_COPY_WORDS = (
    "可核实", "可追溯", "不需要", "不能", "能够", "可以", "需要", "进行", "得到",
    "信息", "资料", "内容", "原文", "数据", "流程", "顺序", "处理", "执行", "记录",
    "说明", "检查", "完整", "真实", "明确", "一致", "清晰", "保存", "读取", "对应",
    "引用", "来源", "证据", "本", "该", "按", "已", "均", "且", "并", "的", "与",
    "在", "中", "有", "无", "不", "应", "是", "为",
)
_GENERIC_COPY = re.compile("(?:" + "|".join(re.escape(word) for word in _GENERIC_COPY_WORDS)
    + r"|[\s，,。.;；！!：:])+\Z")


def _object(properties, required=None):
    return {"type": "object", "properties": properties,
            "required": list(properties) if required is None else required, "additionalProperties": False}


_INPUT_SCHEMA = _object({
    "statements": {"type": "array", "minItems": 1, "maxItems": MAX_STATEMENTS_PER_SECTION,
        "items": _object({"statement_id": _TEXT, "text": _TEXT,
            "citation_chunk_ids": {"type": "array", "maxItems": MAX_PASSAGES_PER_SECTION,
                "items": _TEXT, "uniqueItems": True}})},
    "material_passages": {"type": "array", "maxItems": MAX_PASSAGES_PER_SECTION,
        "items": _object({"chunk_id": _TEXT, "content": _TEXT})},
})
_OUTPUT_SCHEMA = _object({
    "assessments": {"type": "array", "minItems": 1, "maxItems": MAX_STATEMENTS_PER_SECTION,
        "items": _object({"statement_id": _TEXT, "label": {"enum": _LABELS},
            "sufficiently_supported": {"type": "boolean"}, "reason": _TEXT,
            "citations": {"type": "array", "maxItems": MAX_PASSAGES_PER_SECTION,
                "items": _object({"chunk_id": _TEXT, "label": {"enum": _CITATION_LABELS},
                    "quote": _TEXT})}})},
})


def split_statements(body):
    """保留所有非空正文行，移除展示标记后按句末和列表项拆分。"""
    statements = []
    for raw_line in str(body).splitlines():
        line = raw_line.strip()
        line = re.sub(r"^(?:[-*+]\s+|\d+[.、)]\s+|>\s*)", "", line).strip()
        if not line:
            continue
        # 英文句点后须为空白或行尾，避免拆开小数、版本和 URL。
        statements.extend(part.strip() for part in re.split(
            r"(?<=[。！？!?；;])|(?<=\.)(?=\s|$)", line) if part.strip())
    return statements


def _terms(text):
    return set(re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", text.casefold()))


def _term_coverage(text, passages):
    terms = _terms(text)
    source_terms = _terms("\n".join(p["content"] for p in passages))
    return round(len(terms & source_terms) / len(terms), 4) if terms else 0.0


def _validate_assessments(output, inputs):
    requested = {item["statement_id"]: item for item in inputs["statements"]}
    passages = {item["chunk_id"]: item["content"] for item in inputs["material_passages"]}
    assessments = output["assessments"]
    ids = [item["statement_id"] for item in assessments]
    if len(ids) != len(set(ids)) or set(ids) != set(requested):
        raise ValueError("证据判断必须完整返回全部陈述标识，不能遗漏、重复或新增")
    for assessment in assessments:
        expected = set(requested[assessment["statement_id"]]["citation_chunk_ids"])
        citations = assessment["citations"]
        actual = [citation["chunk_id"] for citation in citations]
        if len(actual) != len(set(actual)) or set(actual) != expected:
            raise ValueError("证据判断必须覆盖该陈述已提供的全部引用，不能新增或遗漏")
        for citation in citations:
            if citation["chunk_id"] not in passages or not citation["quote"].strip() \
                    or citation["quote"] not in passages[citation["chunk_id"]]:
                raise ValueError("证据判断的引文必须来自对应片段的连续原文")
        sufficient = assessment["sufficiently_supported"]
        if sufficient != (assessment["label"] == "SUPPORTED"):
            raise ValueError("陈述标签与充分支持标记不一致")
        if sufficient and not any(c["label"] == "SUPPORTED" for c in citations):
            raise ValueError("充分支持的事实陈述必须至少有一个充分支持的真实引用")
        if sufficient and any(c["label"] == "CONTRADICTED" for c in citations):
            raise ValueError("存在矛盾引用的事实陈述不能被认定为充分支持")


def _judge_node():
    return SimpleNamespace(node_id="statement_support_judge", node_kind="LLM_STEP",
        prompt_recipe_id="node.statement_support_judge.v1",
        input_schema=_INPUT_SCHEMA, output_schema=_OUTPUT_SCHEMA)


def _passage_index(material_passages):
    passages, conflicts = {}, set()
    for passage in material_passages:
        key = str(passage.get("chunk_id") or "")
        if not key or not isinstance(passage.get("content"), str) or not passage["content"].strip():
            continue
        if key in passages and any(passages[key].get(field) != passage.get(field)
                                    for field in ("title", "content")):
            conflicts.add(key)
        passages[key] = passage
    return passages, conflicts


def _chapter_passages(heading, global_passages, section_materials):
    if section_materials is None:
        return global_passages, []
    matches = [pack for pack in section_materials if pack.get("heading") == heading]
    if len(matches) != 1:
        return {}, ["章节素材包缺失或同名章节的素材范围不明确"]
    passages, errors = {}, []
    for selected in matches[0].get("passages", []):
        key = str(selected.get("chunk_id") or "")
        source = global_passages.get(key)
        if source is None:
            errors.append("章节素材包引用了不存在的原文片段")
            continue
        if "selected_chars" in selected:
            limit = selected["selected_chars"]
        elif "content" in selected:
            selected_content = selected["content"]
            if not isinstance(selected_content, str) or not source["content"].startswith(selected_content):
                errors.append("章节素材原文与公共片段原文不一致")
                continue
            limit = len(selected_content)
        else:
            limit = len(source["content"])
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 0 or limit > len(source["content"]):
            errors.append("章节素材长度超过真实原文范围")
            continue
        if limit:
            passages[key] = {**source, "content": source["content"][:limit]}
    return passages, errors


def _resolve_citations(refs, passages, ref_kind="AUTO"):
    titles = defaultdict(list)
    for key, passage in passages.items():
        titles[str(passage.get("title") or "")].append(key)
    chunks, unknown = [], []
    for ref in dict.fromkeys(refs):
        by_chunk = {ref} if ref in passages else set()
        if ref_kind == "CHUNK":
            (chunks if by_chunk else unknown).append(ref)
            continue
        by_title = set(titles.get(ref, []))
        if by_chunk and by_title and by_chunk != by_title:
            # 兼容展示标题和片段标识时，不猜测名称碰撞的引用目标。
            unknown.append(ref)
        elif by_chunk:
            chunks.append(ref)
        elif by_title:
            chunks.extend(titles[ref])
        else:
            unknown.append(ref)
    return list(dict.fromkeys(chunks)), unknown


def _not_run(statement, chunks, reason):
    statement.update(label="UNDETERMINED", sufficiently_supported=False,
        assessment_status="NOT_RUN", failure_reasons=[reason],
        citations=[{"chunk_id": key, "label": "UNDETERMINED", "quote": "",
                    "assessment_status": "NOT_RUN"} for key in chunks])


def _copied(statement, passages):
    text = statement["text"]
    if not passages or _HIGH_RISK.search(text) or not _GENERIC_COPY.fullmatch(text) \
            or any(text not in p["content"] for p in passages):
        return False
    statement.update(label="SUPPORTED", sufficiently_supported=True,
        assessment_status="EXACT_COPY", failure_reasons=[],
        citations=[{"chunk_id": p["chunk_id"], "label": "SUPPORTED", "quote": text,
                    "assessment_status": "EXACT_COPY"} for p in passages])
    return True


def _section_support(statements):
    factual = [item for item in statements if item["label"] != "NON_FACTUAL"]
    if not factual:
        return "SUPPORTED" if statements else "NOT_RUN"
    supported = sum(item["label"] == "SUPPORTED" and item["sufficiently_supported"] for item in factual)
    if supported == len(factual):
        return "SUPPORTED"
    if supported:
        return "PARTIAL"
    if all(item["assessment_status"] == "NOT_RUN" or item["label"] == "UNDETERMINED" for item in factual):
        return "NOT_RUN"
    return "UNSUPPORTED"


def verify_statement_support(sections, material_passages, *, llm_client=None,
        citation_density="PER_SECTION", section_materials=None, citation_ref_kind="AUTO"):
    if citation_ref_kind not in {"AUTO", "CHUNK"}:
        raise ValueError("陈述引用类型必须为自动兼容或明确片段标识")
    """按章节批量判断陈述，保留未执行项并据实计算两个引用指标。"""
    passages, conflicts = _passage_index(material_passages)
    material_limit_exceeded = len(material_passages) > MAX_TOTAL_PASSAGES \
        or sum(len(p["content"]) for p in passages.values()) > MAX_CONTEXT_CHARS
    executor = NodeExecutor(llm_client, output_guards={
        "node.statement_support_judge.v1": _validate_assessments})
    required = str(citation_density).upper() == "PER_SECTION"
    results, failed, warnings, traces = [], [], [], []
    call_count = factual_count = supported_count = citation_count = supported_citations = 0
    for section_index, section in enumerate(sections):
        heading = str(section.get("heading") or "")
        scoped, scope_errors = _chapter_passages(heading, passages, section_materials)
        refs = section.get("source_refs", [])
        chunks, unknown = _resolve_citations(refs, scoped, citation_ref_kind)
        selected = [scoped[key] for key in chunks]
        reasons = list(scope_errors)
        if unknown:
            reasons.append("章节含有未知引用或引用超出本章节素材范围")
        if conflicts & set(chunks):
            reasons.append("同一引用片段标识对应的原文存在冲突")
        texts = split_statements(section.get("body", ""))
        if material_limit_exceeded or len(sections) > MAX_SECTIONS or len(texts) > MAX_STATEMENTS_PER_SECTION \
                or len(selected) > MAX_PASSAGES_PER_SECTION \
                or sum(len(p["content"]) for p in selected) > MAX_CONTEXT_CHARS \
                or any(len(text) > MAX_STATEMENT_CHARS for text in texts):
            reasons.append("陈述或引用素材超过证据判断上限，未执行判断")
        statements, pending = [], []
        for position, text in enumerate(texts):
            identifier = sha256(f"{section_index}:{heading}:{position}:{text}".encode("utf-8")).hexdigest()[:24]
            statement = {"statement_id": f"statement-{identifier}", "text": text,
                "term_coverage": _term_coverage(text, selected)}
            statements.append(statement)
            if reasons:
                _not_run(statement, chunks, "；".join(reasons))
            elif not _copied(statement, selected):
                pending.append(statement)
        if pending:
            inputs = {"statements": [{"statement_id": item["statement_id"], "text": item["text"],
                "citation_chunk_ids": chunks} for item in pending],
                "material_passages": [{"chunk_id": p["chunk_id"], "content": p["content"]} for p in selected]}
            try:
                outcome = executor.execute(_judge_node(), inputs)
                trace = {**outcome.trace, "section_heading": heading}
                assessments = {item["statement_id"]: item for item in outcome.output["assessments"]}
                for statement in pending:
                    assessment = assessments[statement["statement_id"]]
                    statement.update(label=assessment["label"],
                        sufficiently_supported=assessment["sufficiently_supported"],
                        assessment_status="ASSESSED", failure_reasons=[] if assessment["sufficiently_supported"]
                            or assessment["label"] == "NON_FACTUAL" else [assessment["reason"]],
                        citations=[{**item, "assessment_status": "ASSESSED"} for item in assessment["citations"]])
            except NodeExecutionError as exc:
                trace = {**exc.trace, "section_heading": heading}
                for statement in pending:
                    _not_run(statement, chunks, str(exc))
            traces.append(trace)
            call_count += trace.get("llm_calls", 0)
        elif reasons:
            traces.append({"node_id": "statement_support_judge", "node_kind": "LLM_STEP",
                "purpose": "artifact.node.statement_support_judge", "status": "NOT_RUN", "llm_calls": 0,
                "section_heading": heading, "reason_code": "ARTIFACT_STATEMENT_SCOPE_OR_LIMIT_INVALID",
                "validation_errors": list(reasons), "input_fields": [], "output_fields": []})
        for statement in statements:
            if statement["label"] != "NON_FACTUAL":
                factual_count += 1
                supported_count += statement["label"] == "SUPPORTED" and statement["sufficiently_supported"]
            citation_count += len(statement["citations"]) + len(unknown)
            if statement["label"] != "NON_FACTUAL":
                supported_citations += sum(c["label"] in {"SUPPORTED", "PARTIAL"} for c in statement["citations"])
            if statement["label"] != "NON_FACTUAL" and not statement["sufficiently_supported"]:
                detail = f"章节 {heading} 的陈述 {statement['statement_id']} 缺少充分证据：{statement['label']}"
                (failed if required else warnings).append(detail)
        if reasons or not statements:
            detail = f"章节 {heading} 无法完成证据判断：" + ("；".join(reasons) if reasons else "没有可判断的正文陈述")
            (failed if required else warnings).append(detail)
        results.append({"heading": heading, "support": _section_support(statements),
            "chunk_ids": chunks, "statements": statements})
    if not sections:
        (failed if required else warnings).append("产物没有可判断的章节")
    return {"status": "FAIL" if failed else "WARN" if warnings else "PASS", "sections": results,
        "citation_recall": round(supported_count / factual_count, 4) if factual_count else None,
        "citation_precision": round(supported_citations / citation_count, 4) if citation_count else None,
        "failed_checks": failed, "warnings": warnings, "llm_calls": call_count,
        "execution_traces": traces}
