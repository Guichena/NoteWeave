"""指标只来自解析结果和人工标注，缺少分母或足够匹配时返回空值。"""
from collections import Counter, defaultdict
import re
import unicodedata


def normalize(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", text))


def cer(reference, prediction, max_cells=4_000_000):
    reference, prediction = normalize(reference), normalize(prediction)
    denominator = len(reference)
    if not reference:
        return {"value": None, "reason": "EMPTY_REFERENCE", "reference_characters": 0, "edit_distance": None}
    # 删除共同前后缀，保留原始参考长度作为分母，降低相似长文档的计算量。
    prefix = 0
    while prefix < min(len(reference), len(prediction)) and reference[prefix] == prediction[prefix]:
        prefix += 1
    reference, prediction = reference[prefix:], prediction[prefix:]
    suffix = 0
    while suffix < min(len(reference), len(prediction)) and reference[-suffix - 1] == prediction[-suffix - 1]:
        suffix += 1
    if suffix:
        reference, prediction = reference[:-suffix], prediction[:-suffix]
    if not reference or not prediction:
        distance = max(len(reference), len(prediction))
    elif len(reference) * len(prediction) > max_cells:
        return {"value": None, "reason": "EDIT_DISTANCE_BUDGET_EXCEEDED", "reference_characters": denominator, "edit_distance": None}
    else:
        if len(reference) < len(prediction):
            reference, prediction = prediction, reference
        previous = list(range(len(prediction) + 1))
        for row, char in enumerate(reference, 1):
            current = [row]
            for column, other in enumerate(prediction, 1):
                current.append(min(current[-1] + 1, previous[column] + 1, previous[column - 1] + (char != other)))
            previous = current
        distance = previous[-1]
    return {"value": distance / denominator, "reason": None, "reference_characters": denominator, "edit_distance": distance}


def _ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def headings(gold, predicted):
    kinds = {"TITLE", "SECTION_HEADER"}
    expected = [item for item in gold if item["element_type"] in kinds]
    actual = [item for item in predicted if item["element_type"] in kinds]
    key = lambda item: (item["page"], normalize(item["text"]))
    target, found = Counter(map(key, expected)), Counter(map(key, actual))
    matched = sum((target & found).values())
    expected_levels = Counter((key(item), item["heading_level"]) for item in expected if item["heading_level"] is not None)
    actual_levels = Counter((key(item), item["heading_level"]) for item in actual if item["heading_level"] is not None)
    known_expected = Counter(key(item) for item in expected if item["heading_level"] is not None)
    known_actual = Counter(key(item) for item in actual if item["heading_level"] is not None)
    level_denominator = sum((known_expected & known_actual).values())
    return {"expected": len(expected), "predicted": len(actual), "matched": matched,
        "precision": _ratio(matched, len(actual)), "recall": _ratio(matched, len(expected)),
        "hierarchy_accuracy": _ratio(sum((expected_levels & actual_levels).values()), level_denominator),
        "hierarchy_compared": level_denominator,
        "reason": "NO_GOLD_HEADINGS" if not expected else None,
        "hierarchy_reason": "NO_MATCHED_KNOWN_LEVELS" if not level_denominator else None}


def reading_order(gold, predicted, min_coverage=0.8):
    # 唯一文本和真实同页匹配，重复文本不通过任意配对生成好看的顺序分数。
    expected = [(index, item) for index, item in enumerate(gold) if item["reading_order_known"] and normalize(item["text"])]
    actual = [(index, item) for index, item in enumerate(predicted) if item["reading_order_known"] and normalize(item["text"])]
    key = lambda item: (item["page"], normalize(item["text"]))
    targets, candidates = defaultdict(list), defaultdict(list)
    for index, item in expected:
        targets[key(item)].append(index)
    for index, item in actual:
        candidates[key(item)].append(index)
    matches = sorted((indexes[0], candidates[token][0]) for token, indexes in targets.items()
        if len(indexes) == 1 and len(candidates[token]) == 1 and token[0] is not None)
    inversions = 0
    # Fenwick 树计算成对逆序，避免大标注文档二次方遍历。
    positions = sorted({position for _, position in matches})
    ranks = {position: index + 1 for index, position in enumerate(positions)}
    tree = [0] * (len(positions) + 1)
    for seen, (_, position) in enumerate(matches):
        rank, cursor, smaller = ranks[position], ranks[position], 0
        while cursor:
            smaller += tree[cursor]
            cursor -= cursor & -cursor
        inversions += seen - smaller
        cursor = rank
        while cursor < len(tree):
            tree[cursor] += 1
            cursor += cursor & -cursor
    pairs = len(matches) * (len(matches) - 1) // 2
    coverage = _ratio(len(matches), len(expected))
    reason = "INSUFFICIENT_MATCHED_BLOCKS" if not pairs else ("LOW_MATCH_COVERAGE" if coverage < min_coverage else None)
    return {"gold_blocks": len(expected), "matched_blocks": len(matches), "coverage": coverage,
        "compared_pairs": pairs, "inversions": inversions if pairs else None,
        "inversion_rate": _ratio(inversions, pairs) if reason is None else None, "reason": reason}


def tables(gold, predicted):
    target = [item for item in gold if item["element_type"] == "TABLE"]
    actual = [item for item in predicted if item["element_type"] == "TABLE"]
    # 同页表格按提供的阅读顺序配对，数量差异独立记录，不根据相似度重新排序。
    expected_pages, predicted_pages = defaultdict(list), defaultdict(list)
    for item in target:
        expected_pages[item["page"]].append(item)
    for item in actual:
        predicted_pages[item["page"]].append(item)
    comparable, shape_matches, cell_matches, cells = 0, 0, 0, 0
    for page, expected in expected_pages.items():
        if page is None:
            continue
        for a, b in zip(expected, predicted_pages[page]):
            if not a["table_structure_known"] or not b["table_structure_known"]:
                continue
            comparable += 1
            left, right = a["table_cells"], b["table_cells"]
            same_shape = len(left) == len(right) and len(left[0]) == len(right[0])
            shape_matches += same_shape
            cells += sum(len(row) for row in left)
            if same_shape:
                cell_matches += sum(normalize(x) == normalize(y) for ar, br in zip(left, right) for x, y in zip(ar, br))
    return {"expected": len(target), "predicted": len(actual), "comparable": comparable,
        "structure_accuracy": _ratio(shape_matches, comparable), "cell_exact_accuracy": _ratio(cell_matches, cells),
        "compared_cells": cells, "gold_structure_known": sum(item["table_structure_known"] for item in target),
        "predicted_structure_known": sum(item["table_structure_known"] for item in actual),
        "reason": "NO_GOLD_TABLES" if not target else ("TABLE_STRUCTURE_OR_PAGE_UNKNOWN" if not comparable else None)}


def measure(gold_text, gold_document, parsed_document, max_cells=4_000_000):
    predicted = parsed_document["elements"]
    text = "\n".join(item["text"] for item in predicted if item["element_type"] != "PICTURE")
    return {"normalization": "NFC_NO_WHITESPACE", "cer": cer(gold_text, text, max_cells),
        "headings": headings(gold_document["elements"], predicted), "tables": tables(gold_document["elements"], predicted),
        "reading_order": reading_order(gold_document["elements"], predicted)}
