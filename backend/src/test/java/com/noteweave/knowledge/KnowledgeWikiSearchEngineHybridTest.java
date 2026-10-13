package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class KnowledgeWikiSearchEngineHybridTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private FakeWikiIndex index;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        createSchema(database);
        insertPage("item-a", "version-a", "Alpha Overview", "alpha beta architecture", "alpha beta summary");
        insertPage("item-b", "version-b", "Beta Detail", "alpha beta detail", "detail");
        index = new FakeWikiIndex();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void unavailableIndexUsesLexicalRankingAndRecordsOneReason() {
        index.available = false;
        KnowledgeWikiSearchEngine engine = engine(null, null);

        List<WikiSearchRow> rows = engine.search("workspace", "alpha beta");

        assertThat(rows).extracting(WikiSearchRow::itemId).containsExactly("item-a", "item-b");
        assertThat(engine.consumeDegradationReasons())
                .containsExactly(WikiHybridRanker.INDEX_UNAVAILABLE);
        assertThat(index.keywordCalls).isZero();
    }

    @Test
    void emptyIndexDoesNotFallBackToRecentPages() {
        index.keyword = WikiPageIndex.SearchResult.ready(List.of());
        KnowledgeWikiSearchEngine engine = engine(null, null);

        assertThat(engine.search("workspace", "alpha")).isEmpty();
        assertThat(engine.consumeDegradationReasons())
                .containsExactly(WikiHybridRanker.EMBEDDING_UNAVAILABLE);
    }

    @Test
    void hybridUsesIndexCountsAndSkipsLinkPropagation() {
        insertPage("item-c", "version-c", "Linked Only", "unrelated", "unrelated");
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    workspace_id, source_item_id, target_item_id, target_title, relation_type,
                    relation_status, mention_count, updated_at
                ) values ('workspace', 'item-a', 'item-c', 'Linked Only', 'WIKI_LINK', 'RESOLVED', 1,
                          timestamp '2026-07-14 12:00:00')
                """);
        index.keyword = WikiPageIndex.SearchResult.ready(List.of(
                new WikiPageIndex.Hit("item-b", 9.0d, 3, 1, 7, 2),
                new WikiPageIndex.Hit("item-a", 1.0d, 0, 4, 1, 0)));
        KnowledgeWikiSearchEngine engine = engine(null, null);

        List<WikiSearchRow> rows = engine.search("workspace", "alpha");

        assertThat(rows).extracting(WikiSearchRow::itemId).containsExactly("item-b", "item-a");
        assertThat(rows.get(0).citationCount()).isEqualTo(7);
        assertThat(rows.get(0).outgoingCount()).isEqualTo(3);
        assertThat(rows.get(0).unresolvedCount()).isEqualTo(2);
        assertThat(engine.consumeDegradationReasons()).containsExactly(
                WikiHybridRanker.EMBEDDING_UNAVAILABLE,
                WikiHybridRanker.RERANK_UNAVAILABLE);
    }

    @Test
    void rerankReordersHybridHits() {
        index.keyword = WikiPageIndex.SearchResult.ready(List.of(
                new WikiPageIndex.Hit("item-b", 1.0d, 0, 0, 0, 0),
                new WikiPageIndex.Hit("item-a", 1.0d, 0, 0, 0, 0)));
        KnowledgeWikiSearchEngine engine = engine(null, new RerankClient() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(
                        new Hit(1, 0.9d, 1),
                        new Hit(0, 0.2d, 2)), "fake");
            }
        });

        List<KnowledgePageHit> hits = engine.findRelevantPages("workspace", "alpha");

        assertThat(hits).extracting(KnowledgePageHit::itemId).containsExactly("item-a", "item-b");
        assertThat(hits).extracting(KnowledgePageHit::score).containsExactly(90, 20);
        assertThat(engine.consumeDegradationReasons())
                .containsExactly(WikiHybridRanker.EMBEDDING_UNAVAILABLE);
    }

    @Test
    void keywordChannelFailureFallsBackWithoutExtraReasons() {
        index.keyword = WikiPageIndex.SearchResult.unavailable();
        KnowledgeWikiSearchEngine engine = engine(null, null);

        assertThat(engine.search("workspace", "alpha beta"))
                .extracting(WikiSearchRow::itemId)
                .containsExactly("item-a", "item-b");
        assertThat(engine.consumeDegradationReasons())
                .containsExactly(WikiHybridRanker.INDEX_UNAVAILABLE);
    }

    @Test
    void vectorFailureKeepsLexicalChannel() {
        index.keyword = WikiPageIndex.SearchResult.ready(List.of(
                new WikiPageIndex.Hit("item-a", 1.0d, 0, 0, 0, 0)));
        index.vector = WikiPageIndex.SearchResult.unavailable();
        EmbeddingClient embedding = new EmbeddingClient() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public EmbeddingResult embedQuery(String text) {
                return new EmbeddingResult(List.of(List.of(0.1f, 0.2f)), "fake", 2, 1);
            }

            @Override
            public EmbeddingResult embedDocuments(List<String> texts) {
                return embedQuery(texts.get(0));
            }
        };
        KnowledgeWikiSearchEngine engine = engine(embedding, null);

        assertThat(engine.search("workspace", "当前问题：缓存一致性\n主题锚点：Canal"))
                .extracting(WikiSearchRow::itemId)
                .containsExactly("item-a");
        assertThat(index.lastKeywordQuery).isEqualTo("缓存一致性 Canal");
        assertThat(engine.consumeDegradationReasons()).contains(
                WikiHybridRanker.EMBEDDING_UNAVAILABLE,
                WikiHybridRanker.RERANK_UNAVAILABLE);
    }

    @Test
    void blankQueryDoesNotRecordDegradation() {
        KnowledgeWikiSearchEngine engine = engine(null, null);

        assertThat(engine.search("workspace", "  ")).hasSize(2);
        assertThat(engine.consumeDegradationReasons()).isEmpty();
        assertThat(index.keywordCalls).isZero();
    }

    private KnowledgeWikiSearchEngine engine(EmbeddingClient embedding, RerankClient rerank) {
        return new KnowledgeWikiSearchEngine(jdbcTemplate, index, embedding, rerank);
    }

    private void insertPage(String itemId, String versionId, String title, String content, String summary) {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status, latest_version_id, updated_at
                ) values (?, 'workspace', 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, timestamp '2026-07-14 10:00:00')
                """, itemId, title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id, created_at
                ) values (?, ?, 1, ?, ?, null, timestamp '2026-07-14 10:00:00')
                """, versionId, itemId, content, summary);
    }

    private void createSchema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_type varchar(32) not null,
                    page_kind varchar(32),
                    title varchar(300) not null,
                    status varchar(32) not null,
                    latest_version_id varchar(36),
                    updated_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table knowledge_version(
                    id varchar(36) primary key,
                    item_id varchar(36) not null,
                    version_no int not null,
                    content clob not null,
                    summary varchar(1000),
                    source_message_id varchar(36),
                    created_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table knowledge_item_link(
                    workspace_id varchar(36) not null,
                    source_item_id varchar(36) not null,
                    target_item_id varchar(36),
                    target_title varchar(300) not null,
                    relation_type varchar(32) not null,
                    relation_status varchar(32) not null,
                    mention_count int not null,
                    updated_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table knowledge_version_citation(
                    knowledge_version_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);
    }

    private static final class FakeWikiIndex implements WikiPageIndex {
        private boolean available = true;
        private SearchResult keyword = SearchResult.ready(List.of());
        private SearchResult vector = SearchResult.ready(List.of());
        private int keywordCalls;
        private String lastKeywordQuery;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean upsert(Document document) {
            return true;
        }

        @Override
        public boolean delete(String workspaceId, String itemId) {
            return true;
        }

        @Override
        public SearchResult keywordSearch(String workspaceId, String query, WikiLexicalBoost boost, int limit) {
            keywordCalls++;
            lastKeywordQuery = query;
            return keyword;
        }

        @Override
        public SearchResult vectorSearch(String workspaceId, List<Float> vector, int limit) {
            return this.vector;
        }
    }
}
