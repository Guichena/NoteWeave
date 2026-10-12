"""宽搜索只提交有检索片段支撑的范围提案，正式证据仍由深挖任务获取。"""
from __future__ import annotations

import hashlib
import ipaddress
import re
from dataclasses import replace
from urllib.parse import urlsplit

from app.deep_cell_executor import require_worker_instance_id, _build_execution_llm, _llm_provider_call_count
from app.evidence_audit_executor import _role_completion
from app.task_snapshot_contract import require_trusted_claim_snapshot
from app.execution_control import require_execution_active
from app.counter_checks import model_json
from app.role_executor import RoleProfileCatalog
from app.source_independence import independent_confidence, registered_domain
from app.models import ResearchTaskInput, ResearchTaskInputPayload, ResearchPlan, ResearchQuerySpec, ControlPack
from app.search_adapters import build_web_search_adapter, run_research_search
from app.research_agent_completion_contract import canonical_json_bytes


def discovery_confidence(observations, authoritative_domains):
    count, _ = independent_confidence([{**item, "quote_text": item["snippet"], "support_score_ppm": 0} for item in observations])
    authorities = {registered_domain(domain) for domain in authoritative_domains}
    matched = len({registered_domain(item["source_domain"]) for item in observations} & authorities)
    weight = count + min(count, matched)
    return count, min(950000, weight * 1000000 // (weight + 1))


class DiscoveryExecutor:
    def __init__(self, worker_instance_id, permit_client=None, *, llm_client=None, search_adapter=None, enable_llm=True):
        self.worker_instance_id = require_worker_instance_id(worker_instance_id)
        self.permit_client = permit_client
        self.llm_client = llm_client
        self.search_adapter = search_adapter
        self.enable_llm = enable_llm

    def __call__(self, command, claim):
        snapshot = require_trusted_claim_snapshot(claim)
        data = snapshot.query_policy.get("discovery_input")
        if snapshot.role != "WIDE_DISCOVERY" or command.research_run_id != snapshot.research_run_id or not isinstance(data, dict):
            raise ValueError("宽搜索必须使用服务端冻结的任务快照")
        if not data.get("input_digest") or not str(data.get("question", "")).strip():
            raise ValueError("宽搜索输入不完整")
        limit = min(3, int(snapshot.budget.get("search_calls", 0)), int(data.get("query_limit", 3)))
        llm = self.llm_client
        if llm is None and self.enable_llm and snapshot.budget.get("llm_calls", 0):
            profile = RoleProfileCatalog().resolve("WIDE_DISCOVERY")
            llm = _build_execution_llm(require_execution_active, replace(profile, max_llm_calls=min(4, int(snapshot.budget["llm_calls"]))))
        reasons = []
        def validate_queries(raw):
            if not isinstance(raw, dict) or set(raw) != {"queries"} or not isinstance(raw["queries"], list) or not 1 <= len(raw["queries"]) <= limit:
                raise ValueError("queries 必须包含预算内的检索词")
            if any(not isinstance(q, str) or not q.strip() or len(q) > 200 or any(ord(c) < 32 for c in q) for q in raw["queries"]):
                raise ValueError("检索词为空、过长或包含控制字符")
            if len({re.sub(r"\s+", " ", q).strip().casefold() for q in raw["queries"]}) != len(raw["queries"]):
                raise ValueError("检索词不能重复")
        planned = model_json(llm, "research.discovery.queries", {"question": data["question"], "table": data.get("table", {}),
            "previous_queries": data.get("previous_queries", []), "query_limit": limit,
            "policy": "Find missing entities and dimensions. Return only queries as an array of short natural-language search queries."}, validate_queries) if limit else None
        if planned:
            queries = [q.strip() for q in planned["queries"]]
        else:
            reasons.append("QUERY_PLANNING_UNAVAILABLE")
            question = re.sub(r"\s+", " ", data["question"]).strip()[:160]
            anchors = [str(c.get("label", "")) for c in data.get("table", {}).get("columns", [])][:2]
            queries = list(dict.fromkeys([question, *[f"{question} {a}"[:200] for a in anchors if a]]))[:limit]
        observations, executed = [], []
        policy = snapshot.source_policy
        mode = str(policy.get("retrieval_mode", "SOURCES_ONLY"))
        for query in queries:
            require_execution_active()
            if self.permit_client is None:
                reasons.append("SEARCH_CLIENT_UNAVAILABLE")
                break
            self.permit_client.require_permit(snapshot, "search")
            executed.append(query)
            if mode != "WEB_ONLY":
                hits = self.permit_client.search_workspace_windows(snapshot, queries=[query], limit=8)
                for hit in hits:
                    observations.append(self.observation(snapshot, query, hit.source_id, hit.source_title, "", hit.window_text[:3000], hit.source_window_id))
            if mode != "SOURCES_ONLY" and policy.get("allow_external_search") is True:
                try:
                    adapter = self.search_adapter or build_web_search_adapter()
                    task = ResearchTaskInput(task_id=snapshot.task_id, workspace_id=snapshot.workspace_id, target_id=snapshot.research_run_id,
                        retrieval_mode="WEB_ONLY", control_pack=ControlPack.model_validate(snapshot.control_pack),
                        input_payload=ResearchTaskInputPayload(question=data["question"], profile_key="DEFAULT"))
                    plan = ResearchPlan(normalized_question=data["question"], query_set=[query], query_specs=[ResearchQuerySpec(
                        query=query, family="direct", search_lane="WIDE_DISCOVERY")], stop_contract={"global_search_limit": 8})
                    for hit in run_research_search(task, plan, adapter=adapter):
                        if hit.url and hit.snippet and _allowed_result_url(hit.url, policy.get("allowed_external_domains", [])):
                            observations.append(self.observation(snapshot, query, hit.source_id, hit.source_title, hit.url, hit.snippet[:3000], ""))
                except Exception:
                    require_execution_active()
                    reasons.append("EXTERNAL_SEARCH_UNAVAILABLE")
        observations = list({o["observation_key"]: o for o in observations}.values())[:24]
        # 为提案及完成元数据预留空间，中文和控制字符按实际规范化字节计数。
        while len(canonical_json_bytes(observations)) > 96000:
            observations.pop()
        by_key = {o["observation_key"]: o for o in observations}
        def validate_discovery(raw):
            if not isinstance(raw, dict) or set(raw) != {"proposals"} or not isinstance(raw["proposals"], list) or len(raw["proposals"]) > 20:
                raise ValueError("proposals 必须为最多 20 条提案的列表")
            if len(canonical_json_bytes(raw)) > 96000:
                raise ValueError("提案及支撑引用超出完成信封的文本预算")
            for item in raw["proposals"]:
                if not isinstance(item, dict) or set(item) != {"proposal_type", "label", "search_query", "rationale", "supports"}:
                    raise ValueError("提案字段不符合约定")
                if item["proposal_type"] not in {"ENTITY", "DIMENSION", "SEARCH_QUERY"}:
                    raise ValueError("提案类型不符合约定")
                for field, cap in [("label", 500), ("search_query", 200), ("rationale", 2000)]:
                    if not isinstance(item[field], str) or not item[field].strip() or len(item[field]) > cap:
                        raise ValueError("提案文本为空或过长")
                supports = item["supports"]
                if not isinstance(supports, list) or not 1 <= len(supports) <= 8:
                    raise ValueError("提案必须引用检索片段")
                seen = set()
                for support in supports:
                    if not isinstance(support, dict) or set(support) != {"observation_key", "quote_text"}:
                        raise ValueError("支撑引用字段不符合约定")
                    key, quote = support["observation_key"], support["quote_text"]
                    if key not in by_key or key in seen or not isinstance(quote, str) or not quote.strip() or quote not in by_key[key]["snippet"]:
                        raise ValueError("引用必须逐字来自本次检索的独立片段")
                    seen.add(key)
        discovered = model_json(llm, "research.discover", {"question": data["question"], "table": data.get("table", {}),
            "observations": observations, "prior_proposals": data.get("prior_proposals", []),
            "policy": "Propose missing ENTITY, DIMENSION or SEARCH_QUERY items. Every proposal must cite exact snippet text via supports (observation_key, quote_text). Return only proposals; return an empty array when unsupported or already covered."}, validate_discovery)
        generation_mode = "LLM" if discovered else "RULE"
        if discovered is None:
            reasons.append("DISCOVERY_MODEL_UNAVAILABLE")
            discovered = {"proposals": [{"proposal_type": "SEARCH_QUERY", "label": o["source_title"][:500],
                "search_query": (data["question"][:100] + " " + o["source_title"][:90]).strip(),
                "rationale": "根据实际检索结果补充查询，尚未作为表格扩展接受",
                "supports": [{"observation_key": o["observation_key"], "quote_text": o["snippet"]}]} for o in observations[:3]]}
        proposals = []
        identities = set()
        for item in discovered["proposals"]:
            identity = (item["proposal_type"], re.sub(r"\s+", " ", item["label"]).strip().casefold())
            if identity in identities: continue
            identities.add(identity)
            sources = [by_key[s["observation_key"]] for s in item["supports"]]
            count, confidence = discovery_confidence(sources, data.get("authoritative_domains", []))
            key = _stable_key(item["proposal_type"].lower(), item["label"])
            proposals.append({**item, "proposal_key": key, "candidate_key": key, "confidence_ppm": confidence,
                "supporting_domain_count": count, "source_lead": "", "source_domain": "", "lineage_digest": ""})
        require_execution_active()
        result = {"result_schema_version": "research-discovery-proposal.v2", "role": "WIDE_DISCOVERY", "status": "PROPOSED",
            "input_digest": data["input_digest"], "plan_revision": int(data.get("plan_revision", 0)),
            "entity_set_version": int(data.get("entity_set_version", 0)), "proposals": proposals, "queries": executed,
            "search_observations": observations, "generation_mode": generation_mode, "degradation_reasons": sorted(set(reasons))}
        return _role_completion(claim, snapshot.snapshot_digest, self.worker_instance_id, result,
            llm_calls=_llm_provider_call_count(llm), search_calls=len(executed))

    @staticmethod
    def observation(snapshot, query, source, title, url, snippet, window):
        domain = urlsplit(url).hostname if url else "workspace-source:" + source
        identity = "\n".join((snapshot.task_id, query, source, url, window, snippet))
        return {"observation_key": "observation:" + hashlib.sha256(identity.encode()).hexdigest()[:48], "query": query,
            "source_id": source, "source_title": title, "source_url": url, "source_window_id": window,
            "snippet": snippet, "source_domain": domain or "", "lineage_digest": hashlib.sha256(snippet.encode()).hexdigest()}


def _stable_key(prefix, value):
    normalized = re.sub(r"\s+", " ", value.strip().casefold())
    return prefix + "-" + hashlib.sha256(normalized.encode()).hexdigest()[:20]


def _allowed_result_url(url, domains):
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").lower()
        if parsed.scheme not in {"http", "https"} or not host or parsed.username or len(url) > 2048:
            return False
        if host in {"localhost", "localhost.localdomain", "metadata.google.internal"} or host.endswith((".localhost", ".local", ".internal")):
            return False
        try:
            if not ipaddress.ip_address(host).is_global:
                return False
        except ValueError:
            if re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}", host):
                return False
        return not domains or any(host == d.lower() or host.endswith("." + d.lower()) for d in domains)
    except ValueError:
        return False
