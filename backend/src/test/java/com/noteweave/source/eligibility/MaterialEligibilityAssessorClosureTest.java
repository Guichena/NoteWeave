package com.noteweave.source.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * FA-01 定向验证：生成来源依赖闭包的回路检测、深度与预算限制、结构化解析结果。
 * 真实 H2 schema 执行真实 SQL；覆盖自引、双向环、共享叶子 DAG、损坏范围 JSON、
 * 合法空范围、未知生成类型、外部证据、跨工作台与传递撤销。
 */
class MaterialEligibilityAssessorClosureTest {

    private final JdbcTemplate jdbc = jdbcTemplate();
    private final MaterialEligibilityAssessor assessor =
            new MaterialEligibilityAssessor(jdbc, new ObjectMapper());

    private static final String WS = "ws-1";
    private static final String OTHER_WS = "ws-2";

    // === 回路与深度 ===

    @org.junit.jupiter.api.Test
    void selfReferenceIsCycleNotStackOverflow() {
        String run = insertRun(WS, "[\"src-self\"]");
        insertGeneratedSource(WS, "src-self", run);
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-self")), AccessPurpose.CURRENT_EVIDENCE);
        EligibilityDecision decision = decisions.get("source:src-self");
        org.junit.jupiter.api.Assertions.assertFalse(decision.evidenceEligible());
        org.junit.jupiter.api.Assertions.assertTrue(decision.dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("DEPENDENCY_CYCLE")));
    }

    @org.junit.jupiter.api.Test
    void mutualCycleIsDetected() {
        String runA = insertRun(WS, "[\"src-b\"]");
        String runB = insertRun(WS, "[\"src-a\"]");
        insertGeneratedSource(WS, "src-a", runA);
        insertGeneratedSource(WS, "src-b", runB);
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-a")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-a").evidenceEligible());
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-a").dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("DEPENDENCY_CYCLE")));
    }

    @org.junit.jupiter.api.Test
    void sharedLeafDagIsNotACycle() {
        String leafRun = insertRun(WS, "[]");
        insertGeneratedSource(WS, "leaf", leafRun);
        String runA = insertRun(WS, "[\"leaf\",\"plain\"]");
        String runB = insertRun(WS, "[\"leaf\"]");
        insertGeneratedSource(WS, "src-a", runA);
        insertGeneratedSource(WS, "src-b", runB);
        insertPlainSource(WS, "plain");
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS,
                List.of(OriginRef.internalSource("src-a"), OriginRef.internalSource("src-b")),
                AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-a").evidenceEligible(),
                String.join(";", decisions.get("source:src-a").dependencySummary()));
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-b").evidenceEligible(),
                String.join(";", decisions.get("source:src-b").dependencySummary()));
    }

    @org.junit.jupiter.api.Test
    void chainBeyondMaxDepthFailsExplicitly() {
        // 8 层嵌套生成来源在深度内通过；第 9 层返回 DEPENDENCY_DEPTH_EXCEEDED
        String tailRun = insertRun(WS, "[\"plain\"]");
        insertGeneratedSource(WS, "gen-8", tailRun);
        insertPlainSource(WS, "plain");
        String previous = "gen-8";
        for (int depth = 7; depth >= 1; depth--) {
            String run = insertRun(WS, "[\"" + previous + "\"]");
            insertGeneratedSource(WS, "gen-" + depth, run);
            previous = "gen-" + depth;
        }
        Map<String, EligibilityDecision> inBound = assessor.assess(
                WS, List.of(OriginRef.internalSource("gen-7")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(inBound.get("source:gen-7").evidenceEligible(),
                String.join(";", inBound.get("source:gen-7").dependencySummary()));

        String overflowRun = insertRun(WS, "[\"gen-1\"]");
        insertGeneratedSource(WS, "gen-0", overflowRun);
        Map<String, EligibilityDecision> beyond = assessor.assess(
                WS, List.of(OriginRef.internalSource("gen-0")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(beyond.get("source:gen-0").evidenceEligible());
        org.junit.jupiter.api.Assertions.assertTrue(beyond.get("source:gen-0").dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("DEPENDENCY_DEPTH_EXCEEDED")));
    }

    // === 结构化解析结果 ===

    @org.junit.jupiter.api.Test
    void corruptScopeJsonIsUnresolvedNotEmpty() {
        String run = insertRun(WS, "{not-json");
        insertGeneratedSource(WS, "src-corrupt", run);
        GeneratedDependencyAssessment assessment = assessor.assessGeneratedDependenciesDetailed(
                WS, "research_agent", run, AccessPurpose.REPUBLISH);
        org.junit.jupiter.api.Assertions.assertFalse(assessment.resolved());
        org.junit.jupiter.api.Assertions.assertTrue(assessment.blockingReasons().stream()
                .anyMatch(reason -> reason.endsWith("SCOPE_PARSE_FAILED")));
        // 闭包评估同样阻断，不再把解析失败当作没有依赖
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-corrupt")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-corrupt").evidenceEligible());
    }

    @org.junit.jupiter.api.Test
    void legalEmptyScopeResolves() {
        String run = insertRun(WS, "[]");
        insertGeneratedSource(WS, "src-empty", run);
        GeneratedDependencyAssessment assessment = assessor.assessGeneratedDependenciesDetailed(
                WS, "research_agent", run, AccessPurpose.REPUBLISH);
        org.junit.jupiter.api.Assertions.assertTrue(assessment.resolved());
        org.junit.jupiter.api.Assertions.assertTrue(assessment.dependencies().isEmpty());
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-empty")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-empty").evidenceEligible());
    }

    @org.junit.jupiter.api.Test
    void missingRunIsUnresolved() {
        GeneratedDependencyAssessment assessment = assessor.assessGeneratedDependenciesDetailed(
                WS, "research_agent", "run-none", AccessPurpose.REPUBLISH);
        org.junit.jupiter.api.Assertions.assertFalse(assessment.resolved());
        org.junit.jupiter.api.Assertions.assertTrue(assessment.blockingReasons().stream()
                .anyMatch(reason -> reason.endsWith("RUN_UNRESOLVED")));
    }

    @org.junit.jupiter.api.Test
    void runInOtherWorkspaceIsUnresolved() {
        String run = insertRun(OTHER_WS, "[]");
        GeneratedDependencyAssessment assessment = assessor.assessGeneratedDependenciesDetailed(
                WS, "research_agent", run, AccessPurpose.REPUBLISH);
        org.junit.jupiter.api.Assertions.assertFalse(assessment.resolved());
    }

    @org.junit.jupiter.api.Test
    void unknownGeneratedKindIsExplicitlyUnknown() {
        insertSource(WS, "src-mystery", null, null, "mystery_agent", "ref-1");
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-mystery")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-mystery").evidenceEligible());
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-mystery").dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("DEPENDENCIES_UNKNOWN")));
    }

    // === 依赖语义 ===

    @org.junit.jupiter.api.Test
    void externalEvidenceWithoutSourceRowPassesLooseCitationOnly() {
        String run = insertRun(WS, "[]");
        insertEvidenceCitation(run, "web-external-1");
        insertGeneratedSource(WS, "src-ext", run);
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-ext")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-ext").evidenceEligible(),
                String.join(";", decisions.get("source:src-ext").dependencySummary()));
    }

    @org.junit.jupiter.api.Test
    void externalPrefixDoesNotCoverStrictScopeOrForeignRow() {
        // 声明范围里的外部前缀 ID 缺行：严格依赖不放行
        String run = insertRun(WS, "[\"web-strict-1\"]");
        insertGeneratedSource(WS, "src-strict", run);
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-strict")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-strict").evidenceEligible());

        // 跨工作台的真实来源行不被前缀覆盖
        insertPlainSource(OTHER_WS, "web-real-row");
        String looseRun = insertRun(WS, "[]");
        insertEvidenceCitation(looseRun, "web-real-row");
        insertGeneratedSource(WS, "src-loose", looseRun);
        Map<String, EligibilityDecision> loose = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-loose")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(loose.get("source:src-loose").evidenceEligible());
        org.junit.jupiter.api.Assertions.assertTrue(loose.get("source:src-loose").dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("FOREIGN_WORKSPACE")));
    }

    @org.junit.jupiter.api.Test
    void deletedAndRevokedDependenciesBlockWithDistinctStatus() {
        insertSource(WS, "dep-user", "DELETED", "USER", null, null);
        insertSource(WS, "dep-compliance", "DELETED", "COMPLIANCE", null, null);
        String runUser = insertRun(WS, "[\"dep-user\"]");
        insertGeneratedSource(WS, "src-user-dep", runUser);
        String runCompliance = insertRun(WS, "[\"dep-compliance\"]");
        insertGeneratedSource(WS, "src-compliance-dep", runCompliance);

        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS,
                List.of(OriginRef.internalSource("src-user-dep"),
                        OriginRef.internalSource("src-compliance-dep")),
                AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-user-dep").dependencySummary()
                .stream().anyMatch(failure -> failure.endsWith("USER_DELETED")));
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-compliance-dep").dependencySummary()
                .stream().anyMatch(failure -> failure.endsWith("COMPLIANCE_REVOKED")));
    }

    @org.junit.jupiter.api.Test
    void temporarilyUnavailableLeafKeepsHistoryButBlocksEvidence() {
        insertSource(WS, "dep-processing", "PROCESSING", null, null, null);
        String run = insertRun(WS, "[\"dep-processing\"]");
        insertGeneratedSource(WS, "src-history", run);
        Map<String, EligibilityDecision> history = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-history")), AccessPurpose.HISTORY_DISPLAY);
        org.junit.jupiter.api.Assertions.assertTrue(history.get("source:src-history").historyReadable(),
                String.join(";", history.get("source:src-history").dependencySummary()));
        Map<String, EligibilityDecision> evidence = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-history")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(evidence.get("source:src-history").evidenceEligible());
    }

    @org.junit.jupiter.api.Test
    void transitiveComplianceRevocationBlocksHistoryBody() {
        // 根报告依赖另一个生成来源，其依赖被 COMPLIANCE 撤销：HISTORY 用途正文也阻断
        insertSource(WS, "dep-compliance", "DELETED", "COMPLIANCE", null, null);
        String nestedRun = insertRun(WS, "[\"dep-compliance\"]");
        insertGeneratedSource(WS, "nested-report", nestedRun);
        String rootRun = insertRun(WS, "[\"nested-report\"]");
        insertGeneratedSource(WS, "root-report", rootRun);

        Map<String, EligibilityDecision> history = assessor.assess(
                WS, List.of(OriginRef.internalSource("root-report")), AccessPurpose.HISTORY_DISPLAY);
        org.junit.jupiter.api.Assertions.assertFalse(history.get("source:root-report").historyReadable(),
                String.join(";", history.get("source:root-report").dependencySummary()));
    }

    @org.junit.jupiter.api.Test
    void historyHardFailureIsPurposeIndependent() {
        // NR04：知识版本可读性的批量评估入口固定以 CURRENT_EVIDENCE 调用，
        // 历史硬失败降级不能只在评估用途为 HISTORY 时生效——READY 生成来源的上游
        // 合规撤销在 CURRENT_EVIDENCE 评估下也必须给出 historyReadable=false
        insertSource(WS, "dep-compliance", "DELETED", "COMPLIANCE", null, null);
        String run = insertRun(WS, "[\"dep-compliance\"]");
        insertGeneratedSource(WS, "report-ev", run);

        Map<String, EligibilityDecision> evidencePurpose = assessor.assess(
                WS, List.of(OriginRef.internalSource("report-ev")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(
                evidencePurpose.get("source:report-ev").historyReadable(),
                String.join(";", evidencePurpose.get("source:report-ev").dependencySummary()));
        org.junit.jupiter.api.Assertions.assertFalse(
                evidencePurpose.get("source:report-ev").allowed(AccessPurpose.HISTORY_DISPLAY));
    }

    @org.junit.jupiter.api.Test
    void userDeletedGeneratedSourceStillChecksUpstreamHardRevocation() {
        // NR04：G 先被 USER 删除、上游 S 随后合规撤销——G 自身状态历史可读，
        // 但闭包硬失败必须阻断历史正文（此前非 READY 状态完全跳过闭包遍历）
        insertSource(WS, "upstream", "DELETED", "COMPLIANCE", null, null);
        String run = insertRun(WS, "[\"upstream\"]");
        insertSource(WS, "g-user-deleted", "DELETED", "USER", "research_agent", run);

        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("g-user-deleted")), AccessPurpose.CURRENT_EVIDENCE);
        EligibilityDecision decision = decisions.get("source:g-user-deleted");
        org.junit.jupiter.api.Assertions.assertFalse(decision.historyReadable(),
                String.join(";", decision.dependencySummary()));
        org.junit.jupiter.api.Assertions.assertTrue(decision.dependencySummary().stream()
                .anyMatch(failure -> failure.endsWith("COMPLIANCE_REVOKED")));
        // 证据位本来就被 USER 删除阻断，不因闭包遍历引入新路径
        org.junit.jupiter.api.Assertions.assertFalse(decision.evidenceEligible());
    }

    @org.junit.jupiter.api.Test
    void artifactAgentVersionCitationsResolve() {
        jdbc.update("insert into artifact_job (id, workspace_id) values ('job-1', ?)", WS);
        jdbc.update("insert into artifact_version (id, artifact_job_id, version_no, citations_json) "
                + "values ('ver-1', 'job-1', 1, ?)",
                (Object) "[{\"sourceId\":\"plain\"}]");
        insertPlainSource(WS, "plain");
        insertSource(WS, "src-artifact", null, null, "artifact_agent", "ver-1");
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-artifact")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertTrue(decisions.get("source:src-artifact").evidenceEligible(),
                String.join(";", decisions.get("source:src-artifact").dependencySummary()));

        GeneratedDependencyAssessment missingVersion = assessor.assessGeneratedDependenciesDetailed(
                WS, "artifact_agent", "ver-none", AccessPurpose.REPUBLISH);
        org.junit.jupiter.api.Assertions.assertFalse(missingVersion.resolved());
        org.junit.jupiter.api.Assertions.assertTrue(missingVersion.blockingReasons().stream()
                .anyMatch(reason -> reason.endsWith("VERSION_UNRESOLVED")));
    }

    @org.junit.jupiter.api.Test
    void redactedResearchContextRevokesGeneratedSource() {
        String run = insertRun(WS, "[]");
        jdbc.update("insert into run_input_snapshot (id, research_run_id, replay_availability) "
                + "values ('ris-1', ?, 'METADATA_ONLY')", run);
        jdbc.update("update research_run set context_snapshot_id = 'ris-1' where id = ?", run);
        insertGeneratedSource(WS, "src-redacted", run);
        Map<String, EligibilityDecision> decisions = assessor.assess(
                WS, List.of(OriginRef.internalSource("src-redacted")), AccessPurpose.CURRENT_EVIDENCE);
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-redacted").evidenceEligible());
        org.junit.jupiter.api.Assertions.assertFalse(decisions.get("source:src-redacted").historyReadable());
        org.junit.jupiter.api.Assertions.assertEquals("RESEARCH_CONTEXT_REDACTED",
                decisions.get("source:src-redacted").reasonCode());
    }

    // === 测试基础设施 ===

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:eligibility-closure-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table source (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null default 'READY',
                    delete_reason varchar(32),
                    generated_by varchar(64),
                    generated_ref_id varchar(64)
                )
                """);
        template.execute("""
                create table source_snapshot (
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no bigint not null
                )
                """);
        template.execute("""
                create table research_run (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_scope_json text,
                    context_snapshot_id varchar(36)
                )
                """);
        template.execute("""
                create table run_input_snapshot (
                    id varchar(36) primary key,
                    research_run_id varchar(36) not null,
                    replay_availability varchar(32)
                )
                """);
        template.execute("""
                create table research_evidence_manifest (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    research_run_id varchar(36) not null
                )
                """);
        template.execute("""
                create table research_evidence_manifest_item (
                    id varchar(36) primary key,
                    manifest_id varchar(36) not null,
                    source_id varchar(64)
                )
                """);
        template.execute("""
                create table source_evidence (
                    id varchar(36) primary key,
                    research_run_id varchar(36) not null,
                    source_id varchar(64)
                )
                """);
        template.execute("create table artifact_job (id varchar(36) primary key, workspace_id varchar(36) not null)");
        template.execute("""
                create table artifact_version (
                    id varchar(36) primary key,
                    artifact_job_id varchar(36) not null,
                    version_no int not null,
                    citations_json text
                )
                """);
        return template;
    }

    private String insertRun(String workspaceId, String scopeJson) {
        String runId = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into research_run (id, workspace_id, source_scope_json) values (?, ?, ?)",
                runId, workspaceId, scopeJson);
        return runId;
    }

    private void insertEvidenceCitation(String runId, String sourceId) {
        jdbc.update("insert into source_evidence (id, research_run_id, source_id) values (?, ?, ?)",
                java.util.UUID.randomUUID().toString(), runId, sourceId);
    }

    private void insertGeneratedSource(String workspaceId, String sourceId, String runId) {
        insertSource(workspaceId, sourceId, null, null, "research_agent", runId);
    }

    private void insertPlainSource(String workspaceId, String sourceId) {
        insertSource(workspaceId, sourceId, null, null, null, null);
    }

    private void insertSource(
            String workspaceId, String sourceId, String status, String deleteReason,
            String generatedBy, String generatedRefId) {
        jdbc.update("""
                insert into source (id, workspace_id, status, delete_reason, generated_by, generated_ref_id)
                values (?, ?, coalesce(?, 'READY'), ?, ?, ?)
                """, sourceId, workspaceId, status, deleteReason, generatedBy, generatedRefId);
        jdbc.update("insert into source_snapshot (id, source_id, version_no) values (?, ?, 1)",
                "snap-" + sourceId, sourceId);
    }
}
