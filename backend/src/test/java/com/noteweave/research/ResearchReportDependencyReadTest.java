package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.workspace.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ResearchReportDependencyReadTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ResearchArtifactService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        createSchema();
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.exists("workspace-1")).thenReturn(true);
        // 依赖闭包统一走 C1 端口：使用真实评估器验证状态矩阵与闭包递归
        ResearchGeneratedSourceReadGate gate = new ResearchGeneratedSourceReadGate(
                jdbc, mock(ResearchContextV2Gate.class),
                new com.noteweave.source.eligibility.MaterialEligibilityAssessor(jdbc, objectMapper));
        service = new ResearchArtifactService(
                jdbc,
                objectMapper,
                workspaceService,
                mock(ObjectStorage.class),
                mock(SourceParseService.class),
                mock(WikiIngestService.class),
                mock(SourceCatalogVersionService.class),
                null,
                gate
        );
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void readyDependencyAllowsReportReadAndEvidenceExport() {
        insertRun("[\"source-1\"]");
        insertSource("source-1", "READY");
        insertManifest("source-1");

        assertThatCode(() -> service.loadRunArtifactView("workspace-1", "run-1"))
                .doesNotThrowAnyException();
        assertThat(service.evidenceManifest("workspace-1", "run-1").evidence()).hasSize(1);
    }

    @Test
    void deletedScopeSourceBlocksSavingButKeepsRedactedHistoryReadable() {
        insertRun("[\"source-1\"]");
        insertSource("source-1", "DELETED");
        insertManifest("source-1");

        assertThatCode(() -> service.loadRunArtifactView("workspace-1", "run-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.evidenceManifest("workspace-1", "run-1"))
                .doesNotThrowAnyException();
        assertRevoked(() -> service.saveReportAsSource("workspace-1", "run-1"));
        assertThat(jdbc.queryForObject("select count(*) from source", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletedScopeSourceBlocksSavingReportAsSource() {
        insertRun("[\"source-1\"]");
        insertSource("source-1", "DELETED");

        assertRevoked(() -> service.saveReportAsSource("workspace-1", "run-1"));
        assertThat(jdbc.queryForObject("select count(*) from source", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletedCitedEvidenceSourceBlocksExportGateButKeepsRedactedEvidenceReadable() {
        insertRun("[]");
        insertSource("source-1", "DELETED");
        insertManifest("source-1");
        jdbc.update("""
                insert into source_evidence(research_run_id, source_id)
                values ('run-1', 'source-1')
                """);

        assertRevoked(() -> service.requireReportDependenciesReadable("workspace-1", "run-1"));
        assertThatCode(() -> service.evidenceManifest("workspace-1", "run-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void externalEvidenceWithoutSourceRowIsNotTreatedAsDeleted() {
        insertRun("[]");
        insertManifest("web-abc");

        assertThatCode(() -> service.evidenceManifest("workspace-1", "run-1"))
                .doesNotThrowAnyException();
    }

    private void assertRevoked(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    // 依赖闭包阻断用 RESEARCH_SOURCE_REVOKED，重复保存的来源失效用专用码
                    assertThat(failure.code()).isIn(
                            "RESEARCH_SOURCE_REVOKED", "RESEARCH_REPORT_SOURCE_REVOKED");
                    assertThat(failure.getMessage()).contains("已删除");
                });
    }

    private void insertRun(String sourceScopeJson) {
        jdbc.update("""
                insert into research_run(
                    id, workspace_id, status, final_report_title, final_report_markdown,
                    report_source_id, source_scope_json
                ) values ('run-1', 'workspace-1', 'COMPLETED', 'Report', '# Report', null, ?)
                """, sourceScopeJson);
    }

    private void insertSource(String sourceId, String status) {
        jdbc.update("""
                insert into source(id, workspace_id, status, generated_by, generated_ref_id)
                values (?, 'workspace-1', ?, '', '')
                """, sourceId, status);
    }

    private void insertManifest(String sourceId) {
        jdbc.update("""
                insert into research_evidence_manifest(
                    id, workspace_id, research_run_id, report_content_hash
                ) values ('manifest-1', 'workspace-1', 'run-1', 'report-hash')
                """);
        jdbc.update("""
                insert into research_evidence_manifest_item(
                    manifest_id, rank_no, evidence_id, source_id, source_snapshot_id,
                    passage_id, title, excerpt, content_hash, location_info
                ) values ('manifest-1', 1, 'evidence-1', ?, 'snapshot-1', 'passage-1',
                    'Title', 'Excerpt', 'hash-1', 'location-1')
                """, sourceId);
    }

    @Test
    void duplicateSaveStillChecksCurrentUsabilityOfExistingSource() {
        insertRun("[\"source-1\"]");
        insertSource("source-1", "READY");
        jdbc.update("update research_run set report_source_id = 'report-source' where id = 'run-1'");
        jdbc.update("""
                insert into source(id, workspace_id, status, delete_reason, generated_by, generated_ref_id)
                values ('report-source', 'workspace-1', 'READY', 'USER', 'research_agent', 'run-1')
                """);
        // 依赖随后被删除：重复保存不能在依赖检查前直接返回既有来源
        jdbc.update("update source set status = 'DELETED' where id = 'source-1'");
        assertRevoked(() -> service.saveReportAsSource("workspace-1", "run-1"));
        assertThat(jdbc.queryForObject(
                "select count(*) from source", Integer.class)).isEqualTo(2);
    }

    @Test
    void complianceRevokedDependencyBlocksReportBodyDelivery() {
        insertRun("[]");
        insertSource("source-1", "DELETED");
        jdbc.update("update source set delete_reason = 'COMPLIANCE' where id = 'source-1'");
        insertManifest("source-1");
        // COMPLIANCE 撤销：详情只保留元数据视图，正文文件信息不再下发
        ResearchArtifactService.RunArtifactView view = service.loadRunArtifactView("workspace-1", "run-1");
        assertThat(view.reportFile()).isNull();
        assertRevoked(() -> service.downloadReportMarkdown("workspace-1", "run-1"));
    }

    @Test
    void downloadRequiresReadableDependencies() {
        insertRun("[]");
        insertSource("source-1", "READY");
        insertManifest("source-1");
        assertThatCode(() -> service.downloadReportMarkdown("workspace-1", "run-1"))
                .doesNotThrowAnyException();
    }

    private void createSchema() {
        jdbc.execute("""
                create table research_run(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null,
                    final_report_title varchar(255),
                    final_report_markdown clob,
                    report_source_id varchar(36),
                    source_scope_json clob,
                    context_snapshot_id varchar(128),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbc.execute("""
                create table run_input_snapshot(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    execution_kind varchar(32) not null,
                    conversation_id varchar(36) not null,
                    research_run_id varchar(36),
                    replay_availability varchar(32) not null default 'FULL'
                )
                """);
        jdbc.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null,
                    delete_reason varchar(16) default 'USER',
                    generated_by varchar(64),
                    generated_ref_id varchar(36)
                )
                """);
        jdbc.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null
                )
                """);
        jdbc.execute("""
                create table research_evidence_manifest(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    research_run_id varchar(36) not null,
                    report_content_hash varchar(64),
                    created_at timestamp default current_timestamp
                )
                """);
        jdbc.execute("""
                create table research_evidence_manifest_item(
                    manifest_id varchar(36) not null,
                    rank_no int not null,
                    evidence_id varchar(64),
                    source_id varchar(128),
                    source_snapshot_id varchar(128),
                    passage_id varchar(64),
                    title varchar(255),
                    excerpt clob,
                    content_hash varchar(64),
                    location_info varchar(255)
                )
                """);
        jdbc.execute("""
                create table source_evidence(
                    research_run_id varchar(36) not null,
                    source_id varchar(128)
                )
                """);
    }
}
