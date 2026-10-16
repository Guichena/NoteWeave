"""产物契约的具名规则插件，不按产物类型分支。"""

import re
from jsonschema import Draft202012Validator


_SECTION = {"type": "integer", "minimum": 0, "maximum": 79}
_COUNT = {"type": "integer", "minimum": 0, "maximum": 80}
_PARAMETERS = {
    "bullet_count": {"section_index": _SECTION, "min": _COUNT, "max": _COUNT},
    "numbered_item_count": {"section_index": _SECTION, "min": _COUNT, "max": _COUNT},
    "section_terms": {"section_index": _SECTION, "groups": {"type": "array", "minItems": 1, "maxItems": 20,
        "items": {"type": "array", "minItems": 1, "maxItems": 10, "items": {"type": "string", "minLength": 1, "maxLength": 200}}}},
    "section_nonempty": {"section_index": _SECTION},
    "focus_coverage": {}, "no_fabricated_metrics": {}, "quiz_answer_in_options": {"section_index": _SECTION},
}


def validate_rule_parameters(name, parameters):
    properties = _PARAMETERS[name]
    required = ["section_index"] if "section_index" in properties else []
    if name == "section_terms":
        required.append("groups")
    errors = list(Draft202012Validator({"type": "object", "properties": properties,
        "required": required, "additionalProperties": False}).iter_errors(parameters))
    if errors or parameters.get("min", 0) > parameters.get("max", 80):
        raise ValueError(f"契约规则参数不符合约定：{name}")


def _body(rule, result):
    sections = result.result_payload.get("sections", [])
    index = rule.parameters.get("section_index", 0)
    section = sections[index] if isinstance(sections, list) and index < len(sections) else None
    return str(section.get("body") or "") if isinstance(section, dict) else ""


def _outcome(passed, detail, **metadata):
    return {"passed": passed, "detail": detail, "metadata": metadata}


def _bullet_count(rule, result, plan):
    count = sum(bool(re.match(r"^\s*[-*+]\s+\S", line)) for line in _body(rule, result).splitlines())
    minimum, maximum = rule.parameters.get("min", 0), rule.parameters.get("max", 80)
    return _outcome(minimum <= count <= maximum, f"列表条数：{count}，要求 {minimum} 至 {maximum}", count=count)


def _numbered_item_count(rule, result, plan):
    count = len(re.findall(r"(?m)^\s*\d+[.、)]\s*\S", _body(rule, result)))
    minimum, maximum = rule.parameters.get("min", 0), rule.parameters.get("max", 80)
    return _outcome(minimum <= count <= maximum, f"编号条数：{count}，要求 {minimum} 至 {maximum}", count=count)


def _section_terms(rule, result, plan):
    text = _body(rule, result).casefold()
    missing = [group for group in rule.parameters["groups"] if not any(term.casefold() in text for term in group)]
    return _outcome(not missing, "章节主题词检查通过" if not missing else "章节缺少必需主题词", missing_groups=missing)


def _section_nonempty(rule, result, plan):
    passed = bool(_body(rule, result).strip())
    return _outcome(passed, "章节正文完整" if passed else "章节正文为空")


def _focus_coverage(rule, result, plan):
    sections = result.result_payload.get("sections", [])
    text = "\n".join(str(s.get("body") or "") for s in sections if isinstance(s, dict)) if isinstance(sections, list) else ""
    missing = [point for point in plan.execution_spec.focus_points if point not in text]
    return _outcome(not missing, "用户重点检查通过" if not missing else "正文未覆盖全部用户重点", missing_focus_points=missing, focus_point_count=len(plan.execution_spec.focus_points),
                    matched_focus_point_count=len(plan.execution_spec.focus_points) - len(missing))


_METRIC = re.compile(r"(?<![0-9A-Za-z_.])[-+]?\d+(?:\.\d+)?\s*(?:%|％|ms\b|毫秒|秒|分钟|小时|倍|万元|亿元|GB\b|MB\b|QPS\b)", re.I)


def _no_fabricated_metrics(rule, result, plan):
    content = result.result_payload.get("canonical_content_objects", [])
    source = "\n".join(str(c.get("plain_text") or "") for c in content if isinstance(c, dict)) if isinstance(content, list) else ""
    sections = result.result_payload.get("sections", [])
    supported_metrics = {re.sub(r"\s+", "", metric).casefold() for metric in _METRIC.findall(source)}
    unsupported = []
    for section in sections if isinstance(sections, list) else []:
        if not isinstance(section, dict):
            continue
        for metric in _METRIC.findall(str(section.get("body") or "")):
            if re.sub(r"\s+", "", metric).casefold() not in supported_metrics:
                unsupported.append(metric)
    return _outcome(not unsupported, "数字指标在素材中可找到" if not unsupported else "正文出现素材中没有的数字指标",
                    unsupported_metrics=list(dict.fromkeys(unsupported)), verification_scope="LITERAL_METRIC_PRESENCE")


def _quiz_answer_in_options(rule, result, plan):
    text = _body(rule, result)
    blocks = re.split(r"(?m)^\s*\d+[.、)]\s+", text)
    invalid = []
    for index, block in enumerate(blocks):
        options = set(re.findall(r"(?m)^\s*([A-Z])[.、):：]\s*\S", block))
        answer = re.search(r"(?:答案|Answer)\s*[：:]\s*([A-Z])\b", block, re.I)
        if options and (answer is None or answer.group(1).upper() not in options):
            invalid.append(index)
        elif answer and not options:
            invalid.append(index)
    return _outcome(not invalid, "选择题答案属于选项" if not invalid else "选择题答案缺失或不在选项中", invalid_question_indexes=invalid)


_RULES = {"bullet_count": _bullet_count, "numbered_item_count": _numbered_item_count,
          "section_terms": _section_terms, "section_nonempty": _section_nonempty,
          "focus_coverage": _focus_coverage, "no_fabricated_metrics": _no_fabricated_metrics,
          "quiz_answer_in_options": _quiz_answer_in_options}


def run_rule(rule, result, plan):
    return _RULES[rule.name](rule, result, plan)
