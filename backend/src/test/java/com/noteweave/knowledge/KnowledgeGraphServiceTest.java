package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class KnowledgeGraphServiceTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeWikiSearchEngine searchEngine;
    private WorkspaceQueryPort workspaceQueryPort;
    private KnowledgeGraphService graphService;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        createSchema();
        seedEdges();
        searchEngine = mock(KnowledgeWikiSearchEngine.class);
        workspaceQueryPort = mock(WorkspaceQueryPort.class);
        when(workspaceQueryPort.exists("workspace")).thenReturn(true);
        when(searchEngine.loadRows("workspace")).thenReturn(rows());
        graphService = new KnowledgeGraphService(
                jdbcTemplate, workspaceQueryPort, searchEngine);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void shouldPreserveOverviewKindFilterAndNormalizedLimit() {
        WikiGraphResponse filtered = graphService.getWikiGraph(
                "workspace", "overview", "", 9, 200, List.of(" concept "));

        assertThat(filtered.nodes()).extracting(WikiGraphNode::itemId)
                .containsExactly("item-c");
        assertThat(filtered.edges()).isEmpty();
        assertThat(filtered.meta().mode()).isEqualTo("overview");
        assertThat(filtered.meta().depth()).isEqualTo(3);
        assertThat(filtered.meta().totalNodes()).isEqualTo(1);
        assertThat(filtered.meta().returnedNodes()).isEqualTo(1);
        assertThat(filtered.meta().truncated()).isFalse();

        WikiGraphResponse limited = graphService.getWikiGraph(
                "workspace", "overview", "", 0, 1, List.of());
        assertThat(limited.nodes()).hasSize(4);
        assertThat(limited.meta().depth()).isEqualTo(1);
        assertThat(limited.meta().totalNodes()).isEqualTo(5);
        assertThat(limited.meta().truncated()).isTrue();
    }

    @Test
    void shouldPreserveEgoBreadthFirstTraversalDepthAndEdgeCropping() {
        WikiGraphResponse depthOne = graphService.getWikiGraph(
                "workspace", "ego", "item-a", 1, 4, List.of());

        assertThat(depthOne.nodes()).extracting(WikiGraphNode::itemId)
                .containsExactly("item-a", "item-b", "item-e");
        assertThat(depthOne.nodes()).extracting(WikiGraphNode::itemId)
                .doesNotContain("item-c", "item-d");
        assertThat(depthOne.edges())
                .extracting(edge -> edge.sourceItemId() + "->" + edge.targetItemId())
                .containsExactly("item-a->item-b", "item-a->item-e");

        WikiGraphResponse depthTwo = graphService.getWikiGraph(
                "workspace", "ego", "item-a", 2, 4, List.of());
        assertThat(depthTwo.nodes()).extracting(WikiGraphNode::itemId)
                .containsExactly("item-a", "item-b", "item-e", "item-c")
                .doesNotContain("item-d");
        assertThat(depthTwo.meta().truncated()).isTrue();
    }

    @Test
    void shouldFallbackInvalidEgoCenterAndRejectMissingWorkspace() {
        WikiGraphResponse fallback = graphService.getWikiGraph(
                "workspace", "ego", "missing", 2, 4, List.of());

        assertThat(fallback.meta().mode()).isEqualTo("overview");
        assertThat(fallback.meta().centerItemId()).isEmpty();
        assertThat(fallback.meta().depth()).isEqualTo(1);
        assertThat(fallback.nodes()).hasSize(4);

        assertThatThrownBy(() -> graphService.getWikiGraph(
                "missing-workspace", "overview", "", 1, 24, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo("WORKSPACE_NOT_FOUND"));
    }

    private List<WikiSearchRow> rows() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return List.of(
                row("item-a", "Alpha", "TOPIC", 4, 0, 2, now),
                row("item-b", "Beta", "TOPIC", 3, 1, 1, now),
                row("item-c", "Gamma", "CONCEPT", 2, 2, 3, now),
                row("item-d", "Delta", "TOPIC", 1, 0, 1, now),
                row("item-e", "Epsilon", "OVERVIEW", 2, 0, 1, now)
        );
    }

    private WikiSearchRow row(
            String itemId,
            String title,
            String pageKind,
            int outgoing,
            int backlinks,
            int citations,
            Instant updatedAt
    ) {
        return new WikiSearchRow(
                itemId, title, pageKind, "version-" + itemId, 1,
                "content", "summary", updatedAt,
                outgoing, backlinks, citations, 0);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    title varchar(300) not null
                )
                """);
        jdbcTemplate.execute("""
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
    }

    private void seedEdges() {
        for (String[] item : List.of(
                new String[] {"item-a", "Alpha"},
                new String[] {"item-b", "Beta"},
                new String[] {"item-c", "Gamma"},
                new String[] {"item-d", "Delta"},
                new String[] {"item-e", "Epsilon"})) {
            jdbcTemplate.update("insert into knowledge_item(id, title) values (?, ?)", item[0], item[1]);
        }
        insertEdge("item-a", "item-b", "Beta", 5, "2026-07-14 12:05:00");
        insertEdge("item-a", "item-e", "Epsilon", 4, "2026-07-14 12:04:00");
        insertEdge("item-b", "item-c", "Gamma", 3, "2026-07-14 12:03:00");
        insertEdge("item-c", "item-d", "Delta", 2, "2026-07-14 12:02:00");
    }

    private void insertEdge(
            String sourceId,
            String targetId,
            String targetTitle,
            int mentions,
            String updatedAt
    ) {
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    workspace_id, source_item_id, target_item_id, target_title,
                    relation_type, relation_status, mention_count, updated_at
                ) values ('workspace', ?, ?, ?, 'WIKI_LINK', 'RESOLVED', ?, ?)
                """, sourceId, targetId, targetTitle, mentions, updatedAt);
    }
}
