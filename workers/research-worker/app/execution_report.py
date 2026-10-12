"""完成诊断中的查询历史与反证结构校验。"""
from app.counter_checks import VERDICTS
from app.repair_queries import STRATEGIES


def validate_execution_report(report):
    if set(report) != {"queries", "strategy", "counter_checks"}:
        raise ValueError("execution_report 字段不符合约定")
    if report["strategy"] not in STRATEGIES | {"DIRECT"}:
        raise ValueError("execution_report 策略不符合约定")
    queries, checks = report["queries"], report["counter_checks"]
    if not isinstance(queries, list) or len(queries) > 32 or not isinstance(checks, list) or len(checks) > 12:
        raise ValueError("execution_report 条数超过上限或格式不符合约定")
    for query in queries:
        if not isinstance(query, str) or not query.strip() or len(query) > 200 or "::" in query or any(ord(c) < 32 for c in query):
            raise ValueError("已执行的检索词不符合约定")
    for check in checks:
        if not isinstance(check, dict) or set(check) != {
            "cell_key", "original_claim", "claim_text", "verdict", "queries", "refuting_evidence_keys"}:
            raise ValueError("反证字段不符合约定")
        if check["verdict"] not in VERDICTS:
            raise ValueError("反证判定不符合约定")
        for key, maximum in [("cell_key", 160), ("original_claim", 16384), ("claim_text", 2000)]:
            if not isinstance(check[key], str) or not check[key].strip() or len(check[key]) > maximum:
                raise ValueError("反证结论标识或文本不符合约定")
        if not isinstance(check["queries"], list) or len(check["queries"]) > 1 or any(q not in queries for q in check["queries"]):
            raise ValueError("反证查询必须出现在已执行的查询列表中")
        refs = check["refuting_evidence_keys"]
        if not isinstance(refs, list) or len(refs) > 6 or any(not isinstance(k, str) for k in refs):
            raise ValueError("反证证据标识不符合约定")
        if check["verdict"] == "REFUTED" and not refs:
            raise ValueError("REFUTED 必须附带反证证据")
        if check["verdict"] != "REFUTED" and refs:
            raise ValueError("只有 REFUTED 可以包含反证证据")
        if check["verdict"] != "NOT_RUN" and not check["queries"]:
            raise ValueError("反证判定必须关联已执行的查询")
