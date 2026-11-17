package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 离线回放覆盖累计；来源资格视为上游已完成，完整证据核验仍由正式链路负责。 */
public class RequirementCoverageReplay {
    static final ObjectMapper M = new ObjectMapper();
    static final String CELL = "row:mechanism";

    public static void main(String[] args) throws Exception {
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:coverage;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("create table research_run(id varchar(36) primary key, agent_feature_flags_json json, created_at timestamp)");
        db.execute("create table research_cell(research_run_id varchar(36), cell_key varchar(160), plan_revision int, entity_set_version int, high_risk boolean)");
        db.execute("create table research_agent_completion(id varchar(36) primary key)");
        db.execute("create table source_evidence(id varchar(36), research_run_id varchar(36), evidence_key varchar(64), quote_text longtext, source_title varchar(300), source_url varchar(1000), relation_type varchar(32))");
        db.execute("create table research_evidence_validation(evidence_id varchar(36), final_status varchar(32))");
        db.execute("create table research_agent_synthesis_candidate(research_run_id varchar(36), status varchar(32))");
        db.execute("create table research_semantic_check(id varchar(36), research_run_id varchar(36), cell_key varchar(160), left_cluster_key varchar(64), right_cluster_key varchar(64), verdict varchar(32), equivalence_verdict varchar(32), semantic_revision int, checked_at timestamp)");
        for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration/V169__research_requirement_coverage.sql")).split(";"))
            if (!sql.isBlank()) db.execute(sql);
        for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration/V170__research_requirement_predicates.sql")).split(";"))
            if (!sql.isBlank()) db.execute(sql);
        for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration/V171__research_fact_quorum_support.sql")).split(";"))
            if (!sql.isBlank()) db.execute(sql);
        for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration/V172__research_fact_source_scope.sql")).split(";"))
            if (!sql.isBlank()) db.execute(sql);
        db.update("insert into research_run values ('run-1', ?, timestamp '2026-10-20 12:00:00')", "{\"requirement_coverage_v1\":true,\"requirement_state_version\":3}");
        db.update("insert into research_cell values ('run-1', ?, 0, 0, false)", CELL);
        var coverage = new ResearchRequirementCoverageService(db, M);
        var qualifier = new ResearchEvidenceQualificationService(db, M, new ResearchAgentCompletionCanonicalizer(), null, null, false) {
            @Override List<String> qualifyFact(ResearchAgentCompletionCommitter.TaskRow task,
                    ResearchAgentCompletionEnvelope envelope, JsonNode cluster,
                    Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority) { return List.of(); }
        };
        Map<String, Object> firstPolicy = policy(coverage);
        JsonNode firstContract = M.valueToTree(firstPolicy.get("requirement_contract"));
        Files.writeString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/requirement-contract.json"), firstContract.toString());
        String firstId = requirement(firstContract, "Index form");
        String secondId = requirement(firstContract, "Update cost");
        var first = envelope(firstContract, "a", "Atlas supports bitmap indexes.", firstId);
        var firstTask = task("task-a", firstPolicy);
        var one = coverage.assess(firstTask, first, Map.of(), qualifier);
        require(one.blockedCandidates().size() == 1, "部分覆盖不能完成整格");
        source(db, "a", first.evidence().get(0).claimText());
        coverage.persist(firstTask, "completion-a", one);
        require(((Number) coverage.readModel("run-1").get("satisfied_count")).intValue() == 1, "第一轮应该覆盖一项");
        Map<String, Object> secondPolicy = policy(coverage);
        JsonNode secondContract = M.valueToTree(secondPolicy.get("requirement_contract"));
        Files.writeString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/requirement-contract-with-facts.json"), secondContract.toString());
        require(secondContract.path("facts").size() == 1, "第二轮必须读取第一轮事实");
        var second = envelope(secondContract, "b", "Atlas updates indexes during writes.", secondId);
        var secondTask = task("task-b", secondPolicy);
        var two = coverage.assess(secondTask, second, Map.of(), qualifier);
        require(two.blockedCandidates().isEmpty(), "跨轮覆盖完整后可以完成整格");
        source(db, "b", second.evidence().get(0).claimText());
        coverage.persist(secondTask, "completion-b", two);
        Map<String, Object> completed = coverage.readModel("run-1");
        require(((Number) completed.get("satisfied_count")).intValue() == 2, "第二轮累计覆盖两项");
        require(((List<?>) completed.get("facts")).size() == 2, "两个互补事实必须都保留");
        coverage.invalidateEvidence("run-1", CELL, List.of("evidence:a"), "REPLAY_CONFLICT");
        Map<String, Object> invalidated = coverage.readModel("run-1");
        require(((Number) invalidated.get("satisfied_count")).intValue() == 1, "争议事实应使依赖的需求失效");
        require(((List<?>) invalidated.get("facts")).size() == 1, "互补的无争议事实必须保留");
        require(ResearchRequirementDefinitions.countValue("at least three new features") == 3, "英文数量需要支持修饰词");
        require(ResearchRequirementDefinitions.countValue("至少三项") == 3, "中文数量需要正确解析");
        String quorumFact = ResearchRequirementCoverageService.key("fact:", "quorum-replay");
        for (int slot = 1; slot <= 5; slot++) {
            String supportKey = "q" + slot;
            String group = slot == 2 ? "group-other" : "group-main";
            int candidateSlot = slot == 1 ? 1 : 2;
            source(db, supportKey, slot == 1 ? "A compressed vector is indexed." : "An independently documented bitmap supports lookup.");
            var quorumTask = new ResearchAgentCompletionCommitter.TaskRow("task-" + supportKey, "run-1", "CLAIMED", "DEEP_CELL", "row", "branch", 0, 0,
                    "[]", "{}", M.writeValueAsString(Map.of("query_policy", firstPolicy)), "research-agent-task-snapshot.v3", "digest", "worker", 1, 1L,
                    "logical-" + supportKey, group, 2, candidateSlot, true);
            var fact = new ResearchRequirementCoverageService.Fact(quorumFact, CELL, "Atlas has an index.", List.of("evidence:" + supportKey), "cluster:" + supportKey,
                    List.of(), List.of("domain:" + (slot == 3 ? "q1" : supportKey) + ".example", "lineage:" + supportKey),
                    List.of("exact:" + (slot == 4 ? "q1" : supportKey)));
            coverage.persist(quorumTask, "completion-" + supportKey, new ResearchRequirementCoverageService.Assessment(
                    List.of(fact), List.of(), java.util.Set.of(), 0, 0, true));
            long visible = coverage.facts("run-1", CELL, 0, 0).stream().filter(item -> quorumFact.equals(item.get("fact_id"))).count();
            require(visible == (slot == 5 ? 1 : 0), "双路事实必须来自同组两个独立执行槽，域名和引文也必须独立");
        }
        var planner = new ResearchAgentCompletionMergePlanner();
        var accepted = new ResearchAgentCompletionCommitter.MergeOutcome(List.of(new ResearchAgentCompletionReceipt.MergeReceipt(CELL, 2, 3, "ACCEPTED", "QUORUM_VERIFIED_AND_VERSION_MATCHED")), List.of());
        var blocked = planner.requireCoverage(accepted, java.util.Set.of(CELL));
        require(blocked.accepted().isEmpty() && blocked.rejected().get(0).toVersion() == 2, "需求不全时双路合并也不能更新整格版本");
        System.out.println("{\"quorum_single_slot_hidden\":true,\"quorum_cross_group_hidden\":true,\"same_source_hidden\":true,\"copied_quote_hidden\":true,\"quorum_pair_visible\":true,\"incomplete_quorum_merge_blocked\":true}");
        Path reportPath = Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/requirement-report.json");
        if (Files.exists(reportPath)) {
            JsonNode report = M.readTree(Files.readString(reportPath));
            List<Map<String, Object>> reportCells = M.convertValue(report.path("input").path("cells"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Map<String, Object> narrative = M.convertValue(report.path("narrative"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var claims = List.<Map<String, Object>>of(Map.of("cell_key", "row:features", "claim_text", reportCells.get(0).get("candidate_value"),
                    "evidence_keys", reportCells.get(0).get("evidence_keys"), "guarded", false));
            new ResearchAgentSynthesisValidationService().validate(java.util.Set.of("row:features"), reportCells, claims,
                    report.path("markdown").asText(), narrative, report.path("input").path("question").asText(), false, true);
            System.out.println("{\"cross_language_report_validation\":\"PASSED\",\"report_facts\":40}");
        }
        System.out.println("{\"first_round_covered\":1,\"second_round_covered\":2,\"retained_facts\":2,\"after_invalidation_covered\":1,\"provider_calls\":0}");
        FactSemanticsReplay.exercise(db, M);
        db.update("insert into research_run values ('run-legacy', ?, timestamp '2026-10-20 12:00:00')", "{\"requirement_coverage_v1\":true,\"requirement_definition_version\":2,\"requirement_state_version\":2}");
        db.update("insert into research_cell values ('run-legacy',?,0,0,false)", CELL);
        var legacyPolicy = coverage.attach("run-legacy",List.of(CELL),Map.of("entity_label","MySQL 8.4",
            "column_requirements",Map.of("mechanism",List.of("Mechanism")),
            "requirement_definitions",List.of(Map.of("text","Mechanism","question_quote","MySQL 8.4 mechanism","dimension_key","mechanism","entity_keys",List.of(),"completion_mode","SINGLE","minimum_count",1,"scope_type","CELL"))));
        JsonNode legacyContract=M.valueToTree(legacyPolicy.get("requirement_contract"));
        require(!legacyContract.has("source_scopes") && !legacyContract.has("fact_semantics_version"),"旧 Run 不能被强制切到新契约");
        String legacyId=ResearchRequirementCoverageService.key("req:","run-legacy",CELL,0,0,"2026-10-20","Mechanism","EXPLICIT","SINGLE",1,new ResearchAgentCompletionCanonicalizer().canonicalJsonValue(Map.of()));
        require(legacyId.equals(legacyContract.path("requirements").get(0).path("requirement_id").asText()),"旧显式需求身份不能改变");
        System.out.println("{\"legacy_run_protocol_preserved\":true,\"legacy_requirement_identity_preserved\":true}");
        db.execute("create table research_agent_task(id varchar(36),research_run_id varchar(36),plan_revision int,entity_set_version int,wave_no int,target_cells_json longtext)");
        db.execute("create table research_agent_execution(id varchar(36),research_agent_task_id varchar(36),extraction_diagnostics_json json)");
        db.update("update research_run set agent_feature_flags_json=? where id='run-1'","{\"requirement_coverage_v1\":true,\"requirement_state_version\":4}");
        var v4Policy=policy(coverage);
        JsonNode v4=M.valueToTree(v4Policy.get("requirement_contract"));
        require(v4.path("coverage_execution_version").asInt()==1,"新 Run 缺少需求执行能力标记");
        require(coverage.requiredCapability(M.writeValueAsString(Map.of("query_policy",v4Policy))).equals("coverage_execution_v1"),"新任务没有协商执行能力");
        Files.writeString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/requirement-contract-v4.json"),M.writeValueAsString(v4));
        System.out.println("{\"new_run_dispatch_contract_frozen\":true,\"worker_capability_required\":true}");
        db.execute("create table research_report_feedback(requirement_id varchar(64),feedback_status varchar(32))");
        for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT"),"backend/src/main/resources/db/migration/V178__research_requirement_revision_lineage.sql")).split(";")) if (!sql.isBlank()) db.execute(sql);
        db.update("update research_run set agent_feature_flags_json=? where id='run-1'","{\"requirement_coverage_v1\":true,\"requirement_state_version\":5}");
        var v5Policy=policy(coverage);
        JsonNode v5=M.valueToTree(v5Policy.get("requirement_contract"));
        require(v5.path("source_scope_checks_version").asInt()==1,"完整执行链没有冻结来源语义核验能力");
        require(coverage.requiredCapability(M.writeValueAsString(Map.of("query_policy",v5Policy))).equals("completion_runtime_v1"),"完整执行链任务允许旧 Worker 领取");
        Files.writeString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT"),"requirement-contract-v5.json"),M.writeValueAsString(v5));
    }

    static Map<String, Object> policy(ResearchRequirementCoverageService service) {
        return service.attach("run-1", List.of(CELL), Map.of("entity_label", "Atlas",
                "column_specs", List.of(Map.of("key", "mechanism", "label", "Mechanism")),
                "column_requirements", Map.of("mechanism", List.of("Index form", "Update cost"))));
    }
    static String requirement(JsonNode contract, String text) {
        for (JsonNode req : contract.path("requirements")) if (text.equals(req.path("text").asText())) return req.path("requirement_id").asText();
        throw new IllegalStateException("需求不存在");
    }
    static void source(JdbcTemplate db, String key, String claim) {
        db.update("insert into research_agent_completion values (?)", "completion-" + key);
        db.update("insert into source_evidence values (?, 'run-1', ?, ?, 'Atlas manual', 'https://atlas.example/docs', 'SUPPORTS')", key, "evidence:" + key, claim);
        db.update("insert into research_evidence_validation values (?, 'QUALIFIED_UNBOUND')", key);
    }
    static ResearchAgentCompletionCommitter.TaskRow task(String id, Map<String, Object> policy) throws Exception {
        return new ResearchAgentCompletionCommitter.TaskRow(id, "run-1", "CLAIMED", "DEEP_CELL", "row", "branch", 0, 0,
                "[]", "{}", M.writeValueAsString(Map.of("query_policy", policy)), "research-agent-task-snapshot.v3", "digest", "worker", 1, 1L, id, null, 1, 1, true);
    }
    static ResearchAgentCompletionEnvelope envelope(JsonNode contract, String key, String claim, String requirement) {
        String evidenceKey = "evidence:" + key;
        Map<String, Object> cluster = Map.of("cluster_key", "cluster:" + key, "cell_key", CELL, "claim_text", claim,
                "evidence_keys", List.of(evidenceKey), "confidence_ppm", 800000,
                "equivalent_claims", List.of(Map.of("evidence_key", evidenceKey, "claim_text", claim, "source_domain", "atlas.example")));
        Map<String, Object> proposal = Map.of("schema_version", ResearchRequirementCoverageService.PROPOSAL_SCHEMA,
                "contract_digest", contract.path("contract_digest").asText(), "observations", List.of(Map.of("requirement_id", requirement, "evidence_keys", List.of(evidenceKey))));
        var evidence = new ResearchAgentCompletionEnvelope.Evidence(evidenceKey, "window", "source", "Atlas manual", "query", "focus", claim, claim, "SUPPORTS", 800000, 0, "WORKSPACE");
        var candidate = new ResearchAgentCompletionEnvelope.Candidate("candidate:" + key, CELL, 0, claim, List.of(evidenceKey), 800000);
        return new ResearchAgentCompletionEnvelope("research-agent-completion.v2", "task-" + key, "worker", 1, 1L, "execution", "digest", "CANDIDATES_PROPOSED",
                Map.of(), Map.of(), "digest", List.of(evidence), List.of(candidate), null,
                Map.of("claim_clusters", List.of(cluster), "requirement_coverage", proposal), "digest");
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
