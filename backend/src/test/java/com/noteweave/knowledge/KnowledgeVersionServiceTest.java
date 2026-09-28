package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeVersionServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeVersionService versionService;
    private TransactionTemplate transactionTemplate;
    private ResearchGeneratedSourceReadGate sourceGate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-version-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        createSchema();
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("actor");
        sourceGate = mock(ResearchGeneratedSourceReadGate.class);
        when(sourceGate.readableSourceIds(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(1)));
        versionService = new KnowledgeVersionService(
                jdbcTemplate,
                actorProvider,
                new KnowledgeWikiMutationService(jdbcTemplate),
                new KnowledgeCitationReadGate(jdbcTemplate, sourceGate));
    }

    @Test
    void shouldAppendImmutableVersionMoveLatestPointerAndPreserveCitationOrder() {
        seedItem("item-note", "NOTE", "Note", "version-1", "old content");
        seedCitation("citation-a", "Source A");
        seedCitation("citation-b", "Source B");

        KnowledgeItemResponse appended = versionService.appendVersion(
                "item-note",
                new AppendKnowledgeVersionRequest(
                        "new content", null, List.of("citation-b", "citation-a")));

        assertThat(appended.latestVersionNo()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = 'version-1'",
                String.class)).isEqualTo("old content");
        assertThat(jdbcTemplate.queryForObject(
                "select latest_version_id from knowledge_item where id = 'item-note'",
                String.class)).isEqualTo(appended.latestVersionId());
        assertThat(versionService.listItemVersions("item-note"))
                .extracting(KnowledgeVersionSummaryResponse::versionNo)
                .containsExactly(2, 1);
        KnowledgeVersionDetailResponse detail = versionService.getItemVersionDetail(
                "item-note", 2);
        assertThat(detail.content()).isEqualTo("new content");
        assertThat(detail.citations())
                .extracting(KnowledgeCitationResponse::citationId)
                .containsExactly("citation-b", "citation-a");
    }

    @Test
    void revokedCitationMustHideHistoricalSummaryAndBody() {
        seedItem("item-note", "NOTE", "Note", "version-1", "old content");
        seedCitation("citation-a", "Source A");
        KnowledgeItemResponse appended = versionService.appendVersion("item-note",
                new AppendKnowledgeVersionRequest("derived content", null, List.of("citation-a")));
        when(sourceGate.readableSourceIds(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(Set.of());

        assertThat(versionService.listItemVersions("item-note"))
                .extracting(KnowledgeVersionSummaryResponse::versionId)
                .containsExactly("version-1");
        assertThatThrownBy(() -> versionService.getItemVersionDetail("item-note", 2))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_SOURCE_REVOKED"));
        assertThatThrownBy(() -> versionService.citationIdsForVersion(appended.latestVersionId()))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_SOURCE_REVOKED"));
        assertThatThrownBy(() -> versionService.appendVersion("item-note",
                new AppendKnowledgeVersionRequest("another derived version", null,
                        List.of("citation-a"))))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_CITATION_REVOKED"));
        assertThat(jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = ?", String.class,
                appended.latestVersionId())).isEqualTo("derived content");
    }

    @Test
    void shouldSerializeConcurrentAppendsThroughKnowledgeItemRowLock() throws Exception {
        seedItem("item-concurrent", "NOTE", "Concurrent", "version-1", "initial");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return transactionTemplate.execute(status -> versionService.appendVersion(
                        "item-concurrent",
                        new AppendKnowledgeVersionRequest("second", null, List.of())));
            });
            var second = executor.submit(() -> {
                start.await();
                return transactionTemplate.execute(status -> versionService.appendVersion(
                        "item-concurrent",
                        new AppendKnowledgeVersionRequest("third", null, List.of())));
            });
            start.countDown();
            assertThat(first.get()).isNotNull();
            assertThat(second.get()).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForList("""
                select version_no from knowledge_version
                where item_id = 'item-concurrent'
                order by version_no
                """, Integer.class)).containsExactly(1, 2, 3);
        assertThat(jdbcTemplate.queryForObject("""
                select v.version_no
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = 'item-concurrent'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void shouldPreserveWikiNormalizationLinkProjectionPageKindAndAudit() {
        seedItem("item-wiki", "WIKI", "Concept Page", "version-1", "initial");
        seedItem("item-related", "WIKI", "Related Page", "version-related", "related");

        KnowledgeItemResponse appended = versionService.appendVersion(
                "item-wiki",
                new AppendKnowledgeVersionRequest(
                        "Concept definition references Related Page.", null, List.of()));

        String content = jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = ?",
                String.class,
                appended.latestVersionId());
        assertThat(content).contains("[[Related Page]]");
        assertThat(appended.pageKind()).isEqualTo("CONCEPT");
        assertThat(jdbcTemplate.queryForMap("""
                select target_item_id, target_title, relation_type, relation_status
                from knowledge_item_link
                where source_item_id = 'item-wiki'
                """))
                .containsEntry("TARGET_ITEM_ID", "item-related")
                .containsEntry("TARGET_TITLE", "Related Page")
                .containsEntry("RELATION_TYPE", "WIKI_LINK")
                .containsEntry("RELATION_STATUS", "RESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where item_id = 'item-wiki' and event_type = 'UPDATE_PAGE'
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
                    workspace_id varchar(36) not null default 'workspace',
                    source_id varchar(36) not null,
                    title varchar(300) not null,
                    quote_text varchar(1000) not null,
                    page_no int,
                    location_info varchar(300)
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    generated_by varchar(64),
                    generated_ref_id varchar(128)
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
                    message varchar(1000) not null
                )
                """);
    }

    private void seedItem(
            String itemId,
            String itemType,
            String title,
            String versionId,
            String content
    ) {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status,
                    latest_version_id, updated_by
                ) values (?, 'workspace', ?, 'TOPIC', ?, 'ACTIVE', ?, 'seed')
                """, itemId, itemType, title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id
                ) values (?, ?, 1, ?, ?, null)
                """, versionId, itemId, content, content);
    }

    private void seedCitation(String citationId, String title) {
        jdbcTemplate.update("""
                merge into source key(id)
                values ('source', '', '')
                """);
        jdbcTemplate.update("""
                insert into citation(
                    id, source_id, title, quote_text, page_no, location_info
                ) values (?, 'source', ?, ?, 1, 'page:1')
                """, citationId, title, "quote " + title);
    }
}
