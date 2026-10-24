package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;

/** 需求和事实的权威记录；所有提交在已有完成事务和租约校验之后处理。 */
final class ResearchRequirementCoverageService {
    static final String CONTRACT_SCHEMA = "research-requirement-contract.v1";
    static final String PROPOSAL_SCHEMA = "research-requirement-coverage.v1";
    static final String INCOMPLETE = "REQUIREMENT_COVERAGE_INCOMPLETE";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ResearchRequirementCoverageService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    boolean enabled(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path(ResearchAgentFeatureFlagService.REQUIREMENT_COVERAGE).asBoolean(false);
    }

    boolean definitionsRequired(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path("requirement_definition_version").asInt(1) >= 2;
    }

    boolean answerPolicy(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path(ResearchAgentFeatureFlagService.REQUIREMENT_COVERAGE).asBoolean()
                && read(flags).path(ResearchAnswerPolicy.FLAG).asInt() == 1;
    }

    boolean modernState(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path(ResearchAgentFeatureFlagService.REQUIREMENT_COVERAGE).asBoolean()
                && read(flags).path("requirement_state_version").asInt(2) >= 3;
    }

    String requiredCapability(String executionContextJson) {
        if (executionContextJson == null || executionContextJson.isBlank()) return "";
        JsonNode policy = read(executionContextJson).path("query_policy");
        if (policy.path(ResearchCompletionGapPolicy.FLAG).asInt() == 1
                || policy.path("planning_input").path(ResearchCompletionGapPolicy.FLAG).asInt() == 1
                || policy.path("synthesis_input").path(ResearchCompletionGapPolicy.FLAG).asInt() == 1
                || policy.path("requirement_contract").path(ResearchCompletionGapPolicy.FLAG).asInt() == 1)
            return "completion_gap_policy_v1";
        if (policy.path(ResearchAnswerPolicy.FLAG).asInt() == 1
                || policy.path("planning_input").path(ResearchAnswerPolicy.FLAG).asInt() == 1
                || policy.path("synthesis_input").path(ResearchAnswerPolicy.FLAG).asInt() == 1
                || policy.path("requirement_contract").path(ResearchAnswerPolicy.FLAG).asInt() == 1)
            return "requirement_answer_policy_v1";
        if (read(executionContextJson).path("query_policy").path("completion_runtime_version").asInt() == 1
                || read(executionContextJson).path("query_policy").path("synthesis_input").path("report_recovery_version").asInt() == 1
                || read(executionContextJson).path("query_policy").path("requirement_contract").path("source_scope_checks_version").asInt() == 1)
            return "completion_runtime_v1";
        JsonNode contract = read(executionContextJson).path("query_policy").path("requirement_contract");
        if (contract.isMissingNode()) {
            if (read(executionContextJson).path("query_policy").path("audit_input").path("fact_semantics_version").asInt() >= 1) return "fact_semantics_v1";
            var synthesis = read(executionContextJson).path("query_policy").path("synthesis_input");
            if (synthesis.path("report_coverage_version").asInt() >= 1) return "report_coverage_v1";
            if (synthesis.path("statement_support_version").asInt() >= 1) return "statement_support_v1";
            if (synthesis.path("report_upload_version").asInt() >= 1) return "synthesis_upload_v1";
            return synthesis.path("source_scope_version").asInt() >= 1 ? "source_scope_v1"
                    : synthesis.has("requirement_coverage") ? "requirement_coverage_v2" : "";
        }
        if (contract.path("coverage_execution_version").asInt() >= 1) return "coverage_execution_v1";
        if (contract.has("source_scopes")) return "source_scope_v1";
        return contract.path("schema_version").asText().endsWith(".v2") || contract.path("fact_verification_version").asInt() >= 2
                ? "requirement_coverage_v2" : "requirement_coverage_v1";
    }

    boolean improvedCompletion(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?", rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path(ResearchAgentFeatureFlagService.REQUIREMENT_COVERAGE).asBoolean()
                && read(flags).path("requirement_state_version").asInt() >= 4;
    }

    boolean fullCompletion(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?", rs -> rs.next() ? rs.getString(1) : null, runId);
        return flags != null && read(flags).path(ResearchAgentFeatureFlagService.REQUIREMENT_COVERAGE).asBoolean()
                && read(flags).path("requirement_state_version").asInt() >= 5;
    }

    boolean enforcesFacts(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",rs -> rs.next() ? rs.getString(1) : null,runId);
        return enabled(runId) && flags != null && !read(flags).path("requirement_shadow_mode").asBoolean()
                && read(flags).path("requirement_fact_accumulation").asBoolean(true);
    }

    boolean enforces(String runId) {
        String flags = jdbc.query("select agent_feature_flags_json from research_run where id = ?",rs -> rs.next() ? rs.getString(1) : null,runId);
        return enforcesFacts(runId) && flags != null && read(flags).path("requirement_driven_scheduling").asBoolean(true);
    }

    void invalidateCell(String runId, String cell, String reason) {
        if (!enabled(runId)) return;
        invalidateSynthesis(runId);
        jdbc.update("""
                update research_requirement_fact_support set qualification_status = 'DISPUTED', reason_codes_json = ?
                where qualification_status = 'QUALIFIED' and fact_id in (
                    select fact_id from research_requirement_fact where research_run_id = ? and cell_key = ?)
                """, Json.write(mapper, List.of(reason)), runId, cell);
    }

    void invalidateEvidence(String runId, String cell, java.util.Collection<String> evidenceKeys, String reason) {
        if (!enabled(runId)) return;
        Set<String> disputed = new LinkedHashSet<>();
        jdbc.query("""
                select support.fact_id, support.evidence_keys_json from research_requirement_fact_support support
                join research_requirement_fact fact on fact.fact_id = support.fact_id
                where fact.research_run_id = ? and fact.cell_key = ? and support.qualification_status = 'QUALIFIED'
                """, rs -> {
            if (strings(read(rs.getString(2))).stream().anyMatch(evidenceKeys::contains)) disputed.add(rs.getString(1));
        }, runId, cell);
        for (String fact : disputed) jdbc.update("""
                update research_requirement_fact_support set qualification_status = 'DISPUTED', reason_codes_json = ?
                where fact_id = ? and qualification_status = 'QUALIFIED'
                """, Json.write(mapper, List.of(reason)), fact);
        if (!disputed.isEmpty()) invalidateSynthesis(runId);
    }

    Map<String, Object> attach(String runId, List<String> cellKeys, Map<String, Object> policy) {
        if (!enabled(runId)) return policy;
        String startedDate = jdbc.query("select created_at from research_run where id = ?", rs -> {
            if (!rs.next() || rs.getTimestamp(1) == null) throw invalid("研究截止时间缺失");
            return rs.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }, runId);
        JsonNode configured = mapper.valueToTree(policy.getOrDefault("column_requirements", Map.of()));
        JsonNode specs = mapper.valueToTree(policy.getOrDefault("column_specs", List.of()));
        JsonNode definitions = mapper.valueToTree(policy.getOrDefault("requirement_definitions", List.of()));
        boolean v2 = definitionsRequired(runId);
        boolean modern = modernState(runId);
        List<Map<String, Object>> requirements = new ArrayList<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> sourceScopes = new ArrayList<>();
        String question = mapper.valueToTree(policy.getOrDefault("research_brief", Map.of())).path("current_question")
                .asText(String.valueOf(policy.getOrDefault("query", "")));
        String asOf = fullCompletion(runId) ? ResearchSourceScopePolicy.cutoffDate(question,startedDate) : startedDate;
        for (String cell : cellKeys) {
            int[] revision = jdbc.query("select plan_revision, entity_set_version from research_cell where research_run_id = ? and cell_key = ?",
                    rs -> { if (!rs.next()) throw invalid("需求的目标单元格不存在"); return new int[] {rs.getInt(1), rs.getInt(2)}; }, runId, cell);
            String column = cell.substring(cell.indexOf(':') + 1);
            String entityKey = cell.substring(0, cell.indexOf(':'));
            String entity = String.valueOf(policy.getOrDefault("entity_label", entityKey));
            Map<String, Object> sourceScope = new LinkedHashMap<>(ResearchSourceScopePolicy.freeze(cell, entity, question, asOf));
            StringBuilder cellText = new StringBuilder();
            strings(configured.path(column)).forEach(text -> cellText.append(' ').append(text));
            for (JsonNode spec : specs) if (column.equals(spec.path("key").asText())) cellText.append(' ').append(spec.path("label").asText());
            if (v2) for (JsonNode definition : definitions)
                if (column.equals(definition.path("dimension_key").asText())) cellText.append(' ').append(definition.path("text").asText());
            sourceScope.put("temporal_mode", ResearchSourceScopePolicy.cellTemporalMode(question, cellText.toString()));
            if (fullCompletion(runId)) sourceScope.put("cutoff_mode", asOf.equals(startedDate) ? "RUN_STARTED_AT" : "REQUESTED_DATE");
            if (modern) sourceScopes.add(sourceScope);
            Map<String, Object> requirementScope = modern ? new LinkedHashMap<>(Map.of("temporal_mode", sourceScope.get("temporal_mode"),
                    "required_versions", sourceScope.get("required_versions"))) : Map.of();
            if (answerPolicy(runId)) requirementScope.put("has_explicit_definitions", !definitions.isEmpty());
            Set<String> explicitTexts = new LinkedHashSet<>();
            if (v2) for (JsonNode definition : definitions) {
                String targetColumn = definition.path("dimension_key").asText();
                List<String> bound = strings(definition.path("entity_keys"));
                if ("RUN".equals(definition.path("scope_type").asText())) {
                    saveRequirement(runId, "@run:" + targetColumn, revision[0], 0, "研究对象范围", asOf,
                            definition.path("text").asText(), "EXPLICIT", definition.path("completion_mode").asText(),
                            definition.path("minimum_count").asInt(), definition.path("question_quote").asText(), "RUN", modern,
                            ResearchCompletionGapPolicy.enabled(jdbc,runId) ? ResearchCompletionGapPolicy.scope(
                                    Map.of("column_key", targetColumn, "entity_keys", bound), mapper.convertValue(definition,Map.class))
                                    : Map.of("column_key", targetColumn, "entity_keys", bound));
                    if ("COMPARISON".equals(definition.path("completion_mode").asText()) && column.equals(targetColumn) && bound.contains(entityKey)) {
                        String localText = "核验 " + entity + " 在 " + targetColumn + " 比较维度的行为、条件和限制";
                        requirements.add(saveRequirement(runId,cell,revision[0],revision[1],entity,asOf,localText,"EXPLICIT","SINGLE",1,
                                definition.path("question_quote").asText(),"CELL",modern,requirementScope));
                    }
                } else if (column.equals(targetColumn) && (bound.isEmpty() || bound.contains(entityKey))) {
                    explicitTexts.add(definition.path("text").asText());
                    requirements.add(saveRequirement(runId, cell, revision[0], revision[1], entity, asOf,
                            definition.path("text").asText(), "EXPLICIT", definition.path("completion_mode").asText(),
                            definition.path("minimum_count").asInt(), definition.path("question_quote").asText(), "CELL", modern,
                            ResearchCompletionGapPolicy.enabled(jdbc,runId) ? ResearchCompletionGapPolicy.scope(requirementScope, mapper.convertValue(definition,Map.class)) : requirementScope));
                }
            }
            List<String> texts = strings(configured.path(column));
            if (texts.isEmpty()) {
                for (JsonNode spec : specs) if (column.equals(spec.path("key").asText())) {
                    String label = spec.path("label").asText(column);
                    texts = List.of(label);
                    break;
                }
            }
            if (texts.isEmpty() || texts.size() > 6) throw invalid("每列需要 1 到 6 个明确需求");
            for (String text : new LinkedHashSet<>(texts)) {
                if (!explicitTexts.contains(text)) requirements.add(saveRequirement(runId, cell, revision[0], revision[1],
                        entity, asOf, text, "DERIVED", "SINGLE", 1, "", "CELL", modern, requirementScope));
            }
            facts.addAll(facts(runId, cell, revision[0], revision[1]));
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schema_version", v2 ? "research-requirement-contract.v2" : CONTRACT_SCHEMA);
        contract.put("fact_verification_version", 2);
        if (answerPolicy(runId)) contract.put(ResearchAnswerPolicy.FLAG, 1);
        if (ResearchCompletionGapPolicy.enabled(jdbc,runId)) {
            contract.put(ResearchCompletionGapPolicy.FLAG, 1);
            contract.put("related_materials", relatedMaterials(runId,cellKeys));
        }
        if (fullCompletion(runId)) contract.put("shadow_mode", !enforcesFacts(runId));
        if (fullCompletion(runId)) contract.put("source_scope_checks_version", 1);
        if (modern && improvedCompletion(runId)) {
            contract.put("coverage_execution_version", 1);
            List<Map<String, Object>> deferred = new ArrayList<>();
            Set<String> scanned = new LinkedHashSet<>();
            for (Map<String, Object> requirement : requirements) {
                String cell = String.valueOf(requirement.get("cell_key"));
                if ("RUN".equals(requirement.get("scope_type")) || !scanned.add(cell)) continue;
                deferred.addAll(new ResearchDeferredMaterialService(jdbc, mapper).load(runId, cell,
                        ((Number) requirement.get("plan_revision")).intValue(), ((Number) requirement.get("entity_set_version")).intValue()));
            }
            contract.put("deferred_materials", deferred.stream().limit(24).toList());
        }
        if (modern) {
            contract.put("fact_semantics_version", 1);
            contract.put("source_scopes", sourceScopes);
        }
        contract.put("as_of_date", asOf);
        contract.put("requirements", requirements);
        contract.put("facts", facts);
        Set<String> eligible = new LinkedHashSet<>();
        facts.forEach(fact -> eligible.add(String.valueOf(fact.get("fact_id"))));
        Set<String> ids = new LinkedHashSet<>();
        requirements.forEach(requirement -> ids.add(String.valueOf(requirement.get("requirement_id"))));
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        facts.forEach(fact -> details.put(String.valueOf(fact.get("fact_id")), fact));
        Set<String> satisfied = satisfied(ids, eligible, details);
        List<String> missingIds = ids.stream().filter(id -> !satisfied.contains(id)).toList();
        contract.put("missing_requirement_ids", missingIds);
        if (contract.containsKey("deferred_materials")) {
            List<Map<String, Object>> pending = new ArrayList<>();
            for (Object raw : (List<?>) contract.get("deferred_materials")) {
                Map<String, Object> material = mapper.convertValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() { });
                JsonNode item = mapper.valueToTree(material);
                List<String> linked = strings(item.path("requirement_ids"));
                if (!linked.isEmpty() && linked.stream().noneMatch(missingIds::contains)) continue;
                String text = item.path("claim_text").asText().replaceAll("\\s+", " ").strip();
                boolean retained = facts.stream().anyMatch(fact -> item.path("cell_key").asText().equals(fact.get("cell_key"))
                        && text.equals(String.valueOf(fact.get("claim_text")).replaceAll("\\s+", " ").strip()));
                if (!retained) pending.add(material);
            }
            contract.put("deferred_materials", List.copyOf(pending));
        }
        contract.put("contract_digest", new ResearchAgentCompletionCanonicalizer().canonicalDigest(contract));
        Map<String, Object> result = new LinkedHashMap<>(policy);
        result.put("requirement_contract", contract);
        return Map.copyOf(result);
    }

    private List<Map<String,Object>> relatedMaterials(String runId, List<String> targetCells) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (String target : targetCells) {
            String entity = target.substring(0,target.indexOf(':'));
            var siblings = jdbc.query("select cell_key,plan_revision,entity_set_version from research_cell where research_run_id = ? and substring(cell_key,1,locate(':',cell_key)-1) = ? and cell_key <> ? order by cell_key limit 8",
                    (rs,n) -> Map.<String,Object>of("cell",rs.getString(1),"plan",rs.getInt(2),"entities",rs.getInt(3)),runId,entity,target);
            Set<String> seen = new LinkedHashSet<>();
            for (var sibling : siblings) for (var fact : facts(runId,String.valueOf(sibling.get("cell")),(int)sibling.get("plan"),(int)sibling.get("entities"))) {
                for (Object raw : (List<?>) fact.get("evidence_quotes")) {
                    var quote = mapper.valueToTree(raw); String url = quote.path("source_url").asText();
                    if (url.isBlank() || !seen.add(url) || seen.size() > 3) continue;
                    result.add(Map.of("cell_key",target,"source_cell_key",sibling.get("cell"),"source_url",url,
                            "claim_text",String.valueOf(fact.get("claim_text")).substring(0,Math.min(1200,String.valueOf(fact.get("claim_text")).length()))));
                    if (result.size() >= 12) return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> saveRequirement(String runId, String cell, int plan, int entities, String entity,
                                               String asOf, String text, String origin, String mode, int minimum,
                                               String quote, String scope, boolean modern, Map<String, Object> scopeDetails) {
        if (text.isBlank() || text.codePointCount(0, text.length()) > 1000 || minimum < 1 || minimum > 20)
            throw invalid("需求文本或数量不符合约定");
        Map<String, Object> frozenScope = new LinkedHashMap<>(scopeDetails);
        String temporalMode = ResearchSourceScopePolicy.temporalMode(text + " " + quote);
        if (modern) frozenScope.put("temporal_mode", "UNSPECIFIED".equals(temporalMode)
                ? scopeDetails.getOrDefault("temporal_mode", "UNSPECIFIED") : temporalMode);
        boolean mandatory = true;
        if (answerPolicy(runId)) {
            Map<String,Object> answer = ResearchAnswerPolicy.freeze(text, quote, origin, mode, minimum, scopeDetails,
                    Boolean.TRUE.equals(scopeDetails.get("has_explicit_definitions")));
            if (scopeDetails.get("answer_definition") instanceof Map<?,?> definition) {
                Map<String,Object> structured = new LinkedHashMap<>(answer);
                for (String field : List.of("answer_kind","temporal_mode","conditions","source_constraints")) structured.put(field,definition.get(field));
                structured.put("version",2); answer = Map.copyOf(structured);
                frozenScope.remove("answer_definition");
            }
            frozenScope.put("answer_contract", answer);
            frozenScope.put("temporal_mode", answer.get("temporal_mode"));
            mandatory = Boolean.TRUE.equals(answer.get("mandatory"));
        }
        String id = "DERIVED".equals(origin) && "SINGLE".equals(mode)
                ? key("req:", runId, cell, plan, entities, asOf, text)
                : key("req:", runId, cell, plan, entities, asOf, text, origin, mode, minimum,
                        new ResearchAgentCompletionCanonicalizer().canonicalJsonValue(frozenScope));
        jdbc.update("""
                insert into research_requirement(requirement_id, research_run_id, cell_key, plan_revision, entity_set_version,
                    requirement_text, entity_label, origin, mandatory, as_of_date, completion_mode, minimum_count,
                    question_quote, scope_type, scope_json)
                select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                where not exists (select 1 from research_requirement where requirement_id = ?)
                """, id, runId, cell, plan, entities, text, entity, origin, mandatory, asOf, mode, minimum, quote,
                scope, Json.write(mapper, frozenScope), id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requirement_id", id); result.put("cell_key", cell); result.put("text", text);
        result.put("entity_label", entity); result.put("plan_revision", plan); result.put("entity_set_version", entities);
        result.put("mandatory", mandatory); result.put("origin", origin); result.put("completion_mode", mode);
        if (fullCompletion(runId)) result.putAll(recordLineage(runId,id,cell,entity,text,quote,scope,plan,entities));
        result.put("minimum_count", minimum); result.put("question_quote", quote); result.put("scope_type", scope);
        result.put("scope", Map.copyOf(frozenScope));
        return Map.copyOf(result);
    }

    private Map<String,Object> recordLineage(String run, String id, String cell, String entity, String text, String quote,
            String scope, int plan, int entities) {
        String lineage = key("lineage:",run,entity,scope,text,quote);
        String previous = jdbc.query("""
                select revision.requirement_id from research_requirement_revision revision
                join research_requirement requirement on requirement.requirement_id = revision.requirement_id
                where revision.research_run_id = ? and revision.lineage_key = ? and revision.requirement_id <> ?
                  and (requirement.plan_revision < ? or (requirement.plan_revision = ? and requirement.entity_set_version < ?))
                order by requirement.plan_revision desc,requirement.entity_set_version desc,revision.created_at desc,revision.requirement_id limit 1
                """,rs -> rs.next() ? rs.getString(1) : null,run,lineage,id,plan,plan,entities);
        String reason = previous == null ? "INITIAL_REQUIREMENT" : "PLAN_OR_ENTITY_SCOPE_REVISED";
        jdbc.update("insert into research_requirement_revision(requirement_id,research_run_id,lineage_key,predecessor_requirement_id,revision_reason) select ?,?,?,?,? where not exists (select 1 from research_requirement_revision where requirement_id = ?)",
                id,run,lineage,previous,reason,id);
        return jdbc.query("select lineage_key,predecessor_requirement_id,revision_reason from research_requirement_revision where requirement_id = ?",
                rs -> { rs.next(); return Map.<String,Object>of("lineage_key",rs.getString(1),"predecessor_requirement_id",rs.getString(2) == null ? "" : rs.getString(2),"revision_reason",rs.getString(3)); },id);
    }

    static void validateProposal(Object raw) {
        JsonNode node = new ObjectMapper().valueToTree(raw);
        if (!fields(node).equals(Set.of("schema_version", "contract_digest", "observations"))
                || !Set.of(PROPOSAL_SCHEMA, "research-requirement-coverage.v2").contains(node.path("schema_version").asText())
                || !node.path("contract_digest").asText().matches("sha256:[0-9a-f]{64}")
                || !node.path("observations").isArray() || node.path("observations").size() > 192) throw invalid("需求覆盖建议格式不符合约定");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node.path("observations")) {
            boolean v2 = node.path("schema_version").asText().endsWith(".v2");
            if (!fields(item).equals(v2 ? Set.of("requirement_id", "evidence_keys", "complete") : Set.of("requirement_id", "evidence_keys"))
                    || (v2 && !item.path("complete").isBoolean())
                    || !item.path("requirement_id").asText().matches("req:[0-9a-f]{48}")
                    || !ids.add(item.path("requirement_id").asText())
                    || !item.path("evidence_keys").isArray() || item.path("evidence_keys").isEmpty()
                    || item.path("evidence_keys").size() > 36) throw invalid("需求覆盖引用不符合约定");
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode evidence : item.path("evidence_keys")) {
                if (!evidence.isTextual() || evidence.asText().isBlank() || evidence.asText().length() > 64
                        || !keys.add(evidence.asText())) throw invalid("需求覆盖证据标识不符合约定");
            }
        }
    }

    Assessment assess(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope,
                      Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority,
                      ResearchEvidenceQualificationService qualification) {
        JsonNode contract = read(task.executionContextJson()).path("query_policy").path("requirement_contract");
        Object raw = envelope.extractionDiagnostics() == null ? null : envelope.extractionDiagnostics().get("requirement_coverage");
        if (contract.isMissingNode()) {
            if (raw != null) throw invalid("当前任务没有需求覆盖契约");
            return Assessment.empty();
        }
        if (raw == null) throw invalid("当前任务需要支持需求覆盖的 Worker");
        validateProposal(raw);
        JsonNode proposal = mapper.valueToTree(raw);
        if (contract.path("schema_version").asText().endsWith(".v2")
                && !"research-requirement-coverage.v2".equals(proposal.path("schema_version").asText()))
            throw invalid("当前需求契约需要覆盖协议 v2");
        if (!contract.path("contract_digest").asText().equals(proposal.path("contract_digest").asText()))
            throw invalid("需求覆盖契约已经变化");
        Map<String, JsonNode> requirements = new LinkedHashMap<>();
        for (JsonNode requirement : contract.path("requirements")) requirements.put(requirement.path("requirement_id").asText(), requirement);
        Object selectionRaw = envelope.extractionDiagnostics().get("fact_selection");
        if (selectionRaw != null) {
            if (contract.path("coverage_execution_version").asInt() != 1) throw invalid("当前任务不支持事实选择扩展");
            ResearchDeferredMaterialService.validate(selectionRaw);
            JsonNode selection = mapper.valueToTree(selectionRaw);
            Set<String> submitted = envelope.evidence().stream().map(ResearchAgentCompletionEnvelope.Evidence::evidenceKey).collect(java.util.stream.Collectors.toSet());
            if (!submitted.containsAll(strings(selection.path("submitted_evidence_keys")))) throw invalid("事实选择引用了未提交证据");
            Set<String> cells = requirements.values().stream().map(req -> req.path("cell_key").asText()).collect(java.util.stream.Collectors.toSet());
            for (JsonNode material : selection.path("deferred")) {
                String cell = material.path("cell_key").asText();
                if (!cells.contains(cell)) throw invalid("延后材料超出任务范围");
                for (String id : strings(material.path("requirement_ids")))
                    if (!requirements.containsKey(id) || !cell.equals(requirements.get(id).path("cell_key").asText())) throw invalid("延后材料引用了范围外需求");
            }
        }
        Map<String, Fact> current = new LinkedHashMap<>();
        Object clustersRaw = envelope.extractionDiagnostics().get("claim_clusters");
        if (clustersRaw == null) throw invalid("需求累计任务必须提交事实簇");
        ResearchClaimClusterService.validateClusters(clustersRaw);
        for (JsonNode cluster : mapper.valueToTree(clustersRaw)) {
            String cell = cluster.path("cell_key").asText();
            if (requirements.values().stream().noneMatch(req -> cell.equals(req.path("cell_key").asText())))
                throw invalid("事实不属于当前需求范围");
            List<String> reasons = new ArrayList<>(qualification.qualifyFact(task, envelope, cluster, authority));
            String claim = cluster.path("claim_text").asText();
            reasons.addAll(new ResearchRepairExecutionService(jdbc, mapper).factRejectionReasons(task.executionContextJson(), envelope, cell, claim));
            String id = key("fact:", task.runId(), cell, task.planRevision(), task.entitySetVersion(), claim.replaceAll("\\s+", " ").strip());
            List<String> evidenceKeys = strings(cluster.path("evidence_keys"));
            List<String> identities = evidenceKeys.stream().map(authority::get).filter(java.util.Objects::nonNull)
                    .flatMap(source -> source.quorumIdentities().stream()).distinct().sorted().toList();
            List<String> fingerprints = envelope.evidence().stream().filter(evidence -> evidenceKeys.contains(evidence.evidenceKey()))
                    .map(evidence -> ResearchSourceIndependence.fingerprint(evidence.quoteText())).distinct().sorted().toList();
            JsonNode sourceScope = ResearchSourceScopePolicy.scope(contract, cell);
            if (contract.path("source_scope_checks_version").asInt() == 1) {
                var scopedCopy = sourceScope.deepCopy();
                if (scopedCopy.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode)scopedCopy).put("allow_historical_facts", requirements.values().stream()
                        .anyMatch(req -> cell.equals(req.path("cell_key").asText()) && Set.of("HISTORICAL", "TIME_INVARIANT").contains(req.path("scope").path("temporal_mode").asText())));
                sourceScope = scopedCopy;
            }
            JsonNode semantic = contract.path("source_scope_checks_version").asInt() == 1
                    ? ResearchSourceScopeChecks.check(envelope, cluster, sourceScope, authority, reasons) : mapper.createObjectNode();
            List<Map<String, Object>> sourceChecks = new ArrayList<>();
            for (var evidence : envelope.evidence()) if (evidenceKeys.contains(evidence.evidenceKey())) {
                var source = authority.get(evidence.evidenceKey());
                if (source == null) continue;
                Map<String, Object> check = new LinkedHashMap<>(ResearchSourceScopePolicy.evaluate(sourceScope, source.sourceUrl(), source.sourceTitle(), evidence.quoteText(), source.scopeText()));
                check.put("evidence_key", evidence.evidenceKey()); sourceChecks.add(Map.copyOf(check));
                if (!semantic.isEmpty()) {
                    check.put("semantic_check", mapper.convertValue(semantic, Object.class));
                    check.put("document_date",semantic.path("date").asText());
                    check.put("published_at", "PUBLISHED".equals(semantic.path("date_kind").asText()) ? semantic.path("date").asText() : "");
                    check.put("updated_at", "UPDATED".equals(semantic.path("date_kind").asText()) ? semantic.path("date").asText() : "");
                    check.put("freshness_status", semantic.path("date").asText().isBlank() ? "UNKNOWN" : "DATED");
                    check.put("date_basis", "ARCHIVED_LITERAL");
                    check.put("original_url", source.sourceUrl());
                    String canonicalUrl;
                    try { canonicalUrl = java.net.URI.create(source.sourceUrl()).normalize().toString().replaceFirst("#.*$", ""); }
                    catch (RuntimeException error) { canonicalUrl = source.sourceUrl(); }
                    check.put("canonical_url",canonicalUrl);
                    check.put("lineage_digest",source.lineageDigest());
                    check.put("snapshot_key",source.snapshotKey() == null ? "" : source.snapshotKey());
                    String fetched = "EXTERNAL_ARCHIVED".equals(source.snapshotStatus()) ? jdbc.query(
                            "select created_at from research_external_snapshot where research_run_id = ? and snapshot_key = ? order by created_at desc limit 1",
                            rs -> rs.next() ? rs.getTimestamp(1).toInstant().toString() : "",task.runId(),source.snapshotKey()) : "";
                    check.put("fetched_at",fetched);
                    sourceChecks.set(sourceChecks.size() - 1, Map.copyOf(check));
                }
            }
            Fact fact = new Fact(id, cell, claim, evidenceKeys, cluster.path("cluster_key").asText(), reasons, identities, fingerprints,
                    Map.of("policy", mapper.convertValue(sourceScope, Object.class), "evidence", sourceChecks));
            current.put(fact.clusterKey(), fact);
        }
        Map<String, Map<String, Object>> live = new HashMap<>();
        Set<String> loadedCells = new LinkedHashSet<>();
        for (JsonNode requirement : requirements.values()) {
            String cell = requirement.path("cell_key").asText();
            if (!loadedCells.add(cell)) continue;
            for (Map<String, Object> fact : facts(task.runId(), cell, task.planRevision(), task.entitySetVersion()))
                live.put(String.valueOf(fact.get("fact_id")), fact);
        }
        Map<String, List<FactReference>> refs = new HashMap<>();
        for (JsonNode previous : contract.path("facts")) {
            String id = previous.path("fact_id").asText();
            if (!live.containsKey(id)) continue;
            if (!((List<?>) live.get(id).get("evidence_keys")).containsAll(strings(previous.path("evidence_keys")))) continue;
            for (String key : strings(previous.path("evidence_keys")))
                refs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new FactReference(id, previous.path("cell_key").asText(), true));
        }
        Set<String> currentEligible = new LinkedHashSet<>();
        for (Fact fact : current.values()) {
            boolean eligible = fact.reasons().isEmpty() && eligibleWithCurrent(task, fact);
            if (eligible) currentEligible.add(fact.id());
            // 先保留通过证据与反证检查的覆盖建议；双路资格仍由服务端单独计算。
            boolean retain = modernState(task.runId()) ? fact.reasons().isEmpty() : eligible;
            fact.keys().forEach(key -> refs.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new FactReference(fact.id(), fact.cell(), retain)));
        }
        Set<String> qualifiedIds = new LinkedHashSet<>(live.keySet());
        qualifiedIds.addAll(currentEligible);
        List<Observation> observations = new ArrayList<>();
        for (JsonNode item : proposal.path("observations")) {
            String requirementId = item.path("requirement_id").asText();
            JsonNode requirement = requirements.get(requirementId);
            if (requirement == null) throw invalid("覆盖建议引用了任务范围之外的需求");
            Set<String> ids = new TreeSet<>();
            boolean qualified = true;
            boolean partialSupport = false;
            for (String key : strings(item.path("evidence_keys"))) {
                List<FactReference> references = refs.get(key);
                if (references == null) {
                    // 不合格的旧证据不能用于当前覆盖；未知引用仍拒绝，防止跨任务串用证据。
                    boolean historical = java.util.stream.StreamSupport.stream(contract.path("facts").spliterator(), false)
                            .anyMatch(fact -> strings(fact.path("evidence_keys")).contains(key));
                    if (!historical) throw invalid("覆盖建议引用了未提交或未冻结的证据");
                    if (answerPolicy(task.runId())) partialSupport = true; else qualified = false;
                    continue;
                }
                if (!answerPolicy(task.runId())) {
                    FactReference ref = references.get(references.size() - 1);
                    if (!ref.cell().equals(requirement.path("cell_key").asText())) throw invalid("覆盖证据属于其他对象或列");
                    qualified &= ref.qualified(); ids.add(ref.id());
                } else {
                    ReferenceSelection selection = selectReferences(references,requirement.path("cell_key").asText());
                    partialSupport |= selection.partial(); ids.addAll(selection.ids());
                }
            }
            int minimum = "DISTINCT_FACTS".equals(requirement.path("completion_mode").asText())
                    ? requirement.path("minimum_count").asInt(1) : 1;
            Set<String> completionIds = new LinkedHashSet<>(ids);
            if ("CURRENT".equals(requirement.path("scope").path("temporal_mode").asText())
                    && (requirement.path("scope").path("required_versions").isEmpty() || fullCompletion(task.runId()))) {
                completionIds.removeIf(id -> !canAnswerCurrent(id, current.values(), live));
            }
            boolean feedbackNeedsNewFact = fullCompletion(task.runId()) && jdbc.queryForObject(
                    "select count(*) from research_report_feedback where requirement_id = ? and feedback_status <> 'RESOLVED'", Integer.class, requirementId) > 0;
            boolean hasNewFact = ids.stream().anyMatch(id -> currentEligible.contains(id) && !live.containsKey(id));
            if (qualified && !ids.isEmpty()) observations.add(new Observation(requirementId, List.copyOf(ids),
                    item.path("complete").asBoolean(true) && !partialSupport && (!feedbackNeedsNewFact || hasNewFact)
                            && distinctFactCount(completionIds, current.values(), live) >= minimum));
        }
        Set<String> previousSatisfied = satisfied(requirements.keySet(), live.keySet(), live);
        Map<String, Map<String, Object>> details = new LinkedHashMap<>(live);
        current.values().stream().filter(fact -> currentEligible.contains(fact.id())).forEach(fact -> details.put(fact.id(),
                Map.of("source_scopes", List.of(fact.sourceScope()))));
        Set<String> satisfied = satisfied(requirements.keySet(), qualifiedIds, details);
        observations.stream().filter(Observation::complete).filter(item -> qualifiedIds.containsAll(item.factIds()))
                .forEach(item -> satisfied.add(item.requirementId()));
        Set<String> blocked = new LinkedHashSet<>();
        for (var candidate : envelope.candidates()) {
            if (requirements.values().stream().anyMatch(req -> req.path("mandatory").asBoolean(true) && candidate.cellKey().equals(req.path("cell_key").asText())
                    && !satisfied.contains(req.path("requirement_id").asText()))) blocked.add(candidate.candidateKey());
            if (contract.path("source_scope_checks_version").asInt() == 1 && current.values().stream().anyMatch(fact ->
                    fact.cell().equals(candidate.cellKey()) && fact.reasons().stream().anyMatch(reason -> reason.startsWith("SOURCE_"))
                            && candidate.evidenceKeys().stream().anyMatch(fact.keys()::contains)))
                blocked.add(candidate.candidateKey());
        }
        return new Assessment(List.copyOf(current.values()), List.copyOf(observations), enforcesFacts(task.runId()) ? Set.copyOf(blocked) : Set.of(),
                qualifiedIds.size() - live.size(), satisfied.size() - previousSatisfied.size(), true);
    }

    void persist(ResearchAgentCompletionCommitter.TaskRow task, String completionId, Assessment assessment) {
        if (!assessment.active()) return;
        Set<String> previousIds = fullCompletion(task.runId()) ? new LinkedHashSet<>(jdbc.queryForList(
                "select fact_id from research_requirement_fact where research_run_id = ?", String.class, task.runId())) : Set.of();
        if (!assessment.facts().isEmpty() || !assessment.observations().isEmpty()) invalidateSynthesis(task.runId());
        for (Fact fact : assessment.facts()) {
            jdbc.update("""
                    insert into research_requirement_fact(fact_id, research_run_id, cell_key, plan_revision, entity_set_version, claim_text)
                    select ?, ?, ?, ?, ?, ? where not exists (select 1 from research_requirement_fact where fact_id = ?)
                    """, fact.id(), task.runId(), fact.cell(), task.planRevision(), task.entitySetVersion(), fact.claim(), fact.id());
            jdbc.update("""
                    insert into research_requirement_fact_support(support_id, fact_id, completion_id, cluster_key,
                        evidence_keys_json, qualification_status, reason_codes_json, support_task_id, quorum_group_key,
                        candidate_quorum, candidate_slot, source_identities_json, source_fingerprints_json, source_scope_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key("support:", completionId, fact.clusterKey()), fact.id(), completionId, fact.clusterKey(),
                    Json.write(mapper, fact.keys()), fact.reasons().isEmpty() ? "QUALIFIED" : "REJECTED", Json.write(mapper, fact.reasons()),
                    task.id(), task.quorumGroupKey(), task.candidateQuorum(), task.candidateSlot(),
                    Json.write(mapper, fact.identities()), Json.write(mapper, fact.fingerprints()), Json.write(mapper, fact.sourceScope()));
        }
        for (Observation observation : assessment.observations()) {
            jdbc.update("""
                    insert into research_requirement_observation(observation_id, requirement_id, completion_id, fact_ids_json, coverage_status)
                    values (?, ?, ?, ?, ?)
                    """, key("coverage:", completionId, observation.requirementId()), observation.requirementId(),
                    completionId, Json.write(mapper, observation.factIds()), observation.complete() ? "SATISFIED" : "PARTIAL");
            if (fullCompletion(task.runId()) && observation.complete() && observation.factIds().stream().anyMatch(id -> !previousIds.contains(id)
                    && assessment.facts().stream().anyMatch(fact -> fact.id().equals(id) && fact.reasons().isEmpty())))
                jdbc.update("update research_report_feedback set feedback_status = 'RESOLVED', updated_at = current_timestamp where research_run_id = ? and requirement_id = ? and feedback_status <> 'RESOLVED'",
                        task.runId(), observation.requirementId());
        }
        jdbc.update("""
                insert into research_requirement_progress(completion_id, research_run_id, new_fact_count, new_satisfied_requirement_count)
                values (?, ?, ?, ?)
                """, completionId, task.runId(), assessment.newFactCount(), assessment.newRequirementCount());
    }

    List<Map<String, Object>> facts(String runId, String cell, int plan, int entities) {
        return factViews(runId, cell, plan, entities, false);
    }

    /** 候选仅供独立审计使用，不能作为已确认事实交给搜索或成文任务。 */
    List<Map<String, Object>> auditFacts(String runId, String cell, int plan, int entities) {
        return factViews(runId, cell, plan, entities, true);
    }

    /** 修复只复用原双路分组已确认的代表结论，不要求同一格的其他事实也全部等价。 */
    boolean equivalentQuorum(String runId, String cell, int plan, int entities, String group) {
        if (!modernState(runId) || group == null || group.isBlank()) return false;
        List<String> ids = jdbc.queryForList("""
                select candidate_value from research_agent_candidate
                where research_run_id = ? and cell_key = ? and quorum_group_key = ? order by candidate_slot
                """, String.class, runId, cell, group).stream()
                .map(claim -> key("fact:", runId, cell, plan, entities, claim.replaceAll("\\s+", " ").strip())).distinct().toList();
        if (ids.size() != 2 || !"EQUIVALENT".equals(new ResearchFactSemanticCoverage(jdbc).relations(runId, cell)
                .get(ResearchFactSemanticCoverage.pair(ids.get(0), ids.get(1))))) return false;
        List<Support> supports = loadSupports(runId, cell, plan, entities).stream()
                .filter(support -> group.equals(support.group()) && ids.contains(support.factId())).toList();
        return ids.stream().allMatch(id -> !eligibleProof(supports, id).isEmpty());
    }

    private List<Map<String, Object>> factViews(String runId, String cell, int plan, int entities, boolean auditOnly) {
        Map<String, List<Support>> byFact = new LinkedHashMap<>();
        for (Support support : loadSupports(runId, cell, plan, entities))
            byFact.computeIfAbsent(support.factId(), ignored -> new ArrayList<>()).add(support);
        var semantics = new ResearchFactSemanticCoverage(jdbc);
        Map<String, String> groups = semantics.groups(byFact.keySet(),
                modernState(runId) ? semantics.relations(runId, cell) : Map.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : byFact.entrySet()) {
            List<Support> related = byFact.entrySet().stream()
                    .filter(other -> groups.get(entry.getKey()).equals(groups.get(other.getKey())))
                    .flatMap(other -> other.getValue().stream()).toList();
            List<Support> proof = eligibleProof(related, entry.getKey());
            // 审计必须看到该事实自己的原始引文，不能提前借用等价候选的证据。
            if (auditOnly) proof = entry.getValue();
            if (proof.isEmpty()) continue;
            Map<String, Map<String, String>> quotes = new LinkedHashMap<>();
            proof.forEach(support -> support.quotes().forEach(quote -> quotes.put(quote.get("evidence_key"), quote)));
            result.add(Map.of("fact_id", entry.getKey(), "cell_key", cell, "claim_text", entry.getValue().get(0).claim(),
                    "evidence_keys", List.copyOf(quotes.keySet()), "evidence_quotes", List.copyOf(quotes.values()),
                    "source_scopes", proof.stream().map(support -> mapper.convertValue(support.sourceScope(), Object.class)).toList()));
        }
        return result.stream().map(fact -> {
            Map<String, Object> item = new LinkedHashMap<>(fact);
            item.put("equivalence_group_id", groups.get(String.valueOf(fact.get("fact_id"))));
            return Map.copyOf(item);
        }).toList();
    }

    /** 成文对已审计的等价事实只写一次，同时保留所有来源和原事实身份。 */
    List<Map<String, Object>> synthesisFacts(String runId, String cell, int plan, int entities) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var fact : facts(runId, cell, plan, entities)) groups.computeIfAbsent(String.valueOf(fact.get("equivalence_group_id")), ignored -> new ArrayList<>()).add(fact);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var group : groups.values()) {
            Map<String, Object> item = new LinkedHashMap<>(group.get(0));
            Map<String, Object> quotes = new LinkedHashMap<>();
            List<Object> scopes = new ArrayList<>();
            for (var fact : group) {
                for (Object raw : (List<?>) fact.get("evidence_quotes")) {
                    var quote = (Map<?, ?>) raw; quotes.put(String.valueOf(quote.get("evidence_key")), quote);
                }
                scopes.addAll((List<?>) fact.get("source_scopes"));
            }
            item.put("equivalent_fact_ids", group.stream().map(fact -> fact.get("fact_id")).toList());
            item.put("evidence_keys", List.copyOf(quotes.keySet())); item.put("evidence_quotes", List.copyOf(quotes.values()));
            item.put("source_scopes", List.copyOf(scopes)); result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private List<Support> loadSupports(String runId, String cell, int plan, int entities) {
        List<Support> supports = new ArrayList<>();
        jdbc.query("""
                select fact.fact_id, fact.claim_text, support.evidence_keys_json, support.support_task_id,
                       support.quorum_group_key, support.candidate_quorum, support.candidate_slot,
                       support.source_identities_json, support.source_fingerprints_json, support.source_scope_json
                from research_requirement_fact fact join research_requirement_fact_support support on support.fact_id = fact.fact_id
                where fact.research_run_id = ? and fact.cell_key = ? and fact.plan_revision = ? and fact.entity_set_version = ?
                  and support.qualification_status = 'QUALIFIED'
                order by fact.fact_id, support.support_id
                """, rs -> {
            String id = rs.getString(1);
            List<String> keys = strings(read(rs.getString(3)));
            List<Map<String, String>> quotes = new ArrayList<>();
            for (String key : keys) {
                quotes.addAll(jdbc.query("""
                        select evidence.quote_text, evidence.source_title, evidence.source_url from source_evidence evidence
                        where evidence.research_run_id = ? and evidence.evidence_key = ?
                          and evidence.relation_type = 'SUPPORTS'
                          and exists (select 1 from research_evidence_validation validation where validation.evidence_id = evidence.id
                              and validation.final_status in ('QUALIFIED','QUALIFIED_UNBOUND'))
                          and not exists (select 1 from research_evidence_validation validation where validation.evidence_id = evidence.id
                              and validation.final_status = 'REJECTED')
                        """, (evidence, n) -> Map.of("evidence_key", key, "quote", evidence.getString(1),
                        "source_title", evidence.getString(2), "source_url", evidence.getString(3) == null ? "" : evidence.getString(3)), runId, key));
            }
            // 一组联合证据缺失时不能假装剩余引文仍支持整个事实。
            if (quotes.size() != keys.size()) return;
            supports.add(new Support(id, cell, rs.getString(2), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                    rs.getString(8) == null ? List.of() : strings(read(rs.getString(8))),
                    rs.getString(9) == null ? List.of() : strings(read(rs.getString(9))), List.copyOf(quotes),
                    rs.getString(10) == null ? mapper.createObjectNode() : read(rs.getString(10))));
        }, runId, cell, plan, entities);
        return List.copyOf(supports);
    }

    private boolean eligibleWithCurrent(ResearchAgentCompletionCommitter.TaskRow task, Fact fact) {
        if (task.candidateQuorum() == 1) return true;
        List<Support> supports = new ArrayList<>(loadSupports(task.runId(), fact.cell(), task.planRevision(), task.entitySetVersion()));
        supports.add(new Support(fact.id(), fact.cell(), fact.claim(), task.id(), task.quorumGroupKey(),
                task.candidateQuorum(), task.candidateSlot(), fact.identities(), fact.fingerprints(), List.of(), mapper.valueToTree(fact.sourceScope())));
        var semantics = new ResearchFactSemanticCoverage(jdbc);
        Map<String, String> groups = semantics.groups(supports.stream().map(Support::factId).distinct().toList(),
                modernState(task.runId()) ? semantics.relations(task.runId(), fact.cell()) : Map.of());
        return !eligibleProof(supports.stream().filter(item -> groups.get(fact.id()).equals(groups.get(item.factId()))).toList(), fact.id()).isEmpty();
    }

    private List<Support> eligibleProof(List<Support> supports, String factId) {
        supports = supports.stream().sorted(java.util.Comparator.comparingInt(this::sourceScopeRank)).toList();
        for (Support support : supports) if (support.quorum() == 1 && factId.equals(support.factId())) return List.of(support);
        var planner = new ResearchAgentCompletionMergePlanner();
        for (int left = 0; left < supports.size(); left++) for (int right = left + 1; right < supports.size(); right++) {
            Support a = supports.get(left), b = supports.get(right);
            if (!factId.equals(a.factId()) && !factId.equals(b.factId())) continue;
            if (a.quorum() != 2 || b.quorum() != 2 || a.group() == null || !a.group().equals(b.group())) continue;
            // 调用者已限制为直接审计过的等价组；仅给门禁使用共同值，保留原始结论与证据。
            if (!planner.quorumOutcome(List.of(quorumFact(a, factId), quorumFact(b, factId))).accepted().isEmpty()) return List.of(a, b);
        }
        return List.of();
    }

    private int sourceScopeRank(Support support) {
        JsonNode scope = support.sourceScope();
        if (scope.path("evidence").isEmpty()) return 1;
        int rank = 3;
        for (JsonNode evidence : scope.path("evidence")) {
            String temporal = evidence.path("temporal_status").asText();
            if ("DEVELOPMENT".equals(temporal) && scope.path("policy").path("allow_development").asBoolean()) return 0;
            if (!scope.path("policy").path("required_versions").isEmpty())
                rank = Math.min(rank, evidence.path("document_versions").isEmpty() ? 1 : 0);
            else rank = Math.min(rank, "CURRENT_ALIAS".equals(temporal) ? 0 : "VERSIONED_SOURCE".equals(temporal) ? 2 : 1);
        }
        return rank;
    }

    /** 使用原有双路门禁同时检查执行槽、域名、血缘和引文指纹。 */
    private ResearchAgentCompletionCommitter.QuorumCandidate quorumFact(Support support, String auditedValue) {
        return new ResearchAgentCompletionCommitter.QuorumCandidate(support.factId(), support.taskId(), null,
                support.factId(), support.cell(), 0, auditedValue, 0, support.identities(), List.of(), null,
                support.slot(), true, support.fingerprints());
    }

    private Set<String> satisfied(Set<String> requirementIds, Set<String> eligibleFacts, Map<String, Map<String, Object>> details) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (String id : requirementIds) {
            String run = jdbc.queryForObject("select research_run_id from research_requirement where requirement_id = ?", String.class, id);
            if (fullCompletion(run) && jdbc.queryForObject("select count(*) from research_report_feedback where requirement_id = ? and feedback_status <> 'RESOLVED'", Integer.class, id) > 0) continue;
            int minimum = jdbc.queryForObject("select minimum_count from research_requirement where requirement_id = ?", Integer.class, id);
            String scopeJson = jdbc.queryForObject("select scope_json from research_requirement where requirement_id = ?", String.class, id);
            JsonNode scope = scopeJson == null ? mapper.createObjectNode() : read(scopeJson);
            for (String json : jdbc.queryForList("select fact_ids_json from research_requirement_observation where requirement_id = ? and coverage_status = 'SATISFIED'", String.class, id)) {
                List<String> refs = strings(read(json));
                Set<String> eligible = new LinkedHashSet<>(refs); eligible.retainAll(eligibleFacts);
                if ("CURRENT".equals(scope.path("temporal_mode").asText()) && (scope.path("required_versions").isEmpty() || fullCompletion(run)))
                    eligible.removeIf(fact -> !canAnswerCurrent(fact, List.of(), details));
                if (!refs.isEmpty() && (minimum == 1 ? eligible.containsAll(refs) : certifiedFactCount(eligible) >= minimum)) satisfied.add(id);
            }
        }
        return satisfied;
    }

    private int distinctFactCount(Set<String> ids, java.util.Collection<Fact> current, Map<String, Map<String, Object>> historical) {
        Set<String> claims = new LinkedHashSet<>();
        for (String id : ids) {
            String claim = historical.containsKey(id) ? String.valueOf(historical.get(id).get("claim_text")) : "";
            for (Fact fact : current) if (id.equals(fact.id())) { claim = fact.claim(); break; }
            if (!claim.isBlank()) claims.add(Normalizer.normalize(claim, Normalizer.Form.NFKC)
                    .replaceAll("(?U)\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT));
        }
        return claims.size();
    }

    private boolean canAnswerCurrent(String id, java.util.Collection<Fact> current, Map<String, Map<String, Object>> historical) {
        List<JsonNode> scopes = new ArrayList<>();
        for (Fact fact : current) if (id.equals(fact.id()) && fact.reasons().isEmpty()) scopes.add(mapper.valueToTree(fact.sourceScope()));
        if (historical.containsKey(id)) mapper.valueToTree(historical.get(id).get("source_scopes")).forEach(scopes::add);
        if (scopes.isEmpty()) return true;
        for (JsonNode scope : scopes) {
            if (scope.path("evidence").isEmpty()) return true;
            for (JsonNode evidence : scope.path("evidence")) {
                String status = evidence.path("temporal_status").asText();
                if (evidence.has("semantic_check")) {
                    String time = evidence.path("semantic_check").path("time").asText();
                    // 官方文档大多不标发布日期：日期无法判断时，只接受不带版本号的当前页面
                    if ("APPLICABLE".equals(time) || "UNKNOWN".equals(time) && Set.of("CURRENT_ALIAS", "UNKNOWN").contains(status)) return true;
                } else if (!"VERSIONED_SOURCE".equals(status)) return true;
            }
        }
        return false;
    }

    List<String> evidenceKeys(String runId, String cell) {
        int[] scope = jdbc.query("select plan_revision, entity_set_version from research_cell where research_run_id = ? and cell_key = ?",
                rs -> rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : null, runId, cell);
        if (scope == null) return List.of();
        return facts(runId, cell, scope[0], scope[1]).stream().flatMap(fact -> ((List<?>) fact.get("evidence_keys")).stream())
                .map(String::valueOf).distinct().toList();
    }

    Map<String, Object> readModel(String runId) {
        if (!enabled(runId)) return Map.of("enabled", false, "requirements", List.of(), "facts", List.of());
        List<Map<String, Object>> requirements = jdbc.query("""
                select requirement.* from research_requirement requirement left join research_cell cell
                    on cell.research_run_id = requirement.research_run_id and cell.cell_key = requirement.cell_key
                    and cell.plan_revision = requirement.plan_revision and cell.entity_set_version = requirement.entity_set_version
                where requirement.research_run_id = ? and (cell.cell_key is not null or
                    (requirement.scope_type = 'RUN' and requirement.plan_revision =
                        (select max(active_cell.plan_revision) from research_cell active_cell where active_cell.research_run_id = ?)))
                order by requirement.cell_key, requirement.requirement_id
                """, (rs, n) -> requirementRow(rs), runId, runId);
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        Set<String> loadedCells = new LinkedHashSet<>();
        for (Map<String, Object> req : requirements) {
            if ("RUN".equals(req.get("scope_type"))) continue;
            if (!loadedCells.add(String.valueOf(req.get("cell_key")))) continue;
            for (Map<String, Object> fact : facts(runId, String.valueOf(req.get("cell_key")),
                    ((Number) req.get("plan_revision")).intValue(), ((Number) req.get("entity_set_version")).intValue()))
                facts.put(String.valueOf(fact.get("fact_id")), fact);
        }
        Set<String> ids = new LinkedHashSet<>();
        requirements.stream().filter(req -> !"RUN".equals(req.get("scope_type")))
                .forEach(req -> ids.add(String.valueOf(req.get("requirement_id"))));
        Set<String> complete = satisfied(ids, facts.keySet(), facts);
        List<Map<String, Object>> progress = new ArrayList<>();
        for (Map<String, Object> req : requirements) {
            Map<String, Object> item = new LinkedHashMap<>(req);
            String id = String.valueOf(req.get("requirement_id"));
            if (improvedCompletion(runId) && !"RUN".equals(req.get("scope_type"))) {
                Set<String> associated = new LinkedHashSet<>();
                for (String json : jdbc.queryForList("select fact_ids_json from research_requirement_observation where requirement_id = ?", String.class, id)) associated.addAll(strings(read(json)));
                associated.retainAll(facts.keySet()); item.put("supporting_fact_ids", List.copyOf(associated));
            }
            if ("RUN".equals(req.get("scope_type"))) {
                int actual = researchedEntityCount(runId, req, requirements, complete);
                item.put("actual_count", actual);
                ids.add(id);
                if (actual >= ((Number) req.get("minimum_count")).intValue()) complete.add(id);
            } else item.put("actual_count", observedFactCount(req, facts));
            item.put("status", complete.contains(id) ? "SATISFIED"
                    : ((Number) item.get("actual_count")).intValue() > 0 ? "PARTIAL" : "PENDING");
            item.put("reason_code", complete.contains(id) ? "QUALIFIED_FACTS_COVER_REQUIREMENT" : INCOMPLETE);
            progress.add(item);
        }
        List<Map<String, Object>> gains = jdbc.query("""
                select completion_id, new_fact_count, new_satisfied_requirement_count from research_requirement_progress
                where research_run_id = ? order by created_at, completion_id
                """, (rs, n) -> Map.<String, Object>of("completion_id", rs.getString(1), "new_fact_count", rs.getInt(2),
                "new_satisfied_requirement_count", rs.getInt(3)), runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true); result.put("shadow_mode",!enforces(runId)); result.put("requirements", progress); result.put("facts", List.copyOf(facts.values()));
        result.put("distinct_fact_count", facts.values().stream().map(fact -> fact.get("equivalence_group_id")).distinct().count());
        Set<String> mandatoryIds = requirements.stream().filter(req -> !Boolean.FALSE.equals(req.get("mandatory")))
                .map(req -> String.valueOf(req.get("requirement_id"))).collect(java.util.stream.Collectors.toSet());
        result.put("required_count", mandatoryIds.size()); result.put("satisfied_count", Math.toIntExact(mandatoryIds.stream().filter(complete::contains).count()));
        if (answerPolicy(runId)) {
            result.put(ResearchAnswerPolicy.FLAG, 1);
            result.put("optional_count", requirements.size() - mandatoryIds.size());
            result.put("optional_satisfied_count", complete.stream().filter(id -> !mandatoryIds.contains(id)).count());
        }
        result.put("missing_requirement_ids", ids.stream().filter(mandatoryIds::contains).filter(id -> !complete.contains(id)).toList());
        result.put("progress", gains); result.put("explicit_definitions_complete", explicitDefinitionsComplete(runId));
        for (String origin : List.of("EXPLICIT", "DERIVED")) {
            String prefix = origin.toLowerCase(java.util.Locale.ROOT);
            result.put(prefix + "_count", requirements.stream().filter(req -> origin.equals(req.get("origin"))).count());
            result.put(prefix + "_satisfied_count", requirements.stream().filter(req -> origin.equals(req.get("origin"))
                    && complete.contains(String.valueOf(req.get("requirement_id")))).count());
        }
        return Map.copyOf(result);
    }

    /** 只计算仍有合格证据的关联事实，部分观察不授予完成资格。 */
    private int observedFactCount(Map<String, Object> requirement, Map<String, Map<String, Object>> eligible) {
        String run = jdbc.queryForObject("select research_run_id from research_requirement where requirement_id = ?",String.class,requirement.get("requirement_id"));
        int maximum = 0;
        for (String json : jdbc.queryForList("select fact_ids_json from research_requirement_observation where requirement_id = ?",
                String.class, requirement.get("requirement_id"))) {
            Set<String> ids = new LinkedHashSet<>(strings(read(json)));
            ids.retainAll(eligible.keySet());
            var scope = mapper.valueToTree(requirement.get("scope"));
            if ("CURRENT".equals(scope.path("temporal_mode").asText()) && (scope.path("required_versions").isEmpty() || fullCompletion(run)))
                ids.removeIf(id -> !canAnswerCurrent(id, List.of(), eligible));
            maximum = Math.max(maximum, "DISTINCT_FACTS".equals(requirement.get("completion_mode"))
                    ? certifiedFactCount(ids) : distinctFactCount(ids, List.of(), eligible));
        }
        return maximum;
    }

    private int certifiedFactCount(Set<String> ids) {
        if (ids.isEmpty()) return 0;
        Map<String, String> claims = new LinkedHashMap<>();
        String[] scope = {null, null};
        for (String id : ids) jdbc.query("select research_run_id, cell_key, claim_text from research_requirement_fact where fact_id = ?", rs -> {
            scope[0] = rs.getString(1); scope[1] = rs.getString(2);
            claims.put(id, Normalizer.normalize(rs.getString(3), Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT));
        }, id);
        if (claims.size() != ids.size()) return 0;
        if (!modernState(scope[0])) return new LinkedHashSet<>(claims.values()).size();
        var semantics = new ResearchFactSemanticCoverage(jdbc);
        Map<String, String> known = semantics.relations(scope[0], scope[1]);
        claims.forEach((left, text) -> claims.forEach((right, other) -> {
            if (text.equals(other)) known.put(ResearchFactSemanticCoverage.pair(left, right), "EQUIVALENT");
        }));
        return semantics.certifiedCount(ids, known);
    }

    void reconcileAuditedCoverage(String runId) {
        if (!enabled(runId)) return;
        invalidateSynthesis(runId);
        for (Object raw : (List<?>) readModel(runId).get("requirements")) {
            Map<?, ?> requirement = (Map<?, ?>) raw;
            if (Boolean.FALSE.equals(requirement.get("mandatory")) || "RUN".equals(requirement.get("scope_type")) || "SATISFIED".equals(requirement.get("status"))) continue;
            jdbc.update("""
                    update research_cell set cell_status = 'GAP', last_verifier_decision = 'REQUIREMENT_FACT_DEDUPLICATED', updated_at = current_timestamp
                    where research_run_id = ? and cell_key = ? and cell_status = 'VERIFIED' and semantic_audit_status = 'PASS' and active_task_id is null
                    """, runId, requirement.get("cell_key"));
        }
    }

    private void invalidateSynthesis(String runId) {
        jdbc.update("update research_agent_synthesis_candidate set status = 'STALE' where research_run_id = ? and status = 'VALIDATED'", runId);
    }

    private Map<String, Object> requirementRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String name : List.of("requirement_id", "cell_key", "entity_label", "origin", "as_of_date", "completion_mode", "scope_type"))
            row.put(name, rs.getString(name));
        row.put("text", rs.getString("requirement_text")); row.put("mandatory", rs.getBoolean("mandatory"));
        row.put("plan_revision", rs.getInt("plan_revision")); row.put("entity_set_version", rs.getInt("entity_set_version"));
        row.put("minimum_count", rs.getInt("minimum_count"));
        row.put("question_quote", rs.getString("question_quote") == null ? "" : rs.getString("question_quote"));
        row.put("scope", rs.getString("scope_json") == null ? Map.of() : mapper.convertValue(read(rs.getString("scope_json")), Object.class));
        if (fullCompletion(rs.getString("research_run_id"))) {
            var revisions = jdbc.query("select lineage_key,predecessor_requirement_id,revision_reason from research_requirement_revision where requirement_id = ?",
                    (revision,n) -> Map.<String,Object>of("lineage_key",revision.getString(1),"predecessor_requirement_id",revision.getString(2) == null ? "" : revision.getString(2),"revision_reason",revision.getString(3)),rs.getString("requirement_id"));
            if (!revisions.isEmpty()) row.putAll(revisions.get(0));
        }
        return Map.copyOf(row);
    }

    private boolean explicitDefinitionsComplete(String runId) {
        if (!definitionsRequired(runId)) return true;
        List<String> plans = jdbc.queryForList("""
                select plan_json from research_matrix_plan where research_run_id = ? and plan_mode = 'LLM_MATRIX' and plan_status = 'ACTIVE'
                order by created_at desc, id desc limit 1
                """, String.class, runId);
        if (plans.isEmpty() || read(plans.get(0)).path("brief").path("requirement_definitions").isEmpty()) return false;
        return !fullCompletion(runId) || read(plans.get(0)).path("brief").path("planning_completeness").path("complete").asBoolean();
    }

    private int researchedEntityCount(String runId, Map<String, Object> requirement,
                                      List<Map<String, Object>> allRequirements, Set<String> completed) {
        JsonNode scope = mapper.valueToTree(requirement.get("scope"));
        List<String> bound = strings(scope.path("entity_keys"));
        Set<String> entities = new LinkedHashSet<>();
        jdbc.query("""
                select research_entity.row_key, cell.cell_key, cell.evidence_refs_json from research_row research_entity
                join research_formal_eligible_cell cell on cell.research_row_id = research_entity.id
                where cell.research_run_id = ? and cell.column_key = ? and cell.cell_status = 'VERIFIED' and cell.plan_revision = ?
                """, rs -> {
            String row = rs.getString(1), cell = rs.getString(2);
            if ((!bound.isEmpty() && !bound.contains(row)) || rs.getString(3) == null || strings(read(rs.getString(3))).isEmpty()) return;
            boolean covered = allRequirements.stream().filter(req -> !Boolean.FALSE.equals(req.get("mandatory")) && cell.equals(req.get("cell_key")))
                    .allMatch(req -> completed.contains(String.valueOf(req.get("requirement_id"))));
            if (covered) entities.add(row);
        }, runId, scope.path("column_key").asText(), requirement.get("plan_revision"));
        return entities.size();
    }

    ResearchAgentRunCompletionGate.TableState applyCoverage(ResearchAgentRunCompletionGate.TableState state) {
        if (!enforces(state.runId())) return state;
        Map<String, Object> view = readModel(state.runId());
        Set<String> incomplete = new LinkedHashSet<>();
        List<ResearchAgentRunCompletionGate.CellState> global = new ArrayList<>();
        for (Object raw : (List<?>) view.get("requirements")) {
            Map<?, ?> req = (Map<?, ?>) raw;
            if (Boolean.FALSE.equals(req.get("mandatory"))) continue;
            if (!"SATISFIED".equals(req.get("status"))) {
                incomplete.add(String.valueOf(req.get("cell_key")));
                if ("RUN".equals(req.get("scope_type"))) global.add(new ResearchAgentRunCompletionGate.CellState(
                        "requirement:" + req.get("requirement_id"), "@requirements", INCOMPLETE, String.valueOf(req.get("text")), List.of()));
            }
        }
        if (!Boolean.TRUE.equals(view.get("explicit_definitions_complete"))) global.add(new ResearchAgentRunCompletionGate.CellState(
                "requirement:planning", "@requirements", INCOMPLETE, "用户明确要求尚未完成解析", List.of()));
        List<ResearchAgentRunCompletionGate.CellState> cells = new ArrayList<>(state.cells().stream().map(cell ->
                incomplete.contains(cell.cellKey()) && cell.resolved()
                        ? new ResearchAgentRunCompletionGate.CellState(cell.cellKey(), cell.columnKey(),
                        INCOMPLETE, cell.candidateValue(), cell.evidenceKeys()) : cell).toList());
        cells.addAll(global);
        Set<String> required = state.requiredColumnKeys() == null ? null : new LinkedHashSet<>(state.requiredColumnKeys());
        if (answerPolicy(state.runId())) {
            Set<String> mandatoryColumns = new LinkedHashSet<>();
            for (Object raw : (List<?>) view.get("requirements")) {
                Map<?,?> req = (Map<?,?>)raw;
                if (!Boolean.FALSE.equals(req.get("mandatory")) && !"RUN".equals(req.get("scope_type")))
                    mandatoryColumns.add(String.valueOf(req.get("cell_key")).split(":",2)[1]);
            }
            if (required == null) required = mandatoryColumns; else required.retainAll(mandatoryColumns);
        }
        if (required != null && !global.isEmpty()) required.add("@requirements");
        return new ResearchAgentRunCompletionGate.TableState(state.runId(), cells, required);
    }

    static String key(String prefix, Object... values) {
        try {
            String canonical = new ObjectMapper().writeValueAsString(java.util.Arrays.stream(values)
                    .map(value -> Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFC)).toList());
            return prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 48);
        } catch (Exception error) { throw new IllegalStateException("无法计算需求或事实标识", error); }
    }

    private JsonNode read(String value) {
        try { JsonNode node = mapper.readTree(value); return node.isTextual() ? mapper.readTree(node.asText()) : node; }
        catch (Exception error) { throw invalid("需求状态 JSON 不符合约定"); }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node != null && node.isObject()) node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return result;
    }

    private static BusinessException invalid(String message) { return new BusinessException("RESEARCH_REQUIREMENT_COVERAGE_INVALID", message); }
    static ReferenceSelection selectReferences(List<FactReference> references, String cell) {
        List<FactReference> scoped = references.stream().filter(ref -> ref.cell().equals(cell)).toList();
        if (scoped.isEmpty()) throw invalid("覆盖证据属于其他对象或列");
        return new ReferenceSelection(scoped.stream().filter(FactReference::qualified).map(FactReference::id).distinct().sorted().toList(),
                scoped.stream().anyMatch(ref -> !ref.qualified()));
    }
    record ReferenceSelection(List<String> ids, boolean partial) { }
    record Fact(String id, String cell, String claim, List<String> keys, String clusterKey, List<String> reasons,
                List<String> identities, List<String> fingerprints, Map<String, Object> sourceScope) {
        Fact(String id, String cell, String claim, List<String> keys, String clusterKey, List<String> reasons,
             List<String> identities, List<String> fingerprints) { this(id, cell, claim, keys, clusterKey, reasons, identities, fingerprints, Map.of()); }
    }
    record Support(String factId, String cell, String claim, String taskId, String group, int quorum, int slot,
                   List<String> identities, List<String> fingerprints, List<Map<String, String>> quotes, JsonNode sourceScope) { }
    record FactReference(String id, String cell, boolean qualified) { }
    record Observation(String requirementId, List<String> factIds, boolean complete) { }
    record Assessment(List<Fact> facts, List<Observation> observations, Set<String> blockedCandidates,
                      int newFactCount, int newRequirementCount, boolean active) {
        static Assessment empty() { return new Assessment(List.of(), List.of(), Set.of(), 0, 0, false); }
    }
}
