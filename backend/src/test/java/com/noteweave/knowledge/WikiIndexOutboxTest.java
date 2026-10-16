package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WikiIndexOutboxTest {

    private JdbcTemplate jdbcTemplate;
    private RecordingIndex index;
    private EmbeddingClient embedding;
    private WikiIndexOutbox outbox;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-outbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        index = new RecordingIndex();
        embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(false);
        outbox = new WikiIndexOutbox(jdbcTemplate, index, embedding);
    }

    @Test
    void enqueueDeduplicatesPendingOperation() {
        insertPage("page-a", "version-a", "Title A", "content a");

        outbox.enqueueUpsert("ws", "page-a");
        outbox.enqueueUpsert("ws", "page-a");
        outbox.enqueueDelete("ws", "page-a");
        outbox.enqueueDelete("ws", "page-a");

        assertThat(count("page-a", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(count("page-a", "DELETE", "PENDING")).isEqualTo(1);
    }

    @Test
    void dispatchUpsertsCurrentCountsAndDeletesMissingPages() {
        insertPage("page-a", "version-a", "Title A", "content a");
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    id, workspace_id, source_item_id, target_item_id, target_title,
                    relation_type, relation_status, mention_count
                ) values ('link-out', 'ws', 'page-a', null, 'Missing', 'WIKI_LINK', 'UNRESOLVED', 1)
                """);
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    id, workspace_id, source_item_id, target_item_id, target_title,
                    relation_type, relation_status, mention_count
                ) values ('link-in', 'ws', 'other', 'page-a', 'Title A', 'WIKI_LINK', 'RESOLVED', 1)
                """);
        jdbcTemplate.update("""
                insert into knowledge_version_citation(knowledge_version_id, citation_id, sort_order)
                values ('version-a', 'citation-a', 0)
                """);
        outbox.enqueueUpsert("ws", "page-a");

        outbox.dispatchPending();

        assertThat(index.upserts).hasSize(1);
        WikiPageIndex.Document document = index.upserts.get(0);
        assertThat(document.itemId()).isEqualTo("page-a");
        assertThat(document.versionId()).isEqualTo("version-a");
        assertThat(document.outgoingCount()).isEqualTo(1);
        assertThat(document.backlinkCount()).isEqualTo(1);
        assertThat(document.citationCount()).isEqualTo(1);
        assertThat(document.unresolvedCount()).isEqualTo(1);
        assertThat(document.embedding()).isNull();
        assertThat(status("page-a", "UPSERT")).isEqualTo("SENT");

        jdbcTemplate.update("update knowledge_item set status = 'DELETED' where id = 'page-a'");
        jdbcTemplate.update("""
                insert into wiki_index_outbox(
                    id, workspace_id, item_id, version_id, operation, status, attempts
                ) values ('again', 'ws', 'page-a', 'version-a', 'UPSERT', 'PENDING', 0)
                """);
        outbox.dispatchPending();
        assertThat(index.deletes).containsExactly("ws:page-a");
    }

    @Test
    void dispatchDeleteAndStopsAfterRepeatedFailures() {
        insertPage("page-a", "version-a", "Title A", "content a");
        outbox.enqueueDelete("ws", "page-a");
        outbox.dispatchPending();
        assertThat(index.deletes).containsExactly("ws:page-a");
        assertThat(status("page-a", "DELETE")).isEqualTo("SENT");

        index.accept = false;
        jdbcTemplate.update("""
                insert into wiki_index_outbox(
                    id, workspace_id, item_id, operation, status, attempts
                ) values ('fail', 'ws', 'page-a', 'DELETE', 'PENDING', 0)
                """);
        for (int attempt = 0; attempt < 4; attempt++) {
            outbox.dispatchPending();
        }
        assertThat(jdbcTemplate.queryForObject(
                "select attempts from wiki_index_outbox where id = 'fail'", Integer.class)).isEqualTo(4);
        outbox.dispatchPending();
        assertThat(jdbcTemplate.queryForObject(
                "select status from wiki_index_outbox where id = 'fail'", String.class)).isEqualTo("FAILED");
    }

    @Test
    void unavailableIndexDoesNotDispatchOrBackfill() {
        insertPage("page-a", "version-a", "Title A", "content a");
        insertPage("page-b", "version-b", "Title B", "content b");
        outbox.enqueueUpsert("ws", "page-a");
        index.available = false;

        outbox.dispatchPending();

        assertThat(status("page-a", "UPSERT")).isEqualTo("PENDING");
        assertThat(count("page-b", "UPSERT", "PENDING")).isZero();
        assertThat(index.upserts).isEmpty();
    }

    @Test
    void backfillWaitsForTheNextDispatchAfterVersionChange() {
        insertPage("page-a", "version-a", "Title A", "content a");
        insertPage("page-b", "version-b", "Title B", "content b");
        outbox.enqueueUpsert("ws", "page-a");

        outbox.dispatchPending();

        assertThat(index.upserts).extracting(WikiPageIndex.Document::itemId).containsExactly("page-a");
        assertThat(status("page-b", "UPSERT")).isEqualTo("PENDING");
        outbox.dispatchPending();
        assertThat(index.upserts).extracting(WikiPageIndex.Document::itemId)
                .containsExactly("page-a", "page-b");

        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary)
                values ('version-a2', 'page-a', 2, 'content a2', 'summary a2')
                """);
        jdbcTemplate.update(
                "update knowledge_item set latest_version_id = 'version-a2' where id = 'page-a'");
        outbox.dispatchPending();
        assertThat(count("page-a", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(index.upserts).extracting(WikiPageIndex.Document::versionId)
                .doesNotContain("version-a2");

        outbox.dispatchPending();
        assertThat(index.upserts.get(index.upserts.size() - 1).versionId()).isEqualTo("version-a2");
        assertThat(count("page-a", "UPSERT", "PENDING")).isZero();
    }

    @Test
    void mutationEnqueuesSourceTargetAndResolvedIncomingPages() {
        insertPage("page-a", "version-a", "Title A", "content a");
        insertPage("page-b", "version-b", "Title B", "content b");
        KnowledgeWikiMutationService mutation = new KnowledgeWikiMutationService(jdbcTemplate, outbox);

        mutation.upsertWikiLinks("ws", "page-a", "Title A", "plain text");
        mutation.upsertWikiLinks("ws", "page-a", "Title A", "see [[Title B]]");

        assertThat(count("page-a", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(count("page-b", "UPSERT", "PENDING")).isEqualTo(1);

        insertPage("page-d", "version-d", "Title D", "content d");
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    id, workspace_id, source_item_id, target_item_id, target_title,
                    relation_type, relation_status, mention_count
                ) values ('link-d', 'ws', 'page-c', null, 'Title D', 'WIKI_LINK', 'UNRESOLVED', 1)
                """);
        outbox.enqueueUpsert("ws", "page-d");
        mutation.resolveIncomingLinksForCreatedWikiPage("ws", "page-d", "Title D");

        assertThat(count("page-d", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(count("page-c", "UPSERT", "PENDING")).isEqualTo(1);
    }

    @Test
    void renameAndDeleteEnqueueRelatedPages() {
        insertPage("page-a", "version-a", "Old Title", "plain");
        insertPage("page-b", "version-b", "Neighbor", "mentions nothing");
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    id, workspace_id, source_item_id, target_item_id, target_title,
                    relation_type, relation_status, mention_count
                ) values ('link-b', 'ws', 'page-b', 'page-a', 'Old Title', 'WIKI_LINK', 'RESOLVED', 1)
                """);
        KnowledgeCommandService command = command();

        command.renameItem("page-a", new RenameKnowledgeItemRequest("New Title"));

        assertThat(count("page-a", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(count("page-b", "UPSERT", "PENDING")).isEqualTo(1);

        jdbcTemplate.update("delete from wiki_index_outbox");
        command.deleteItem("page-a");
        assertThat(count("page-a", "DELETE", "PENDING")).isEqualTo(1);
        assertThat(count("page-b", "UPSERT", "PENDING")).isEqualTo(1);
        assertThat(count("page-a", "UPSERT", "PENDING")).isZero();
    }

    private KnowledgeCommandService command() {
        AuditActorProvider actor = mock(AuditActorProvider.class);
        when(actor.currentOrSystem("KNOWLEDGE")).thenReturn("actor");
        KnowledgeWikiMutationService mutation = new KnowledgeWikiMutationService(jdbcTemplate, outbox);
        return new KnowledgeCommandService(
                jdbcTemplate,
                mock(WorkspaceQueryPort.class),
                actor,
                mock(KnowledgeVersionService.class),
                mutation,
                mock(KnowledgeCitationReadGate.class),
                outbox);
    }

    private void insertPage(String itemId, String versionId, String title, String content) {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status, latest_version_id, updated_at
                ) values (?, 'ws', 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, current_timestamp)
                """, itemId, title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary)
                values (?, ?, 1, ?, '')
                """, versionId, itemId, content);
    }

    private int count(String itemId, String operation, String status) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from wiki_index_outbox
                where item_id = ? and operation = ? and status = ?
                """, Integer.class, itemId, operation, status);
        return count == null ? 0 : count;
    }

    private String status(String itemId, String operation) {
        return jdbcTemplate.queryForObject("""
                select status from wiki_index_outbox
                where item_id = ? and operation = ?
                order by created_at desc, id desc
                limit 1
                """, String.class, itemId, operation);
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
                    created_at timestamp default current_timestamp
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
                create table knowledge_version_citation(
                    knowledge_version_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
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
                create table wiki_index_outbox(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_id varchar(36) not null,
                    version_id varchar(36) null,
                    operation varchar(16) not null,
                    status varchar(16) not null,
                    attempts int not null default 0,
                    last_error varchar(500) null,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp
                )
                """);
    }

    private static final class RecordingIndex implements WikiPageIndex {
        private final List<Document> upserts = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();
        private boolean available = true;
        private boolean accept = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean upsert(Document document) {
            if (!accept) {
                return false;
            }
            upserts.add(document);
            return true;
        }

        @Override
        public boolean delete(String workspaceId, String itemId) {
            if (!accept) {
                return false;
            }
            deletes.add(workspaceId + ":" + itemId);
            return true;
        }

        @Override
        public SearchResult keywordSearch(String workspaceId, String query, WikiLexicalBoost boost, int limit) {
            return SearchResult.ready(List.of());
        }

        @Override
        public SearchResult vectorSearch(String workspaceId, List<Float> vector, int limit) {
            return SearchResult.ready(List.of());
        }
    }
}
