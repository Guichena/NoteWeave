"""单元格结论聚类；不改写原始结论和引文，只规范化比较用的文本。"""
import hashlib
import re
import unicodedata
from decimal import Decimal

from app.counter_checks import model_json
from app.source_independence import independent_confidence, quote_tokens

_UNITS = {"B": Decimal(1), "KB": Decimal(1000), "MB": Decimal(1000) ** 2,
          "GB": Decimal(1000) ** 3, "TB": Decimal(1000) ** 4,
          "KiB": Decimal(1024), "MiB": Decimal(1024) ** 2, "GiB": Decimal(1024) ** 3,
          "TiB": Decimal(1024) ** 4, "字节": Decimal(1), "ms": Decimal("0.001"),
          "s": Decimal(1), "秒": Decimal(1), "毫秒": Decimal("0.001")}
_MEASURE = re.compile(r"(?<![A-Za-z0-9_.])(\d+(?:\.\d+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B|ms|s|毫秒|字节|秒)(?![A-Za-z])")
_POLARITY = re.compile(r"\b(?:not|no|never|without|unsupported)\b|不|未|无|不能|禁止")


def normalize_claim(text):
    text = unicodedata.normalize("NFKC", text)
    def measure(match):
        value = Decimal(match[1]) * _UNITS[match[2]]
        number = format(value.normalize(), "f")
        unit = "seconds" if match[2] in {"ms", "s", "秒", "毫秒"} else "bytes"
        return number + " " + unit
    text = _MEASURE.sub(measure, text).casefold()
    text = re.sub(r"(?<![\w.])\d+(?:\.\d+)?", lambda m: format(Decimal(m[0]).normalize(), "f"), text)
    return " ".join(re.findall(r"[\w.]+", text))


def equivalent(left, right, llm, *, force_model=False):
    a, b = normalize_claim(left), normalize_claim(right)
    if a == b:
        return True
    if not force_model and set(re.findall(r"\d+(?:\.\d+)?", a)) != set(re.findall(r"\d+(?:\.\d+)?", b)):
        return False
    if not force_model and bool(_POLARITY.search(a)) != bool(_POLARITY.search(b)):
        return False
    x, y = set(quote_tokens(a)), set(quote_tokens(b))
    score = len(x & y) / max(1, len(x | y))
    if not force_model and score >= 0.9:
        return True
    if not force_model and score < 0.2:
        return False
    def validate(raw):
        if not isinstance(raw, dict) or set(raw) != {"equivalent"} or type(raw["equivalent"]) is not bool:
            raise ValueError("输出只能包含布尔值 equivalent")
    result = model_json(llm, "research.claims.equivalent", {
        "policy": "Determine whether the two claims state the same fact, including subject, scope, "
                  "conditions, quantities, units and polarity. Similar topics alone are insufficient.",
        "left_claim": left, "right_claim": right, "output_schema": {"equivalent": "boolean"}}, validate)
    return result["equivalent"] if result else None


def build_claim_clusters(rows, llm=None):
    by_cell = {}
    for row in rows:
        by_cell.setdefault(row["cell_key"], []).append(row)
    result, cache = [], {}
    for cell_key in sorted(by_cell):
        clusters = []
        for row in sorted(by_cell[cell_key], key=lambda r: (-r["support_score_ppm"], r["claim_text"], r["evidence_key"])):
            chosen = None
            for group in clusters:
                matched = True
                # 每条成员都要等价，避免通过相似度链条把不同事实合在一起。
                for member in group:
                    texts = tuple(sorted((row["claim_text"], member["claim_text"])))
                    key = (cell_key, *texts)
                    if key not in cache:
                        cache[key] = equivalent(*texts, llm)
                    if cache[key] is not True:
                        matched = False
                        break
                if matched:
                    chosen = group
                    break
            if chosen is None:
                chosen = []
                clusters.append(chosen)
            chosen.append(row)
        for group in clusters:
            representative = group[0]["claim_text"]
            keys = sorted(item["evidence_key"] for item in group)
            _, confidence = independent_confidence(group)
            identity = "\n".join([cell_key, representative, *keys]).encode("utf-8")
            result.append({"cluster_key": "cluster:" + hashlib.sha256(identity).hexdigest()[:48],
                "cell_key": cell_key, "claim_text": representative, "evidence_keys": keys,
                "confidence_ppm": confidence,
                "equivalent_claims": [{"evidence_key": row["evidence_key"], "claim_text": row["claim_text"],
                                       "source_domain": row["source_domain"]}
                                      for row in sorted(group, key=lambda r: r["evidence_key"])]})
    return sorted(result, key=lambda c: (c["cell_key"], -c["confidence_ppm"], c["claim_text"]))


# 每个单元格最多 6 张证据卡，因此最多 6 个候选簇；一个任务最多覆盖 6 个单元格
MAX_CLUSTERS_PER_CELL = 6
MAX_CLUSTERS_PER_TASK = 36


def validate_clusters(clusters):
    if not isinstance(clusters, list) or len(clusters) > MAX_CLUSTERS_PER_TASK:
        raise ValueError("候选簇列表超过上限或格式不符合约定")
    per_cell: dict[object, int] = {}
    for cluster in clusters:
        cell_key = cluster.get("cell_key") if isinstance(cluster, dict) else None
        per_cell[cell_key] = per_cell.get(cell_key, 0) + 1
        if per_cell[cell_key] > MAX_CLUSTERS_PER_CELL:
            raise ValueError("候选簇列表超过上限或格式不符合约定")
    seen, evidence = set(), set()
    for cluster in clusters:
        if not isinstance(cluster, dict) or set(cluster) != {
            "cluster_key", "cell_key", "claim_text", "evidence_keys", "confidence_ppm", "equivalent_claims"}:
            raise ValueError("候选簇字段不符合约定")
        for key, limit in [("cluster_key", 64), ("cell_key", 160), ("claim_text", 16384)]:
            if not isinstance(cluster[key], str) or not cluster[key].strip() or len(cluster[key]) > limit:
                raise ValueError("候选簇标识或结论不符合约定")
        keys, claims = cluster["evidence_keys"], cluster["equivalent_claims"]
        if (cluster["cluster_key"] in seen or type(cluster["confidence_ppm"]) is not int
                or not 0 <= cluster["confidence_ppm"] <= 950000 or not isinstance(keys, list)
                or not 1 <= len(keys) <= 6 or len(set(keys)) != len(keys) or evidence.intersection(keys)
                or not isinstance(claims, list) or len(claims) != len(keys)):
            raise ValueError("候选簇证据、置信度或重复关系不符合约定")
        claim_keys = []
        for claim in claims:
            if not isinstance(claim, dict) or set(claim) != {"evidence_key", "claim_text", "source_domain"}:
                raise ValueError("等价证据字段不符合约定")
            for key, limit in [("evidence_key", 64), ("claim_text", 16384), ("source_domain", 300)]:
                if not isinstance(claim[key], str) or not claim[key].strip() or len(claim[key]) > limit:
                    raise ValueError("等价证据标识、结论或来源域名不符合约定")
            claim_keys.append(claim["evidence_key"])
        if sorted(claim_keys) != sorted(keys) or not any(claim["claim_text"] == cluster["claim_text"] for claim in claims):
            raise ValueError("等价证据必须逐条对应候选簇证据")
        seen.add(cluster["cluster_key"])
        evidence.update(keys)
