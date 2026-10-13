"""声明式产物契约：结构由 JSON Schema 校验，内容检查按具名规则分派。"""

from copy import deepcopy
from typing import Literal

from jsonschema import Draft202012Validator
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.models import ArtifactContractCheckTrace


RULE_NAMES = frozenset({"bullet_count", "numbered_item_count", "section_terms", "section_nonempty",
                        "focus_coverage", "no_fabricated_metrics", "quiz_answer_in_options"})


class ContractSections(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    min: int = Field(default=1, ge=1, le=80)
    max: int = Field(default=80, ge=1, le=80)
    required_headings: list[str] = Field(default_factory=list, max_length=80)

    @model_validator(mode="after")
    def validate_bounds(self):
        if self.min > self.max or len(set(self.required_headings)) != len(self.required_headings):
            raise ValueError("章节数量上下限或必需标题不符合约定")
        if any(not h.strip() or len(h) > 200 for h in self.required_headings):
            raise ValueError("必需标题为空或过长")
        if len(self.required_headings) > self.max:
            raise ValueError("必需标题数量超过章节上限")
        return self


class ContractCitation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    density: Literal["NONE", "OPTIONAL", "PER_SECTION"] = "OPTIONAL"
    ref_kind: Literal["SOURCE", "CHUNK"] = "SOURCE"


class ContractRule(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    name: str
    level: Literal["ASSERT", "SUGGEST"]
    backtrack_to: str = Field(min_length=1, max_length=100)
    parameters: dict[str, object] = Field(default_factory=dict)
    label: str = ""

    @model_validator(mode="after")
    def validate_rule(self):
        if self.name not in RULE_NAMES:
            raise ValueError(f"契约规则未注册：{self.name}")
        from app.contract_rules import validate_rule_parameters
        validate_rule_parameters(self.name, self.parameters)
        return self


class OutputContract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    schema_version: Literal["artifact-output-contract.v1"] = "artifact-output-contract.v1"
    sections: ContractSections = Field(default_factory=ContractSections)
    fields: dict[str, dict[str, object]] = Field(default_factory=dict)
    json_schema: dict[str, object] = Field(default_factory=dict)
    citation: ContractCitation = Field(default_factory=ContractCitation)
    rules: list[ContractRule] = Field(default_factory=list, max_length=32)

    @model_validator(mode="after")
    def validate_schema(self):
        if any("required" in field and not isinstance(field["required"], bool) for field in self.fields.values()):
            raise ValueError("字段 required 必须是布尔值")
        schema = contract_schema(self)
        _reject_external_references(schema)
        Draft202012Validator.check_schema(schema)
        if len({r.name for r in self.rules}) != len(self.rules):
            raise ValueError("同一契约的规则名称不能重复")
        return self


def _reject_external_references(value):
    if isinstance(value, dict):
        if "$ref" in value or "$dynamicRef" in value:
            raise ValueError("产物契约不能加载外部或递归 Schema 引用")
        for child in value.values():
            _reject_external_references(child)
    elif isinstance(value, list):
        for child in value:
            _reject_external_references(child)


def contract_schema(contract):
    section = {"type": "object", "required": ["heading", "body", "source_refs"],
               "properties": {"heading": {"type": "string", "minLength": 1, "pattern": r"\S"},
                              "body": {"type": "string", "minLength": 1, "pattern": r"\S"},
                              "source_refs": {"type": "array", "uniqueItems": True,
                                              "items": {"type": "string", "minLength": 1, "pattern": r"\S"}}}}
    sections = {"type": "array", "minItems": contract.sections.min, "maxItems": contract.sections.max,
                "items": section, "allOf": [
                    {"contains": {"type": "object", "required": ["heading"],
                                  "properties": {"heading": {"const": heading}}}}
                    for heading in contract.sections.required_headings]}
    if not sections["allOf"]:
        sections.pop("allOf")
    properties = {"title": {"type": "string", "minLength": 1, "pattern": r"\S"},
                  "markdown": {"type": "string", "minLength": 1, "pattern": r"\S"}, "sections": sections}
    required = ["title", "markdown", "sections"]
    aliases = {"min_items": "minItems", "max_items": "maxItems", "min_length": "minLength", "max_length": "maxLength"}
    for name, field in contract.fields.items():
        definition = {aliases.get(k, k): deepcopy(v) for k, v in field.items() if k != "required"}
        # 基础字段不能被自定义契约削弱，附加约束以 allOf 合并。
        properties[name] = {"allOf": [properties[name], definition]} if name in properties else definition
        if field.get("required", True) and name not in required:
            required.append(name)
    schema = {"type": "object", "properties": properties, "required": required}
    return {"allOf": [schema, deepcopy(contract.json_schema)]} if contract.json_schema else schema


def validate_output_contract(raw, node_ids=None):
    contract = OutputContract.model_validate(raw)
    if node_ids is not None and any(rule.backtrack_to not in node_ids for rule in contract.rules):
        raise ValueError("契约规则的 backtrack_to 不在技能图中")
    return contract


def evaluate_output_contract(result, plan):
    from app.contract_rules import run_rule
    raw = plan.declared_output_contract
    if not raw:
        from app.artifact_skill_catalog import resolve_output_contract
        raw = resolve_output_contract(plan.skill_key, plan.action_key) or {
            "sections": {"required_headings": list(plan.outline)}, "rules": []}
    contract = validate_output_contract(raw,
                                       {node.node_id for node in plan.node_sequence})
    payload = {"title": result.result_title, "markdown": result.result_payload.get("markdown"),
               "sections": result.result_payload.get("sections")}
    payload.update({key: result.result_payload[key] for key in contract.fields if key in result.result_payload})
    checks = []
    errors = sorted(Draft202012Validator(contract_schema(contract)).iter_errors(payload),
                    key=lambda e: tuple(str(x) for x in e.absolute_path))
    for error in errors[:80]:
        path = ".".join(str(x) for x in error.absolute_path) or "$"
        checks.append(ArtifactContractCheckTrace(label=f"schema:{path}", status="FAIL",
            detail=f"产物结构不符合契约：{path}（{error.validator}）",
            metadata={"rule_name": "json_schema", "level": "ASSERT", "path": path,
                      "validator": error.validator, "backtrack_to": _writer_node(plan)}))
    if not errors:
        checks.append(ArtifactContractCheckTrace(label="json_schema", status="PASS", detail="产物结构通过契约校验",
            metadata={"rule_name": "json_schema", "level": "ASSERT", "schema_version": contract.schema_version}))
    if contract.citation.density == "PER_SECTION":
        for index, section in enumerate(payload.get("sections", []) if isinstance(payload.get("sections"), list) else []):
            if isinstance(section, dict) and not section.get("source_refs"):
                checks.append(ArtifactContractCheckTrace(label=f"citation:{index}", status="FAIL",
                    detail=f"章节缺少必需引用：{section.get('heading', index)}",
                    metadata={"rule_name": "citation_density", "level": "ASSERT", "section_index": index,
                              "ref_kind": contract.citation.ref_kind, "backtrack_to": _writer_node(plan)}))
    for rule in contract.rules:
        outcome = run_rule(rule, result, plan)
        checks.append(ArtifactContractCheckTrace(label=rule.label or rule.name,
            status="PASS" if outcome["passed"] else "FAIL" if rule.level == "ASSERT" else "WARN",
            detail=outcome["detail"], metadata={**outcome.get("metadata", {}), "rule_name": rule.name,
                "level": rule.level, "backtrack_to": rule.backtrack_to}))
    return checks


def _writer_node(plan):
    return next((n.node_id for n in plan.node_sequence if n.node_id == "write"), plan.node_sequence[0].node_id if plan.node_sequence else "")


def verify_native_skill_result(result, skill_key, action_key):
    """专用产物保留冻结证据验证，并经过同一声明式结构门控。"""
    from types import SimpleNamespace
    from app.artifact_skill_catalog import resolve_output_contract
    from app.registry import resolve_production_action, resolve_skill_graph
    from app.models import ArtifactOutputContractTrace

    action = resolve_production_action(action_key)
    graph = resolve_skill_graph(action.default_skill_graph_key)
    plan = SimpleNamespace(declared_output_contract=resolve_output_contract(skill_key, action_key),
                           node_sequence=graph.nodes)
    checks = evaluate_output_contract(result, plan)
    failed = [c.detail for c in checks if c.status == "FAIL"]
    warnings = [c.detail for c in checks if c.status == "WARN"]
    trace = ArtifactOutputContractTrace(status="FAIL" if failed else "WARN" if warnings else "PASS",
        action_checks=checks, passed_checks=[c.detail for c in checks if c.status == "PASS"],
        failed_checks=failed, warnings=warnings)
    result.result_payload["output_contract_trace"] = trace.model_dump(mode="json")
    if failed:
        raise ValueError("产物输出契约未通过：" + "; ".join(failed))
    verification = dict(result.result_payload.get("verification", {}))
    verification["status"] = "WARN" if warnings else verification.get("status", "PASS")
    verification["warnings"] = list(dict.fromkeys([*verification.get("warnings", []), *warnings]))
    result.result_payload["verification"] = verification
