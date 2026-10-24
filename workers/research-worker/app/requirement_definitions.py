"""将用户明确要求编译成有原文依据、范围和数量的研究项。"""

import re
import unicodedata

_WORDS = {word: index for index, word in enumerate(
    ("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"))}
_DIGITS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
           "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
_COUNT = re.compile(
    r"(?i)(?:至少|不少于|最少|at\s+least|no\s+fewer\s+than|minimum(?:\s+of)?)?\s*"
    r"(?P<number>\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|[一二两三四五六七八九十]+)"
    r"\s*(?:[a-z]+(?:-[a-z]+)*\s+){0,4}"
    r"(?:个|项|种|条|家|篇|组|features?|changes?|findings?|products?|systems?|databases?|frameworks?|"
    r"studies|papers?|organizations?|implementations?|examples?|reasons?|limitations?)(?:\D*)")


def normalized(value):
    return " ".join(unicodedata.normalize("NFC", str(value or "")).split())


def count_value(expression):
    if len(re.findall(r"(?i)\d+|\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten)\b|[一二两三四五六七八九十]+", normalized(expression))) != 1:
        raise ValueError("数量原文只能包含一个明确数值")
    match = _COUNT.fullmatch(normalized(expression))
    if match is None:
        raise ValueError("数量原文必须包含一个明确数量及其单位")
    value = match["number"].casefold()
    if value.isascii() and value.isdigit():
        return int(value)
    if value in _WORDS:
        return _WORDS[value]
    if value in _DIGITS:
        return _DIGITS[value]
    if "十" in value and value.count("十") == 1:
        left, right = value.split("十")
        if (not left or left in _DIGITS) and (not right or right in _DIGITS):
            return _DIGITS.get(left, 1) * 10 + _DIGITS.get(right, 0)
    raise ValueError("数量原文中的数值不符合约定")


def compile_requirements(raw, question, dimensions, entities, *, required=False, structured=False):
    if raw is None and not required:
        return []
    if raw == [] and not required:
        return []
    if not required and isinstance(raw, list):
        # 未启用需求覆盖时显式需求只作参考：个别条目不合格（例如把版本号当成数量）只丢弃该条，不让整份规划作废
        result = []
        for item in raw[:24]:
            try:
                compiled = compile_requirements([item], question, dimensions, entities, required=True, structured=structured)
            except ValueError:
                continue
            result.extend(entry for entry in compiled if entry not in result)
        return result
    if not isinstance(raw, list) or not 1 <= len(raw) <= 24:
        raise ValueError("必须列出 1 到 24 个用户明确要求")
    question = normalized(question)
    labels = {normalized(item["label"]).casefold(): normalized(item["label"]) for item in dimensions}
    subjects = {normalized(item["label"]).casefold(): normalized(item["label"]) for item in entities}
    result, seen = [], set()
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ValueError("用户需求必须为对象")
        text, quote = normalized(item.get("text")), normalized(item.get("question_quote"))
        dimension = labels.get(normalized(item.get("dimension")).casefold())
        if not text or len(text) > 1000 or not quote or len(quote) > 1000 or quote not in question:
            raise ValueError("显式需求需要用户问题中的准确原文，文本不能超过 1000 字符")
        if dimension is None:
            available = [label[:120] for label in labels.values()][:12]
            raise ValueError(f"user_requirements[{index}].dimension={normalized(item.get('dimension'))[:120]!r} "
                             f"没有对应的研究维度；可用维度为 {available}。请使用最终列名并保留原要求")
        mode = item.get("completion_mode", "SINGLE")
        count = item.get("minimum_count", 1)
        expression = normalized(item.get("count_expression"))
        if mode not in {"SINGLE", "DISTINCT_FACTS", "DISTINCT_ENTITIES", "COMPARISON"} or type(count) is not int or not 1 <= count <= 20:
            raise ValueError("需求完成方式或数量不符合约定")
        if mode == "SINGLE":
            if count != 1 or expression:
                raise ValueError("单项需求不能声明额外数量")
        elif mode != "COMPARISON" and (not expression or expression not in quote or count_value(expression) != count):
            raise ValueError("需求数量必须与用户原文一致")
        raw_subjects = item.get("entities", [])
        if not isinstance(raw_subjects, list):
            raise ValueError("需求适用对象必须为列表")
        bound = []
        for subject in raw_subjects:
            label = subjects.get(normalized(subject).casefold())
            if label is None:
                raise ValueError(f"user_requirements[{index}].entities 引用了未规划的研究对象 "
                                 f"{normalized(subject)[:120]!r}；请使用最终 entities.label")
            if label not in bound:
                bound.append(label)
        if mode == "COMPARISON" and (len(bound) < 2 or count != len(bound) or expression):
            raise ValueError("比较需求必须明确绑定至少两个对象，数量与对象数一致且不声明数量原文")
        identity = (text, dimension, tuple(sorted(bound)), mode, count)
        if identity in seen:
            continue
        seen.add(identity)
        result.append({"text": text, "question_quote": quote, "dimension": dimension, "entities": bound,
                       "origin": "EXPLICIT", "completion_mode": mode, "minimum_count": count,
                       "count_expression": expression})
        if structured:
            kind, temporal = item.get("answer_kind"), item.get("temporal_mode")
            conditions, constraints = item.get("conditions"), item.get("source_constraints")
            if (kind not in {"FACT", "IDENTIFIER"} or temporal not in {"CURRENT", "HISTORICAL", "TIME_INVARIANT", "UNSPECIFIED"}
                    or kind == "IDENTIFIER" and temporal != "TIME_INVARIANT"
                    or not isinstance(conditions, list) or not 1 <= len(conditions) <= 8
                    or any(not isinstance(value, str) or not value.strip() or len(value) > 350 for value in conditions)
                    or len(set(conditions)) != len(conditions) or not isinstance(constraints, list) or len(constraints) > 4):
                raise ValueError("需求必须明确声明内容类型、时间范围及独立条件；来源要求附到内容项，不单独扩充为研究项")
            normalized_constraints = []
            for constraint in constraints:
                if (not isinstance(constraint, dict) or set(constraint) not in ({"policy", "question_quote"}, {"policy", "question_quote", "source_types"})
                        or constraint["policy"] != "PRIMARY_SOURCE" or not isinstance(constraint["question_quote"], str)
                        or not constraint["question_quote"].strip() or len(constraint["question_quote"]) > 1000
                        or normalized(constraint["question_quote"]) not in question):
                    raise ValueError("来源约束必须绑定原问题中的准确原文")
                types = constraint.get("source_types", ["PRIMARY_SOURCE"])
                if (not isinstance(types, list) or not 1 <= len(types) <= 4 or any(not isinstance(value, str) or value not in {
                        "PRIMARY_SOURCE", "SPECIFICATION", "OFFICIAL_DOCUMENTATION", "ORIGINAL_RESEARCH"} for value in types) or len(types) != len(set(types))):
                    raise ValueError("来源类型不符合约定")
                normalized_constraints.append({**constraint, "source_types": list(types)})
            result[-1].update(answer_kind=kind, temporal_mode=temporal, conditions=conditions, source_constraints=normalized_constraints)
    return result
