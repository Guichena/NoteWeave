"""原子事实隔离验证。生成失败和判定失败均保留 NOT_RUN。"""
from app.json_repair import parse_json_payload
from app.cell_query_planner import normalize_query, query_rejection_reason

MAX_FACTS = 6
VERDICTS = {"REFUTED", "NOT_REFUTED", "NO_COUNTER_EVIDENCE_FOUND", "NOT_RUN"}


def model_json(llm, purpose, payload, validate):
    if llm is None:
        return None
    for _ in range(2):
        try:
            raw = parse_json_payload(llm.complete_json(purpose, payload))
            validate(raw)
            return raw
        except Exception as exc:
            payload = {**payload, "validation_errors": [str(exc)[:240]]}
    return None


def atomic_queries(claim, llm, tried):
    def validate(raw):
        facts = raw.get("facts") if isinstance(raw, dict) and set(raw) == {"facts"} else None
        if not isinstance(facts, list) or not 1 <= len(facts) <= MAX_FACTS:
            raise ValueError("facts 必须包含 1 到 6 条事实，并覆盖完整结论")
        seen = set()
        queries = []
        for fact in facts:
            if not isinstance(fact, dict) or set(fact) != {"claim_text", "query"}:
                raise ValueError("每条事实只能包含 claim_text 和 query")
            text, query = fact["claim_text"], fact["query"]
            if not isinstance(text, str) or not text.strip() or len(text) > 2000 or text in seen:
                raise ValueError("原子事实为空、过长或重复")
            seen.add(text)
            if not isinstance(query, str) or any(ord(c) < 32 for c in query):
                raise ValueError("检索词格式不符合约定")
            reason = query_rejection_reason(normalize_query(query), [], tuple([*tried, *queries]))
            if reason:
                raise ValueError(reason)
            queries.append(query)
    raw = model_json(llm, "research.counter.facts", {
        "policy": "Split the entire claim into independently understandable atomic facts. Cover every fact. "
                  "For each write a short search query seeking exceptions, limitations or counterexamples. "
                  "Do not reuse tried queries. If more than 6 facts are required return an empty array.",
        "claim": claim, "tried_queries": list(tried),
        "output_schema": {"facts": [{"claim_text": "string", "query": "string"}]}}, validate)
    return raw["facts"] if raw else None


def judge_fact(fact, windows, llm):
    # 调用方只传入本条事实新检索的窗口，不带原证据、其他事实或研究草稿。
    by_id = {w.window_id: w for w in windows}
    def validate(raw):
        if not isinstance(raw, dict) or set(raw) != {"verdict", "citations"}:
            raise ValueError("输出只能包含 verdict 和 citations")
        if raw["verdict"] not in VERDICTS - {"NOT_RUN"} or not isinstance(raw["citations"], list) or len(raw["citations"]) > 6:
            raise ValueError("判定或引用格式不符合约定")
        seen = set()
        for citation in raw["citations"]:
            if not isinstance(citation, dict) or set(citation) != {"window_id", "quote_text"}:
                raise ValueError("引用只能包含 window_id 和 quote_text")
            w = by_id.get(citation["window_id"])
            quote = citation["quote_text"]
            if w is None or not isinstance(quote, str) or not quote.strip() or quote not in w.window_text:
                raise ValueError("引文必须逐字来自本条事实新检索的证据")
            identity = (citation["window_id"], quote)
            if identity in seen:
                raise ValueError("引用重复")
            seen.add(identity)
        if raw["verdict"] in {"REFUTED", "NOT_REFUTED"} and not raw["citations"]:
            raise ValueError("事实判定必须附带逐字引文")
        if raw["verdict"] == "NO_COUNTER_EVIDENCE_FOUND" and raw["citations"]:
            raise ValueError("未找到反证的结果不能包含引用")
    return model_json(llm, "research.counter.verify", {
        "policy": "Judge this single atomic fact using only these newly retrieved passages. REFUTED requires "
                  "contradicting evidence; NOT_REFUTED requires supporting evidence; unrelated or empty results "
                  "mean NO_COUNTER_EVIDENCE_FOUND. Cite exact original text. Never treat no results as proof.",
        "claim_text": fact,
        "passages": [{"window_id": w.window_id, "text": w.window_text} for w in windows],
        "output_schema": {"verdict": "REFUTED|NOT_REFUTED|NO_COUNTER_EVIDENCE_FOUND",
                          "citations": [{"window_id": "string", "quote_text": "string"}]}}, validate)
