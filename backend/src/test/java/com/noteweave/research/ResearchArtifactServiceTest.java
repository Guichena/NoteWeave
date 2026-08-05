package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ResearchArtifactServiceTest {

    @Test
    void saveReportAsSourceShouldOwnPersistenceAndRemainIdempotent() {
        Fixture fixture = fixture();
        try {
            fixture.jdbcTemplate().update("""
                    insert into research_run(
                        id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                    ) values ('run-1', 'workspace-1', 'COMPLETED', 'Report title', '# Report', null)
                    """);

            SaveResearchReportSourceResponse first = fixture.service()
                    .saveReportAsSource("workspace-1", "run-1");
            SaveResearchReportSourceResponse replay = fixture.service()
                    .saveReportAsSource("workspace-1", "run-1");

            assertThat(replay.sourceId()).isEqualTo(first.sourceId());
            assertThat(first.sourceType()).isEqualTo("GENERATED_RESEARCH_REPORT");
            assertThat(fixture.jdbcTemplate().queryForObject(
                    "select count(*) from source", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbcTemplate().queryForObject(
                    "select count(*) from source_snapshot", Integer.class)).isEqualTo(1);
            verify(fixture.sourceCatalogVersionService(), times(1)).bump("workspace-1");
            verify(fixture.sourceParseService(), times(1)).parseAndIndex(
                    org.mockito.ArgumentMatchers.eq("workspace-1"),
                    org.mockito.ArgumentMatchers.eq(first.sourceId()),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(byte[].class)
            );
        } finally {
            fixture.database().shutdown();
        }
    }

    @Test
    void evidenceManifestShouldReturnOrderedEvidenceOwnedByTheArtifactModule() {
        Fixture fixture = fixture();
        try {
            fixture.jdbcTemplate().update("""
                    insert into research_run(
                        id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                    ) values ('run-1', 'workspace-1', 'COMPLETED', 'Report', '# Report', null)
                    """);
            fixture.jdbcTemplate().update("""
                    insert into research_evidence_manifest(id, workspace_id, research_run_id, report_content_hash)
                    values ('manifest-1', 'workspace-1', 'run-1', 'report-hash')
                    """);
            fixture.jdbcTemplate().update("""
                    insert into research_evidence_manifest_item(
                        manifest_id, rank_no, evidence_id, source_id, source_snapshot_id,
                        passage_id, title, excerpt, content_hash, location_info
                    ) values
                        ('manifest-1', 2, 'evidence-2', 'source-2', 'snapshot-2', null,
                         'Second', 'second excerpt', 'hash-2', 'p2'),
                        ('manifest-1', 1, 'evidence-1', 'source-1', 'snapshot-1', 'passage-1',
                         'First', 'first excerpt', 'hash-1', 'p1')
                    """);

            ResearchEvidenceManifestResponse response = fixture.service()
                    .evidenceManifest("workspace-1", "run-1");

            assertThat(response.reportContentHash()).isEqualTo("report-hash");
            assertThat(response.evidence()).extracting(ResearchEvidenceManifestResponse.Evidence::evidenceId)
                    .containsExactly("evidence-1", "evidence-2");
        } finally {
            fixture.database().shutdown();
        }
    }

    private Fixture fixture() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        createSchema(jdbcTemplate);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.exists("workspace-1")).thenReturn(true);
        ObjectStorage storage = mock(ObjectStorage.class);
        SourceParseService sourceParseService = mock(SourceParseService.class);
        WikiIngestService wikiIngestService = mock(WikiIngestService.class);
        SourceCatalogVersionService sourceCatalogVersionService = mock(SourceCatalogVersionService.class);
        ResearchArtifactService service = new ResearchArtifactService(
                jdbcTemplate,
                new ObjectMapper(),
                workspaceService,
                storage,
                sourceParseService,
                wikiIngestService,
                sourceCatalogVersionService
        );
        return new Fixture(
                database, jdbcTemplate, service, sourceParseService, sourceCatalogVersionService);
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table research_run(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null,
                    final_report_title varchar(255),
                    final_report_markdown clob,
                    report_source_id varchar(36),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    object_key varchar(1024) not null,
                    sha256 varchar(64) not null,
                    file_size bigint not null,
                    mime_type varchar(128),
                    ref_count int not null
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    file_object_id varchar(36),
                    title varchar(255),
                    source_type varchar(64),
                    status varchar(32),
                    parse_status varchar(32),
                    index_status varchar(32),
                    generated_by varchar(64),
                    generated_ref_id varchar(36),
                    created_by varchar(128),
                    updated_by varchar(128)
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36),
                    file_object_id varchar(36),
                    version_no int,
                    object_key varchar(1024),
                    sha256 varchar(64),
                    parse_status varchar(32),
                    index_status varchar(32)
                )
                """);
        jdbcTemplate.execute("""
                create table research_trace(
                    id varchar(36) primary key,
                    research_run_id varchar(36),
                    trace_type varchar(64),
                    trace_message varchar(1024),
                    payload_json clob
                )
                """);
        jdbcTemplate.execute("""
                create table research_evidence_manifest(
                    id varchar(36) primary key,
                    workspace_id varchar(36),
                    research_run_id varchar(36),
                    report_content_hash varchar(64),
                    created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table research_evidence_manifest_item(
                    manifest_id varchar(36),
                    rank_no int,
                    evidence_id varchar(64),
                    source_id varchar(36),
                    source_snapshot_id varchar(36),
                    passage_id varchar(64),
                    title varchar(255),
                    excerpt clob,
                    content_hash varchar(64),
                    location_info varchar(255)
                )
                """);
    }

    private record Fixture(
            EmbeddedDatabase database,
            JdbcTemplate jdbcTemplate,
            ResearchArtifactService service,
            SourceParseService sourceParseService,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
    }
}
