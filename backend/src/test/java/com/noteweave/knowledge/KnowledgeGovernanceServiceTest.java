package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class KnowledgeGovernanceServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeCommandService commandService;
    private KnowledgeGovernanceService governanceService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-governance-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        WorkspaceQueryPort workspaceQueryPort = mock(WorkspaceQueryPort.class);
        when(workspaceQueryPort.exists("workspace")).thenReturn(true);
        when(workspaceQueryPort.isWikiEnabled("workspace")).thenReturn(true);
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("actor");
        KnowledgeWikiMutationService mutationService =
                new KnowledgeWikiMutationService(jdbcTemplate);
        KnowledgeVersionService versionService = new KnowledgeVersionService(
                jdbcTemplate, actorProvider, mutationService);
        commandService = new KnowledgeCommandService(
                jdbcTemplate,
                workspaceQueryPort,
                actorProvider,
                versionService,
                mutationService);
        governanceService = new KnowledgeGovernanceService(
                jdbcTemplate,
                workspaceQueryPort,
                new KnowledgeWikiSearchEngine(jdbcTemplate),
                versionService,
                mutationService,
                commandService);
    }

    @Test
    void shouldPrioritizeBrokenLinksAndProjectStatsAdviceAndFilters() {
        commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Source Page", "See [[Missing Page]].", null));

        List<WikiIssueResponse> issues = governanceService.lintWiki("workspace");
        WikiStatsResponse stats = governanceService.getWikiStats("workspace");
        WikiRebuildAdviceResponse advice =
                governanceService.getWikiRebuildAdvice("workspace");

        assertThat(issues).extracting(WikiIssueResponse::issueType)
                .startsWith("BROKEN_LINK")
                .contains("MISSING_SOURCE");
        assertThat(governanceService.listWikiIssues(
                "workspace", "BROKEN_LINK", "HIGH", true, null))
                .hasSize(1)
                .allMatch(WikiIssueResponse::autoFixable);
        assertThat(stats.pageCount()).isEqualTo(1);
        assertThat(stats.unresolvedLinkCount()).isEqualTo(1);
        assertThat(stats.autoFixableIssueCount()).isEqualTo(1);
        assertThat(advice.recommendedAction()).isEqualTo("AUTO_FIX_WIKI");
        assertThat(advice.recommendedIssueType()).isEqualTo("BROKEN_LINK");
    }

    @Test
    void shouldRebuildLinksThroughImmutableVersionAndPreserveCitations() {
        KnowledgeItemResponse sourcePage = commandService.createItemWithVersion(
                "workspace",
                "WIKI",
                "Source Page",
                "References Related Page.",
                null,
                List.of("citation-b", "citation-a"));
        commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Related Page", "related content", null));

        WikiStatsResponse stats = governanceService.rebuildWikiLinks("workspace");

        assertThat(jdbcTemplate.queryForObject("""
                select v.version_no
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, Integer.class, sourcePage.itemId())).isEqualTo(2);
        String latestVersionId = jdbcTemplate.queryForObject(
                "select latest_version_id from knowledge_item where id = ?",
                String.class,
                sourcePage.itemId());
        assertThat(jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = ?",
                String.class,
                latestVersionId)).contains("[[Related Page]]");
        assertThat(jdbcTemplate.queryForList("""
                select citation_id
                from knowledge_version_citation
                where knowledge_version_id = ?
                order by sort_order
                """, String.class, latestVersionId))
                .containsExactly("citation-b", "citation-a");
        assertThat(stats.linkCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where event_type = 'REBUILD_LINKS'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldAutoFixMissingPageThroughCommandOwnerAndResolveBrokenLink() {
        commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Source Page", "See [[Missing Page]].", null));

        WikiAutoFixResponse fixed = governanceService.autoFixWiki("workspace");

        assertThat(fixed.createdPages()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_item
                where workspace_id = 'workspace' and title = 'Missing Page'
                  and status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                select target_item_id, relation_status
                from knowledge_item_link
                where workspace_id = 'workspace' and target_title = 'Missing Page'
                """))
                .containsEntry("RELATION_STATUS", "RESOLVED");
        assertThat(governanceService.listWikiIssues(
                "workspace", "BROKEN_LINK", null, null, null)).isEmpty();
        assertThat(governanceService.listWikiIssues(
                "workspace", "PLACEHOLDER_CONTENT", null, null, null)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where event_type = 'AUTO_FIX'
                """, Integer.class)).isEqualTo(1);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_type varchar(32) not null,
                    page_kind varchar(32),
                    title varchar(300) not null,
                    status varchar(32) not null,
                    latest_version_id varchar(36),
                    created_by varchar(80),
                    updated_by varchar(80),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version(
                    id varchar(36) primary key,
                    item_id varchar(36) not null,
                    version_no int not null,
                    content clob not null,
                    summary varchar(1000),
                    source_message_id varchar(36),
                    created_at timestamp default current_timestamp,
                    unique(item_id, version_no)
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
                    source_id varchar(36) not null,
                    title varchar(300),
                    quote_text varchar(1000),
                    page_no int,
                    location_info varchar(300)
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(300),
                    status varchar(32),
                    index_status varchar(32),
                    generated_by varchar(64),
                    generated_ref_id varchar(128),
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
                    mention_count int not null,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table wiki_log_entry(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_id varchar(36),
                    event_type varchar(64) not null,
                    message varchar(1000) not null,
                    created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    task_type varchar(64),
                    task_status varchar(32),
                    progress_phase varchar(64),
                    progress_message varchar(1000),
                    target_type varchar(64),
                    target_id varchar(36),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table conversation_message(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    role varchar(32) not null,
                    content clob not null
                )
                """);
        jdbcTemplate.execute("""
                create table message_citation(
                    message_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);
    }
}
