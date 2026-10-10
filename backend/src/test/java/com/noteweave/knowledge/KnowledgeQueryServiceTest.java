package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import com.noteweave.workspace.WorkspaceQueryPort;
import com.noteweave.research.ResearchGeneratedSourceReadGate;

class KnowledgeQueryServiceTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeQueryService queryService;
    private WorkspaceQueryPort workspaceQueryPort;
    private KnowledgeGovernanceService governanceService;
    private WikiPageVersionCache pageVersionCache;
    private ResearchGeneratedSourceReadGate generatedSourceGate;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        createSchema(database);
        seedData();
        workspaceQueryPort = mock(WorkspaceQueryPort.class);
        governanceService = mock(KnowledgeGovernanceService.class);
        pageVersionCache = mock(WikiPageVersionCache.class);
        generatedSourceGate = mock(ResearchGeneratedSourceReadGate.class);
        when(generatedSourceGate.visible(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        when(generatedSourceGate.readableSourceIds(eq("workspace"), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(1)));
        when(pageVersionCache.get(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(workspaceQueryPort.exists("workspace")).thenReturn(true);
        when(governanceService.getWikiStats("workspace")).thenReturn(
                new WikiStatsResponse(
                        "workspace", 2, 2, 2, 0, 2, 0, 0, 0,
                        Map.of("OVERVIEW", 1, "TOPIC", 1),
                        List.of(), List.of(), 0, true));
        when(governanceService.lintWiki("workspace")).thenReturn(List.of());
        queryService = new KnowledgeQueryService(
                jdbcTemplate,
                new KnowledgeWikiSearchEngine(jdbcTemplate),
                workspaceQueryPort,
                governanceService,
                pageVersionCache,
                new SimpleMeterRegistry(),
                new KnowledgeCitationReadGate(jdbcTemplate, generatedSourceGate),
                generatedSourceGate);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void titleAndSummaryMatchesOutrankBodyOnlyMatchesAndNoMatchReturnsNothing() {
        List<KnowledgePageHit> ranked = queryService.findRelevantWikiPages(
                "workspace", "alpha beta");

        assertThat(ranked).extracting(KnowledgePageHit::itemId)
                .containsExactly("item-a", "item-b");
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
        assertThat(queryService.searchWikiPages("workspace", "alpha beta"))
                .extracting(KnowledgeItemResponse::itemId)
                .containsExactly("item-a", "item-b");

        // 没有页面命中时不再用最近更新的页面充当证据
        assertThat(queryService.findRelevantWikiPages("workspace", "no-match-token")).isEmpty();
    }

    @Test
    void chineseQuestionsMatchPagesByBigramsAndLinkedPagesFollowStrongHits() {
        insertWikiPage("item-cache", "version-cache", "缓存一致性方案",
                "写请求先更新数据库，再删除缓存；删除失败时写入消息队列重试。", "先更新数据库再删缓存");
        insertWikiPage("item-binlog", "version-binlog", "Binlog 订阅",
                "通过 Canal 订阅 MySQL 变更日志，由独立消费者异步处理。", "Canal 变更订阅");
        insertWikiPage("item-unrelated", "version-unrelated", "前端路由设计",
                "页面路由按工作台划分，懒加载各个视图。", "路由与懒加载");
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    workspace_id, source_item_id, target_item_id, target_title, relation_type,
                    relation_status, mention_count, updated_at
                ) values ('workspace', 'item-cache', 'item-binlog', 'Binlog 订阅', 'WIKI_LINK', 'RESOLVED', 1,
                          timestamp '2026-07-14 12:00:00')
                """);

        List<KnowledgePageHit> hits = queryService.findRelevantWikiPages("workspace", "缓存一致性怎么保证？");

        assertThat(hits).extracting(KnowledgePageHit::itemId)
                .containsExactly("item-cache", "item-binlog");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
        // 只零星共享一个词（Canal）的问题覆盖度不够，不算命中
        assertThat(queryService.findRelevantWikiPages("workspace", "Canal 与量子计算的发展前景和未来展望")).isEmpty();
    }

    private void insertWikiPage(String itemId, String versionId, String title, String content, String summary) {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status, latest_version_id, updated_at
                ) values (?, 'workspace', 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, timestamp '2026-07-15 10:00:00')
                """, itemId, title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id, created_at
                ) values (?, ?, 1, ?, ?, null, timestamp '2026-07-15 10:00:00')
                """, versionId, itemId, content, summary);
    }

    @Test
    void queryFilteringShouldHappenBeforeTheRecentPageLimit() {
        for (int index = 0; index < 121; index++) {
            String itemId = "noise-item-" + index;
            String versionId = "noise-version-" + index;
            jdbcTemplate.update("""
                    insert into knowledge_item(
                        id, workspace_id, item_type, page_kind, title, status, latest_version_id, updated_at
                    ) values (?, 'workspace', 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, timestamp '2026-07-15 10:00:00')
                    """, itemId, "Unrelated page " + index, versionId);
            jdbcTemplate.update("""
                    insert into knowledge_version(
                        id, item_id, version_no, content, summary, source_message_id, created_at
                    ) values (?, ?, 1, 'unrelated content', 'unrelated summary', null,
                              timestamp '2026-07-15 10:00:00')
                    """, versionId, itemId);
        }

        assertThat(queryService.searchWikiPages("workspace", "alpha architecture"))
                .extracting(KnowledgeItemResponse::itemId)
                .contains("item-a");
    }

    @Test
    void olderMatchingPagesMustRemainSearchableAfterWorkspaceGrowth() {
        for (int index = 0; index < 121; index++) {
            String itemId = "matching-item-" + index;
            String versionId = "matching-version-" + index;
            jdbcTemplate.update("""
                    insert into knowledge_item(
                        id, workspace_id, item_type, page_kind, title, status, latest_version_id, updated_at
                    ) values (?, 'workspace', 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, timestamp '2026-07-15 10:00:00')
                    """, itemId, "Architecture note " + index, versionId);
            jdbcTemplate.update("""
                    insert into knowledge_version(
                        id, item_id, version_no, content, summary, source_message_id, created_at
                    ) values (?, ?, 1, 'architecture guidance', 'architecture summary', null,
                              timestamp '2026-07-15 10:00:00')
                    """, versionId, itemId);
        }

        assertThat(queryService.searchWikiPages("workspace", "architecture"))
                .extracting(KnowledgeItemResponse::itemId)
                // 121 个新页面加上原有的 item-a；item-b 不含该词，但链接到 item-a，通过链接传播进入结果
                .hasSize(123)
                .contains("matching-item-0", "item-a", "item-b");
    }

    @Test
    void shouldLoadOneHopRelationsAndCitationOrderForAnswerRetrieval() {
        List<WikiPageContext> contexts = queryService.findRelevantWikiPageContexts(
                "workspace", "alpha beta");

        assertThat(contexts).extracting(context -> context.page().itemId())
                .containsExactly("item-a", "item-b");
        WikiPageContext alpha = contexts.get(0);
        assertThat(alpha.outgoingLinks())
                .extracting(WikiLinkResponse::targetItemId)
                .containsExactly("item-b");
        assertThat(alpha.backlinks())
                .extracting(WikiLinkResponse::sourceItemId)
                .containsExactly("item-b");
        assertThat(alpha.citations())
                .extracting(KnowledgeCitationResponse::citationId)
                .containsExactly("citation-a", "citation-b");
        assertThat(queryService.citationIdsForWikiPages("workspace", List.of(alpha.page())))
                .containsExactly("citation-a", "citation-b");
    }

    @Test
    void shouldOwnHomeIndexListDetailAndSourceBackedReadModels() {
        WikiHomeResponse home = queryService.getWikiHome("workspace");
        assertThat(home.wikiUrl()).isEqualTo("/workspaces/workspace/wiki");
        assertThat(home.pages()).extracting(KnowledgeItemResponse::itemId)
                .containsExactly("item-b", "item-a");
        assertThat(home.links()).hasSize(2);

        WikiIndexResponse index = queryService.getWikiIndex("workspace");
        assertThat(index.readySourceCount()).isEqualTo(1);
        assertThat(index.pageCount()).isEqualTo(2);
        assertThat(index.sourceBackedPageCount()).isEqualTo(1);
        assertThat(index.manualPageCount()).isEqualTo(1);
        assertThat(index.recentSources()).singleElement()
                .satisfies(source -> assertThat(source.recommendedAction())
                        .isEqualTo("REBUILD_WIKI"));

        KnowledgeItemDetailResponse detail = queryService.getItemDetail("item-a");
        assertThat(detail.latestVersionNo()).isEqualTo(2);
        assertThat(detail.citations())
                .extracting(KnowledgeCitationResponse::citationId)
                .containsExactly("citation-a", "citation-b");
        assertThat(detail.outgoingLinks()).singleElement()
                .satisfies(link -> assertThat(link.targetItemId()).isEqualTo("item-b"));
        assertThat(detail.backlinks()).singleElement()
                .satisfies(link -> assertThat(link.sourceItemId()).isEqualTo("item-b"));

        assertThat(queryService.findSourceBackedWikiItemIds("workspace", "source"))
                .containsExactly("item-a");
    }

    @Test
    void cachedVersionMustKeepItemStateAndLinksLive() {
        KnowledgeItemDetailResponse initial = queryService.getItemDetail("item-a");
        ArgumentCaptor<KnowledgePageVersionSnapshot> snapshot =
                ArgumentCaptor.forClass(KnowledgePageVersionSnapshot.class);
        verify(pageVersionCache).put(eq("workspace"), snapshot.capture());
        when(pageVersionCache.get("workspace", "item-a", "version-a"))
                .thenReturn(Optional.of(snapshot.getValue()));

        jdbcTemplate.update("""
                update knowledge_version set content = 'mutated content' where id = 'version-a'
                """);
        jdbcTemplate.update("""
                update knowledge_item_link set mention_count = 9
                where source_item_id = 'item-a' and target_item_id = 'item-b'
                """);

        KnowledgeItemDetailResponse cached = queryService.getItemDetail("item-a");
        assertThat(cached.content()).isEqualTo(initial.content());
        assertThat(cached.outgoingLinks()).singleElement()
                .satisfies(link -> assertThat(link.mentionCount()).isEqualTo(9));

        jdbcTemplate.update("update knowledge_item set status = 'DELETED' where id = 'item-a'");
        assertThatThrownBy(() -> queryService.getItemDetail("item-a"))
                .hasMessageContaining("知识对象不可读取");
    }

    @Test
    void revokedCitedSourceMustHideWikiPageEvenWhenVersionWasCached() {
        queryService.getItemDetail("item-a");
        List<KnowledgeItemResponse> recentPages = queryService.listItems("workspace", "WIKI");
        WikiStatsResponse baseStats = governanceService.getWikiStats("workspace");
        when(governanceService.getWikiStats("workspace")).thenReturn(new WikiStatsResponse(
                baseStats.workspaceId(), baseStats.pageCount(), baseStats.linkCount(),
                baseStats.resolvedLinkCount(), baseStats.unresolvedLinkCount(),
                baseStats.citationCount(), baseStats.issueCount(), baseStats.autoFixableIssueCount(),
                baseStats.manualReviewIssueCount(), baseStats.pagesByKind(), recentPages,
                List.of(new WikiTaskSummaryResponse("task", "WIKI_INGEST", "COMPLETED", "DONE", "",
                        "WIKI", "item-a", "Alpha Overview", List.of(), java.time.Instant.now())),
                baseStats.pendingTaskCount(), baseStats.wikiEnabled()));
        when(governanceService.lintWiki("workspace")).thenReturn(List.of(
                new WikiIssueResponse("CONTENT_STALE", "MEDIUM", "item-a", "Alpha Overview",
                        "stale", "rebuild", false, "REBUILD_WIKI")));
        ArgumentCaptor<KnowledgePageVersionSnapshot> snapshot =
                ArgumentCaptor.forClass(KnowledgePageVersionSnapshot.class);
        verify(pageVersionCache).put(eq("workspace"), snapshot.capture());
        when(pageVersionCache.get("workspace", "item-a", "version-a"))
                .thenReturn(Optional.of(snapshot.getValue()));
        when(generatedSourceGate.readableSourceIds(eq("workspace"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Set.of());

        assertThat(queryService.listItems("workspace", "WIKI"))
                .extracting(KnowledgeItemResponse::itemId).containsExactly("item-b");
        assertThat(queryService.searchWikiPages("workspace", "alpha"))
                .extracting(KnowledgeItemResponse::itemId).containsExactly("item-b");
        assertThat(queryService.findRelevantWikiPages("workspace", "alpha"))
                .extracting(KnowledgePageHit::itemId).containsExactly("item-b");
        assertThatThrownBy(() -> queryService.citationIdsForWikiPages("workspace", List.of(
                new KnowledgePageHit("item-a", "version-a", 2, "Alpha Overview",
                        "stale content", "stale summary", 1))))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_SOURCE_REVOKED"));
        assertThat(queryService.getWikiHome("workspace").links()).isEmpty();
        WikiIndexResponse index = queryService.getWikiIndex("workspace");
        assertThat(index.recentUpdates()).extracting(KnowledgeItemResponse::itemId)
                .containsExactly("item-b");
        assertThat(index.recentTasks()).isEmpty();
        assertThat(index.topIssues()).isEmpty();
        assertThatThrownBy(() -> queryService.getItemDetail("item-a"))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_SOURCE_REVOKED"));
    }

    @Test
    void wikiIndexMustHideRevokedResearchSourceTitle() {
        jdbcTemplate.update("""
                insert into source(id, workspace_id, title, status, index_status,
                                   generated_by, generated_ref_id, updated_at)
                values ('research-source', 'workspace', 'Revoked report', 'READY', 'INDEXED',
                        'research_agent', 'run', timestamp '2026-07-16 12:00:00')
                """);
        when(generatedSourceGate.visible("workspace", "research_agent", "run"))
                .thenReturn(false);

        assertThat(queryService.getWikiIndex("workspace").recentSources())
                .extracting(WikiIndexSourceResponse::sourceId).containsExactly("source");
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
        jdbc.execute("""
                create table citation(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    title varchar(300) not null,
                    quote_text varchar(1000) not null,
                    page_no int,
                    location_info varchar(300)
                )
                """);
        jdbc.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(300) not null,
                    status varchar(32) not null,
                    index_status varchar(32) not null,
                    generated_by varchar(64),
                    generated_ref_id varchar(128),
                    updated_at timestamp not null
                )
                """);
    }

    private void seedData() {
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status,
                    latest_version_id, updated_at
                ) values (?, ?, 'WIKI', 'OVERVIEW', ?, 'ACTIVE', ?, timestamp '2026-07-14 10:00:00')
                """, "item-a", "workspace", "Alpha Overview", "version-a");
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status,
                    latest_version_id, updated_at
                ) values (?, ?, 'WIKI', 'TOPIC', ?, 'ACTIVE', ?, timestamp '2026-07-14 11:00:00')
                """, "item-b", "workspace", "Beta Detail", "version-b");
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary,
                    source_message_id, created_at
                ) values (
                    'version-a', 'item-a', 2, 'alpha beta architecture',
                    'alpha beta summary', null, timestamp '2026-07-14 09:00:00'
                )
                """);
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary,
                    source_message_id, created_at
                ) values (
                    'version-b', 'item-b', 1, 'alpha beta detail', 'detail',
                    null, timestamp '2026-07-14 09:30:00'
                )
                """);
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    workspace_id, source_item_id, target_item_id, target_title, relation_type,
                    relation_status, mention_count, updated_at
                ) values ('workspace', 'item-a', 'item-b', 'Beta Detail', 'WIKI_LINK', 'RESOLVED', 2,
                          timestamp '2026-07-14 12:00:00')
                """);
        jdbcTemplate.update("""
                insert into knowledge_item_link(
                    workspace_id, source_item_id, target_item_id, target_title, relation_type,
                    relation_status, mention_count, updated_at
                ) values ('workspace', 'item-b', 'item-a', 'Alpha Overview', 'WIKI_LINK', 'RESOLVED', 1,
                          timestamp '2026-07-14 12:01:00')
                """);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, title, status, index_status,
                    generated_by, generated_ref_id, updated_at
                ) values (
                    'source', 'workspace', 'Source', 'READY', 'INDEXED',
                    '', '', timestamp '2026-07-14 12:30:00'
                )
                """);
        jdbcTemplate.update("""
                insert into citation(id, source_id, title, quote_text, page_no, location_info)
                values ('citation-a', 'source', 'Source A', 'quote a', 1, 'page:1')
                """);
        jdbcTemplate.update("""
                insert into citation(id, source_id, title, quote_text, page_no, location_info)
                values ('citation-b', 'source', 'Source B', 'quote b', 2, 'page:2')
                """);
        jdbcTemplate.update("""
                insert into knowledge_version_citation(knowledge_version_id, citation_id, sort_order)
                values ('version-a', 'citation-b', 2), ('version-a', 'citation-a', 1)
                """);
    }
}
