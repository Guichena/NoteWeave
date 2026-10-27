package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.noteweave.workspace.WorkspaceQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class KnowledgeGovernanceSourceRevokedTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeGovernanceService governanceService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-governance-source-revoked-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        governanceService = new KnowledgeGovernanceService(
                jdbcTemplate,
                mock(WorkspaceQueryPort.class),
                new KnowledgeWikiSearchEngine(jdbcTemplate),
                mock(KnowledgeVersionService.class),
                mock(KnowledgeWikiMutationService.class),
                mock(KnowledgeCommandService.class),
                mock(KnowledgeCitationReadGate.class));
    }

    @Test
    void deletedSourceShouldProduceSingleSourceRevokedIssueAndSuppressContentStale() {
        Timestamp versionCreatedAt = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp sourceUpdatedAt = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        insertWorkspace();
        insertWikiPage("page-revoked", "来源已删除页", "version-revoked", versionCreatedAt);
        insertSource("source-revoked", "已删除资料", "READY", sourceUpdatedAt);
        insertCitation("citation-a", "source-revoked", "已删除资料");
        insertCitation("citation-b", "source-revoked", "已删除资料");
        linkCitation("kvc-a", "version-revoked", "citation-a", 0);
        linkCitation("kvc-b", "version-revoked", "citation-b", 1);

        insertWikiPage("page-stale", "资料更新页", "version-stale", versionCreatedAt);
        insertSource("source-ready", "仍可用资料", "READY", sourceUpdatedAt);
        insertCitation("citation-c", "source-ready", "仍可用资料");
        linkCitation("kvc-c", "version-stale", "citation-c", 0);

        List<WikiIssueResponse> beforeRevoke = governanceService.lintWiki("workspace");
        assertThat(beforeRevoke)
                .noneMatch(issue -> "SOURCE_REVOKED".equals(issue.issueType()));
        assertThat(beforeRevoke)
                .anyMatch(issue -> "CONTENT_STALE".equals(issue.issueType())
                        && "page-revoked".equals(issue.itemId()));

        jdbcTemplate.update("""
                update source
                set status = 'DELETED', updated_at = ?
                where id = ?
                """, sourceUpdatedAt, "source-revoked");

        List<WikiIssueResponse> issues = governanceService.lintWiki("workspace");
        List<WikiIssueResponse> revokedIssues = issues.stream()
                .filter(issue -> "SOURCE_REVOKED".equals(issue.issueType()))
                .filter(issue -> "page-revoked".equals(issue.itemId()))
                .toList();

        assertThat(revokedIssues).hasSize(1);
        WikiIssueResponse revokedIssue = revokedIssues.get(0);
        assertThat(revokedIssue.severity()).isEqualTo("HIGH");
        assertThat(revokedIssue.title()).isEqualTo("来源已删除页");
        assertThat(revokedIssue.message())
                .contains("来源已删除")
                .contains("正文可能失效");
        assertThat(revokedIssue.suggestedAction())
                .contains("重新绑定来源")
                .contains("重建页面");
        assertThat(revokedIssue.autoFixable()).isFalse();
        assertThat(issues)
                .noneMatch(issue -> "CONTENT_STALE".equals(issue.issueType())
                        && "page-revoked".equals(issue.itemId()));
        assertThat(issues)
                .anyMatch(issue -> "CONTENT_STALE".equals(issue.issueType())
                        && "page-stale".equals(issue.itemId()));

        assertThat(governanceService.listWikiIssues(
                "workspace", "SOURCE_REVOKED", "HIGH", false, "page-revoked"))
                .hasSize(1);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table workspace(
                    id varchar(36) primary key,
                    status varchar(32) not null,
                    wiki_enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_type varchar(32) not null,
                    title varchar(300) not null,
                    status varchar(32) not null,
                    latest_version_id varchar(36),
                    origin varchar(32),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version(
                    id varchar(36) primary key,
                    item_id varchar(36) not null,
                    version_no int not null,
                    content clob not null,
                    created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version_citation(
                    id varchar(36) primary key,
                    knowledge_version_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);
        jdbcTemplate.execute("""
                create table citation(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    title varchar(300),
                    quote_text varchar(1000)
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(300),
                    status varchar(32),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_item_link(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_item_id varchar(36) not null,
                    target_item_id varchar(36),
                    target_title varchar(300) not null,
                    relation_type varchar(64) not null,
                    relation_status varchar(32) not null,
                    updated_at timestamp default current_timestamp
                )
                """);
    }

    private void insertWorkspace() {
        jdbcTemplate.update("""
                insert into workspace(id, status, wiki_enabled)
                values ('workspace', 'ACTIVE', true)
                """);
    }

    private void insertWikiPage(
            String itemId,
            String title,
            String versionId,
            Timestamp createdAt
    ) {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, title, status, latest_version_id, updated_at
                ) values (?, 'workspace', 'WIKI', ?, 'ACTIVE', ?, ?)
                """, itemId, title, versionId, createdAt);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, created_at)
                values (?, ?, 1, ?, ?)
                """, versionId, itemId, "# " + title + "\n\n页面正文。", createdAt);
    }

    private void insertSource(
            String sourceId,
            String title,
            String status,
            Timestamp updatedAt
    ) {
        jdbcTemplate.update("""
                insert into source(id, workspace_id, title, status, updated_at)
                values (?, 'workspace', ?, ?, ?)
                """, sourceId, title, status, updatedAt);
    }

    private void insertCitation(String citationId, String sourceId, String title) {
        jdbcTemplate.update("""
                insert into citation(id, workspace_id, source_id, title, quote_text)
                values (?, 'workspace', ?, ?, '引用片段')
                """, citationId, sourceId, title);
    }

    private void linkCitation(
            String linkId,
            String versionId,
            String citationId,
            int sortOrder
    ) {
        jdbcTemplate.update("""
                insert into knowledge_version_citation(
                    id, knowledge_version_id, citation_id, sort_order
                ) values (?, ?, ?, ?)
                """, linkId, versionId, citationId, sortOrder);
    }
}
