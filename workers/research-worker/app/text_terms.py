"""中英文混合文本的词项切分，供检索覆盖度、引用支持度等本地打分共用。

英文和数字按单词切分并转小写，只保留长度不少于 3 的词；中文连续片段按相邻两字切分，
只有一个字的片段保留单字。按空格切分对中文无效（整句会变成一个词），所以中文必须单独处理。
"""

from __future__ import annotations

import re

_LATIN = re.compile(r"[a-z0-9]+")
_HAN_RUN = re.compile(r"[一-鿿]+")

# 否定词里的"不""未""无"也出现在很多并不表示否定的常用词中，判断否定前先把这些词去掉
_NON_NEGATING_COMPOUNDS = (
    "不同", "不仅", "不但", "不断", "不少", "不错", "不久", "不过", "不管", "不论", "不得不",
    "未来", "无论", "无数", "毫无疑问", "无疑",
)
_CJK_NEGATIONS = ("没有", "并非", "不能", "无法", "从未", "否认", "反对", "不", "未", "无")
_LATIN_NEGATIONS = frozenset({
    "not", "no", "never", "neither", "without", "cannot", "can't", "doesn't",
    "didn't", "isn't", "wasn't", "aren't", "weren't", "won't",
})


def terms(text: str) -> set[str]:
    """返回去重后的词项集合。"""
    value = str(text or "")
    result = {token for token in _LATIN.findall(value.lower()) if len(token) >= 3}
    for run in _HAN_RUN.findall(value):
        if len(run) == 1:
            result.add(run)
            continue
        for index in range(len(run) - 1):
            result.add(run[index:index + 2])
    return result


def coverage(query: str, text: str) -> float:
    """query 的词项有多大比例出现在 text 中。"""
    query_terms = terms(query)
    if not query_terms:
        return 0.0
    return round(len(query_terms & terms(text)) / len(query_terms), 4)


def is_negated(text: str) -> bool:
    """文本是否带有否定表达；"不同""未来"这类只是含有否定字的常用词不算。"""
    value = str(text or "")
    words = set(re.findall(r"[a-z]+(?:'[a-z]+)?", value.lower()))
    if words & _LATIN_NEGATIONS:
        return True
    for compound in _NON_NEGATING_COMPOUNDS:
        value = value.replace(compound, " ")
    return any(token in value for token in _CJK_NEGATIONS)
