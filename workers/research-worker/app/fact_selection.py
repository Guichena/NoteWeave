"""在既有提交上限内按需求选择材料，未提交的材料仍保持未核验身份。"""
from __future__ import annotations

import hashlib
import unicodedata


def select_facts(snapshot, evidence, observations, *, limit, per_cell_limit=6, byte_limit=None, client=None):
    evidence = list({item["evidence_key"]: item for item in evidence}.values())
    contract = snapshot.query_policy.get("requirement_contract") or {}
    missing = set(contract.get("missing_requirement_ids", []))
    requirements = {item["requirement_id"]: item for item in contract.get("requirements", [])}
    modern = contract.get("completion_gap_policy_version") == 1
    from app.fact_selection_review import review_selection
    reviewed = review_selection(contract, evidence, client) if modern else {}
    conditions_covered, selected_groups, requirement_groups = {}, set(), {}
    links = {}
    for observation in observations:
        for key in observation["evidence_keys"]:
            links.setdefault(key, set()).add(observation["requirement_id"])
    historical = {_claim(item["claim_text"]) for item in contract.get("facts", [])}
    selected, seen, counts, covered, claims = [], set(), {}, {}, set()
    for fact in contract.get("facts", []):
        for key in fact["evidence_keys"]:
            for identity in links.get(key, set()):
                covered.setdefault(identity, set()).add(_claim(fact["claim_text"]))
    remaining = list(evidence)
    used_bytes = 0
    def payload_cost(item):
        return 3 * len(item["claim_text"].encode("utf-8")) + len(item.get("quote_text", "").encode("utf-8")) + 1536
    while remaining and len(selected) < max(0, limit):
        candidates = [item for item in remaining if counts.get(item["cell_key"], 0) < per_cell_limit
                      and (byte_limit is None or used_bytes + payload_cost(item) <= byte_limit)]
        if not candidates:
            break
        def rank(item):
            text = _claim(item["claim_text"])
            if modern:
                annotation = reviewed.get(item["evidence_key"])
                group = (item["cell_key"], annotation["duplicate_group"] if annotation else text)
                scores = [0, 0, 0]
                if annotation is not None:
                    for match in annotation["relevance"]:
                        req = requirements[match["requirement_id"]]
                        if not match["temporal_match"]:
                            continue
                        proposed = set(match["condition_indexes"]) | {"source:" + kind for kind in match.get("source_types", [])}
                        gained = proposed - conditions_covered.get(req["requirement_id"], set())
                        distinct = bool(match["condition_indexes"]) and req.get("completion_mode") == "DISTINCT_FACTS" and group not in requirement_groups.get(req["requirement_id"], set()) and len(requirement_groups.get(req["requirement_id"], set())) < req.get("minimum_count", 1)
                        scores[0 if req.get("mandatory", True) else 2] += len(gained) + int(distinct)
                else:
                    for identity in links.get(item["evidence_key"], set()) & missing:
                        req = requirements[identity]
                        target = req.get("minimum_count", 1) if req.get("completion_mode") == "DISTINCT_FACTS" else 1
                        scores[1 if req.get("mandatory", True) else 2] += int(text not in covered.get(identity, set()) and len(covered.get(identity, set())) < target)
                return (*scores, group not in selected_groups, text not in historical, text not in claims,
                        -counts.get(item["cell_key"], 0), item.get("support_score_ppm", 0), item["evidence_key"])
            value = 0
            for identity in links.get(item["evidence_key"], set()) & missing:
                requirement = requirements[identity]
                minimum = requirement.get("minimum_count", 1)
                target = minimum if requirement.get("completion_mode") == "DISTINCT_FACTS" else 3
                if text not in covered.get(identity, set()) and len(covered.get(identity, set())) < target:
                    first = not covered.get(identity)
                    value += (1000 if first else 100) if requirement.get("origin") == "EXPLICIT" else (50 if first else 10)
            return (value, text not in historical, text not in claims,
                    -counts.get(item["cell_key"], 0), item.get("support_score_ppm", 0), item["evidence_key"])
        chosen = max(candidates, key=rank)
        remaining.remove(chosen)
        if chosen["evidence_key"] in seen:
            continue
        selected.append(chosen)
        used_bytes += payload_cost(chosen)
        seen.add(chosen["evidence_key"])
        counts[chosen["cell_key"]] = counts.get(chosen["cell_key"], 0) + 1
        text = _claim(chosen["claim_text"])
        claims.add(text)
        if modern:
            annotation = reviewed.get(chosen["evidence_key"])
            selected_groups.add((chosen["cell_key"], annotation["duplicate_group"] if annotation else text))
            for match in annotation["relevance"] if annotation else []:
                if match["temporal_match"]:
                    conditions_covered.setdefault(match["requirement_id"], set()).update(match["condition_indexes"])
                    conditions_covered[match["requirement_id"]].update("source:"+kind for kind in match.get("source_types", []))
                    if match["condition_indexes"]:
                        requirement_groups.setdefault(match["requirement_id"], set()).add((chosen["cell_key"],annotation["duplicate_group"]))
        for identity in links.get(chosen["evidence_key"], set()):
            covered.setdefault(identity, set()).add(text)
    deferred = []
    for item in evidence:
        if item["evidence_key"] in seen:
            continue
        deferred.append({"material_id": "material:" + hashlib.sha256(
            (item["cell_key"] + "\0" + item["source_id"] + "\0" + item["window_id"] + "\0" + item["claim_text"] + "\0" + item.get("quote_text", "")).encode()).hexdigest()[:48],
            "cell_key": item["cell_key"], "claim_text": item["claim_text"][:1200],
            "source_id": item["source_id"], "window_id": item["window_id"], "source_url": item.get("source_url", "")[:2000],
            "requirement_ids": sorted(links.get(item["evidence_key"], set())), "reason_code": "COMPLETION_PAYLOAD_LIMIT"
                if byte_limit is not None and used_bytes + payload_cost(item) > byte_limit else "COMPLETION_EVIDENCE_LIMIT"})
    return selected, {"schema_version": "research-fact-selection.v1", "considered_count": len(evidence),
        "submitted_evidence_keys": [item["evidence_key"] for item in selected], "limit": max(0, limit),
        "deferred": deferred[:24], "omitted_count": max(0, len(deferred) - 24)}


def validate_selection(raw):
    fields = {"schema_version", "considered_count", "submitted_evidence_keys", "limit", "deferred", "omitted_count"}
    if not isinstance(raw, dict) or set(raw) != fields or raw["schema_version"] != "research-fact-selection.v1":
        raise ValueError("事实选择诊断格式错误")
    if any(type(raw[key]) is not int or not 0 <= raw[key] <= 10000 for key in ("considered_count", "limit", "omitted_count")):
        raise ValueError("事实选择计数不符合约定")
    submitted, deferred = raw["submitted_evidence_keys"], raw["deferred"]
    if (not isinstance(submitted, list) or len(submitted) > min(12, raw["limit"])
            or any(not isinstance(key, str) or not key or len(key) > 64 for key in submitted)
            or len(set(submitted)) != len(submitted) or not isinstance(deferred, list) or len(deferred) > 24
            or raw["considered_count"] != len(submitted) + len(deferred) + raw["omitted_count"]):
        raise ValueError("事实选择与延后材料计数不一致")
    identities = set()
    for item in deferred:
        if not isinstance(item, dict) or set(item) != {"material_id", "cell_key", "claim_text", "source_id", "window_id", "source_url", "requirement_ids", "reason_code"}:
            raise ValueError("延后材料字段错误")
        for key, maximum in (("material_id", 64), ("cell_key", 160), ("claim_text", 1200), ("source_id", 160), ("window_id", 160), ("source_url", 2000)):
            if not isinstance(item[key], str) or len(item[key]) > maximum or (key != "source_url" and not item[key].strip()):
                raise ValueError("延后材料字段为空或过长")
        if item["material_id"] in identities or item["reason_code"] not in {"COMPLETION_EVIDENCE_LIMIT", "COMPLETION_PAYLOAD_LIMIT"}:
            raise ValueError("延后材料身份或原因错误")
        identities.add(item["material_id"])
        if not isinstance(item["requirement_ids"], list) or len(item["requirement_ids"]) > 192 or any(not isinstance(key, str) or not key.startswith("req:") or len(key) > 64 for key in item["requirement_ids"]):
            raise ValueError("延后材料需求范围错误")


def _claim(text):
    return " ".join(unicodedata.normalize("NFKC", text).split()).casefold()
