"""类型化节点分派、字段隔离与严格输出校验。"""

from copy import deepcopy
from dataclasses import dataclass
import json
import math
import re
from typing import Callable

from jsonschema import Draft202012Validator, validators
from pydantic import ConfigDict, RootModel

from app.json_repair import parse_json_payload
from app.node_prompts import resolve_node_prompt_recipe
from app.execution_heartbeat import ArtifactExecutionSuperseded


_NODE_KINDS = frozenset({"LLM_STEP", "RETRIEVE", "VERIFY", "REPAIR", "TOOL"})
_NUMBER = re.compile(r"[-+]?\d+(?:[.,]\d+)*(?:\s*[%％])?")
_METRIC = re.compile(
    r"(?<![0-9A-Za-z_.])[-+]?\d+(?:\.\d+)?\s*"
    r"(?:%|％|ms\b|毫秒|秒|分钟|小时|倍|万元|亿元|GB\b|MB\b|QPS\b)", re.I
)

# JSON Schema 的 integer 默认接受 1.0，节点协议要求实际整数且禁止布尔值冒充数字。
_TYPES = Draft202012Validator.TYPE_CHECKER.redefine(
    "integer", lambda _checker, value: isinstance(value, int) and not isinstance(value, bool)
).redefine(
    "number", lambda _checker, value: isinstance(value, (int, float))
    and not isinstance(value, bool) and math.isfinite(value)
)
_StrictValidator = validators.extend(Draft202012Validator, type_checker=_TYPES)


class _NodeOutput(RootModel[dict[str, object]]):
    model_config = ConfigDict(strict=True)


@dataclass
class NodeExecutionResult:
    output: dict[str, object]
    trace: dict[str, object]


class NodeExecutionError(RuntimeError):
    def __init__(self, message: str, trace: dict[str, object]) -> None:
        super().__init__(message)
        self.trace = deepcopy(trace)


class NodeExecutor:
    def __init__(self, llm_client=None, *, handlers=None, output_guards=None, llm_handlers=None) -> None:
        self.llm_client = llm_client
        self.handlers = dict(handlers or {})
        self.llm_handlers = dict(llm_handlers or {})
        self.output_guards: dict[str, Callable] = dict(DEFAULT_OUTPUT_GUARDS)
        self.output_guards.update(output_guards or {})

    def execute(self, node, state: dict[str, object]) -> NodeExecutionResult:
        kind = str(node.node_kind)
        trace = {
            "node_id": str(node.node_id), "node_kind": kind,
            "prompt_recipe_id": str(node.prompt_recipe_id), "purpose": "",
            "llm_calls": 0, "validation_errors": [], "status": "RUNNING",
            "input_fields": [], "output_fields": [],
        }
        try:
            if kind not in _NODE_KINDS:
                raise ValueError(f"节点类型未注册：{kind}")
            _validate_schema(node.input_schema)
            _validate_schema(node.output_schema)
            declared = node.input_schema["properties"]
            projected = deepcopy({key: state[key] for key in declared if key in state})
            trace["input_fields"] = sorted(projected)
            _validate_value(projected, node.input_schema, "输入")
            if kind == "LLM_STEP":
                handler = self.llm_handlers.get(node.prompt_recipe_id)
                if handler is not None:
                    result = handler(projected, node)
                    trace.update({key: deepcopy(value) for key, value in result.trace.items()
                        if key not in {"node_id", "node_kind", "prompt_recipe_id", "input_fields", "output_fields"}})
                    output = self._validate_output(result.output, projected, node)
                    trace.update(status="COMPLETED", output_fields=sorted(output))
                    return NodeExecutionResult(output, trace)
                return self._execute_llm(node, projected, trace)
            handler = self.handlers.get(kind)
            if not callable(handler):
                raise ValueError(f"节点类型缺少执行器：{kind}")
            output = self._validate_output(handler(projected, node), projected, node)
            trace.update(status="COMPLETED", output_fields=sorted(output))
            return NodeExecutionResult(output, trace)
        except ArtifactExecutionSuperseded:
            raise
        except NodeExecutionError as exc:
            exc.trace.update(node_id=trace["node_id"], node_kind=trace["node_kind"],
                prompt_recipe_id=trace["prompt_recipe_id"], input_fields=trace["input_fields"])
            raise
        except (ValueError, TypeError) as exc:
            _fail(str(exc), trace)
        except Exception:
            _fail("节点执行失败", trace)

    def _execute_llm(self, node, projected, trace) -> NodeExecutionResult:
        recipe = resolve_node_prompt_recipe(node.prompt_recipe_id)
        trace["purpose"] = recipe.purpose
        if self.llm_client is None:
            _fail("节点模型未配置", trace, "ARTIFACT_NODE_LLM_UNAVAILABLE")
        errors = []
        for attempt in range(2):
            before = _call_count(self.llm_client)
            try:
                raw = self.llm_client.complete_json(recipe.purpose, {
                    "node_id": node.node_id, "prompt_recipe_id": recipe.recipe_id,
                    "instruction": recipe.instruction,
                    "input_schema": deepcopy(node.input_schema),
                    "output_schema": deepcopy(node.output_schema),
                    "inputs": deepcopy(projected),
                    "validation_errors": list(errors) if attempt else [],
                })
            except ArtifactExecutionSuperseded:
                trace["llm_calls"] += _actual_calls(self.llm_client, before)
                raise
            except Exception:
                trace["llm_calls"] += _actual_calls(self.llm_client, before)
                _fail("节点模型调用失败", trace, "ARTIFACT_NODE_LLM_CALL_FAILED")
            trace["llm_calls"] += _actual_calls(self.llm_client, before)
            if not isinstance(raw, str) or not raw.strip():
                _fail("节点模型未返回内容或调用预算已耗尽", trace,
                      "ARTIFACT_NODE_LLM_UNAVAILABLE_OR_BUDGET_EXHAUSTED")
            try:
                parsed = _parse_node_output(raw)
                output = self._validate_output(parsed, projected, node)
            except (ValueError, TypeError) as exc:
                errors = [str(exc)[:2000]]
                trace["validation_errors"].extend(errors)
                continue
            trace.update(status="COMPLETED", output_fields=sorted(output))
            return NodeExecutionResult(output, trace)
        _fail("节点模型输出两次校验失败", trace, "ARTIFACT_NODE_OUTPUT_INVALID")

    def _validate_output(self, output, projected, node):
        # 根对象使用 Pydantic strict 校验，节点动态字段再按目录 Schema 校验。
        try:
            validated = _NodeOutput.model_validate(output).root
        except ValueError as exc:
            raise ValueError("节点输出必须为 JSON object") from exc
        _validate_value(validated, node.output_schema, "输出")
        guard = self.output_guards.get(node.prompt_recipe_id)
        if guard is not None:
            guard(validated, deepcopy(projected))
        return deepcopy(validated)


def _validate_schema(schema):
    if not isinstance(schema, dict) or schema.get("type") != "object" \
            or not isinstance(schema.get("properties"), dict) \
            or schema.get("additionalProperties") is not False:
        raise ValueError("节点 Schema 必须声明 object 字段并禁止额外字段")
    _reject_references(schema)
    Draft202012Validator.check_schema(schema)


def _parse_node_output(raw):
    """先解析原文候选，合法的 fenced JSON 引文也不经过容错改写。"""
    stripped = raw.strip()
    candidates = [stripped]
    candidates.extend(match.group(1).strip() for match in re.finditer(
        r"```(?:json)?\s*(.*?)```", stripped, flags=re.IGNORECASE | re.DOTALL))
    start, end = stripped.find("{"), stripped.rfind("}")
    if start >= 0 and end > start:
        candidates.append(stripped[start:end + 1])
    for candidate in dict.fromkeys(candidates):
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            continue
    return parse_json_payload(raw)


def _reject_references(value, depth=0):
    if depth > 64:
        raise ValueError("节点 Schema 嵌套过深")
    if isinstance(value, dict):
        if "$ref" in value or "$dynamicRef" in value:
            raise ValueError("节点 Schema 不允许外部或递归引用")
        for child in value.values():
            _reject_references(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            _reject_references(child, depth + 1)


def _validate_value(value, schema, direction):
    errors = sorted(_StrictValidator(schema).iter_errors(value),
                    key=lambda error: tuple(str(part) for part in error.absolute_path))
    if errors:
        details = []
        for error in errors[:20]:
            path = ".".join(str(part) for part in error.absolute_path) or "$"
            # 不记录完整输入值，诊断保留具体路径与违反的约束。
            detail = f"{direction}字段 {path} 不符合 {error.validator} 约束"
            if direction == "输出":
                detail += f"：{error.message[:400]}"
            details.append(detail)
        raise ValueError("；".join(details))


def _call_count(client):
    per_thread = getattr(client, "current_thread_call_count", None)
    if callable(per_thread):
        count = per_thread()
        return count if isinstance(count, int) and not isinstance(count, bool) else None
    summary = getattr(client, "usage_summary", None)
    if callable(summary):
        count = summary().get("current_call_count")
        return count if isinstance(count, int) else None
    calls = getattr(client, "calls", None)
    return len(calls) if isinstance(calls, list) else None


def _actual_calls(client, before):
    after = _call_count(client)
    return max(0, after - before) if before is not None and after is not None else 1


def _fail(message, trace, reason_code="ARTIFACT_NODE_EXECUTION_FAILED"):
    trace.update(status="FAILED", reason_code=reason_code)
    raise NodeExecutionError(message, trace)


def _passage_map(inputs):
    passages = {}
    for passage in inputs.get("material_passages", []):
        key = passage["chunk_id"]
        if key in passages and passages[key] != passage:
            raise ValueError("同一片段标识对应了不同素材")
        passages[key] = passage
    return passages


def _facts_by_id(facts):
    mapped = {}
    for fact in facts:
        if fact["fact_id"] in mapped:
            raise ValueError("事实标识不能重复")
        mapped[fact["fact_id"]] = fact
    return mapped


def validate_extracted_facts(output, inputs):
    passages = _passage_map(inputs)
    facts = _facts_by_id(output["facts"])
    for fact in facts.values():
        passage = passages.get(fact["chunk_id"])
        if passage is None:
            raise ValueError(f"事实引用了未知片段：{fact['fact_id']}")
        if not fact["quote"].strip() or fact["quote"] not in passage["content"]:
            raise ValueError(f"事实引文不属于对应片段的连续原文：{fact['fact_id']}")


def _numbers(text):
    return {re.sub(r"\s+", "", token).casefold() for token in _NUMBER.findall(text)}


def _metrics(text):
    return {re.sub(r"\s+", "", token).casefold() for token in _METRIC.findall(text)}


def _check_numeric_grounding(text, quote, label):
    if not _numbers(text).issubset(_numbers(quote)) or not _metrics(text).issubset(_metrics(quote)):
        raise ValueError(f"{label}出现引用原文没有的数字或指标")


def validate_normalized_facts(output, inputs):
    validate_extracted_facts({"facts": inputs["facts"]}, inputs)
    original = _facts_by_id(inputs["facts"])
    normalized = _facts_by_id(output["normalized_facts"])
    for key, fact in normalized.items():
        prior = original.get(key)
        if prior is None or any(fact[field] != prior[field] for field in ("chunk_id", "quote")):
            raise ValueError(f"归一化不能新增事实或更改原文引用：{key}")
        _check_numeric_grounding(fact["text"], fact["quote"], "归一化事实")
        for metric in fact["metrics"]:
            if not metric.strip() or not _NUMBER.search(metric) or metric not in fact["quote"]:
                raise ValueError(f"归一化指标必须逐字来自该事实原文：{key}")
            _check_numeric_grounding(metric, fact["quote"], "归一化指标")


def validate_resume_bullets(output, inputs):
    facts = _facts_by_id(inputs["normalized_facts"])
    validate_extracted_facts({"facts": list(facts.values())}, inputs)
    passages = _passage_map(inputs)
    cited_titles = set()
    for bullet in output["bullets"]:
        ids = bullet["fact_ids"]
        if not ids or len(ids) != len(set(ids)) or any(key not in facts for key in ids):
            raise ValueError("简历要点必须引用非空且已声明的事实标识")
        quote = "\n".join(facts[key]["quote"] for key in ids)
        _check_numeric_grounding(bullet["text"], quote, "简历要点")
        cited_titles.update(passages[facts[key]["chunk_id"]]["title"] for key in ids)
    highlight = [section for section in output["sections"] if section["heading"] == "简历亮点"]
    if len(highlight) != 1:
        raise ValueError("简历输出必须有且仅有一个简历亮点章节")
    lines = highlight[0]["body"].splitlines()
    actual = []
    for line in lines:
        match = re.fullmatch(r"\s*[-*+]\s+(.+?)\s*", line)
        if not match:
            raise ValueError("简历亮点章节只能包含已声明要点的列表行")
        actual.append(match.group(1))
    if actual != [bullet["text"] for bullet in output["bullets"]]:
        raise ValueError("简历亮点章节与已声明要点不一致")
    if set(highlight[0]["source_refs"]) != cited_titles:
        raise ValueError("简历亮点章节引用必须覆盖全部要点的原文资料")
    for section in output["sections"]:
        if not set(section["source_refs"]).issubset(cited_titles):
            raise ValueError("简历章节引用不属于要点事实的原文资料")
        _check_numeric_grounding(section["body"], "\n".join(facts[key]["quote"] for key in facts), "简历章节")


DEFAULT_OUTPUT_GUARDS = {
    "node.resume_highlight_extractor.v1": validate_extracted_facts,
    "node.resume_impact_normalizer.v1": validate_normalized_facts,
    "node.resume_bullet_writer.v1": validate_resume_bullets,
}
