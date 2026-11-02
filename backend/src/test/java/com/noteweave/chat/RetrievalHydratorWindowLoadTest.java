package com.noteweave.chat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * FA-02 定向验证：窗口两阶段装载在真实 H2 schema 上执行真实 SQL。
 * source 表按真实 DDL 没有 source_id 列——连接关系若再引用不存在的列会直接失败。
 * 覆盖文末词法命中、语义命中优先、公平配额、快照过期排除、字节预算与超限丢弃。
 */
class RetrievalHydratorWindowLoadTest {

    private static final String WS = "ws-1";

    private final JdbcTemplate jdbc = jdbcTemplate();
    private final RetrievalHydrator hydrator = new RetrievalHydrator(jdbc);

    @org.junit.jupiter.api.Test
    void realSqlExecutesOnRealSchema() {
        insertSourceWithWindows("src-basic", 3, "普通内容");
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS, List.of("src-basic"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults());
        org.junit.jupiter.api.Assertions.assertEquals(3, index.windows().size());
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, index.windows(), RetrievalHydrator.WindowBodyBudget.defaults());
        org.junit.jupiter.api.Assertions.assertEquals(3, bodies.contentById().size());
    }

    @org.junit.jupiter.api.Test
    void tailWindowReachableByLexicalHit() {
        insertSourceRow("src-long");
        // 60 个窗口，只有最后一个包含答案词
        for (int i = 1; i <= 59; i++) {
            insertWindow("src-long", i, i, "背景材料第" + i + "段，没有答案。");
        }
        insertWindow("src-long", 60, 1, "唯一结论：量子隧穿效应是目标答案。");
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS, List.of("src-long"), List.of(), List.of("量子隧穿"),
                RetrievalHydrator.WindowIndexBudget.defaults());
        boolean tailSelected = index.windows().stream()
                .anyMatch(identity -> identity.chunkNo() == 60);
        org.junit.jupiter.api.Assertions.assertTrue(tailSelected,
                "词法命中的文末窗口必须进入候选身份池");
        org.junit.jupiter.api.Assertions.assertTrue(index.lexicalAnchorCount() > 0);
        org.junit.jupiter.api.Assertions.assertEquals(0, index.semanticAnchorCount());
    }

    @org.junit.jupiter.api.Test
    void semanticChunkAnchorSelectsTailWindows() {
        insertSourceRow("src-sem");
        insertWindow("src-sem", 1, 1, "开头");
        insertWindow("src-sem", 2, 1, "中间");
        insertWindow("src-sem", 3, 1, "文末命中段");
        String tailChunkId = jdbc.queryForObject(
                "select c.id from source_chunk c where c.source_id = ? and c.chunk_no = 3",
                String.class, "src-sem");
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS, List.of("src-sem"), List.of(tailChunkId), List.of(),
                new RetrievalHydrator.WindowIndexBudget(24, 384, 0, 0, 0));
        org.junit.jupiter.api.Assertions.assertEquals(1, index.windows().size());
        org.junit.jupiter.api.Assertions.assertEquals(3, index.windows().get(0).chunkNo());
        org.junit.jupiter.api.Assertions.assertEquals(1, index.semanticAnchorCount());
    }

    @org.junit.jupiter.api.Test
    void fairAllocationAcrossSources() {
        insertSourceRow("src-a");
        insertSourceRow("src-b");
        // src-a 有 20 个词法命中；src-b 只有 1 个；两者都必须进入身份池
        for (int i = 1; i <= 20; i++) {
            insertWindow("src-a", i, 1, "稀有词出现在第" + i + "段");
        }
        insertWindow("src-b", 1, 1, "稀有词仅一次");
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS, List.of("src-a", "src-b"), List.of(), List.of("稀有词"),
                RetrievalHydrator.WindowIndexBudget.defaults());
        boolean hasB = index.windows().stream().anyMatch(identity ->
                "src-b".equals(identity.sourceId()));
        org.junit.jupiter.api.Assertions.assertTrue(hasB, "配额轮转不能让命中多的来源挤占其他来源");
    }

    @org.junit.jupiter.api.Test
    void staleSnapshotAndRevokedSourceExcluded() {
        // src-stale：chunk 挂在旧版本快照上，最新 INDEXED 快照是 v2
        insertSourceRow("src-stale");
        jdbc.update("insert into source_snapshot (id, source_id, version_no, index_status) "
                + "values ('snap-v1', 'src-stale', 1, 'INDEXED')");
        jdbc.update("insert into source_snapshot (id, source_id, version_no, index_status) "
                + "values ('snap-v2', 'src-stale', 2, 'INDEXED')");
        jdbc.update("insert into source_chunk (id, workspace_id, source_id, source_snapshot_id, chunk_no, "
                + "heading, projection_status) values ('chk-stale', ?, 'src-stale', 'snap-v1', 1, '', 'PROJECTED')",
                WS);
        jdbc.update("insert into source_window (id, source_chunk_id, window_no, content) "
                + "values ('win-stale', 'chk-stale', 1, '旧快照内容')");
        // src-revoked：来源已删除
        insertSourceWithWindows("src-revoked", 2, "已删除来源内容");
        jdbc.update("update source set status = 'DELETED' where id = 'src-revoked'");

        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS, List.of("src-stale", "src-revoked"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults());
        org.junit.jupiter.api.Assertions.assertTrue(index.windows().isEmpty(),
                "过期快照与已删除来源的窗口不得装载");
    }

    @org.junit.jupiter.api.Test
    void byteBudgetDropsByPriorityAndCounts() {
        insertSourceRow("src-budget");
        insertWindow("src-budget", 1, 1, "优先装载的短窗口");
        insertWindow("src-budget", 2, 1, "次优先窗口");
        List<RetrievalHydrator.WindowIdentity> identities = hydrator.loadWindowIndex(
                WS, List.of("src-budget"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults()).windows();
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, identities, new RetrievalHydrator.WindowBodyBudget(30, 64 * 1024, 96));
        org.junit.jupiter.api.Assertions.assertEquals(1, bodies.contentById().size());
        RetrievalHydrator.WindowLoadUsage usage = hydrator.lastWindowLoadUsage();
        org.junit.jupiter.api.Assertions.assertTrue(usage.byteTruncated());
        org.junit.jupiter.api.Assertions.assertEquals(1, usage.droppedByBudget());
    }

    @org.junit.jupiter.api.Test
    void oversizedWindowDroppedNotTruncated() {
        insertSourceRow("src-oversize");
        insertWindow("src-oversize", 1, 1, "x".repeat(200));
        List<RetrievalHydrator.WindowIdentity> identities = hydrator.loadWindowIndex(
                WS, List.of("src-oversize"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults()).windows();
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, identities, new RetrievalHydrator.WindowBodyBudget(2_000_000, 100, 96));
        org.junit.jupiter.api.Assertions.assertTrue(bodies.contentById().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, hydrator.lastWindowLoadUsage().droppedOversized());
    }

    @org.junit.jupiter.api.Test
    void utf8ByteBudgetCountsNonAsciiContent() {
        insertSourceRow("src-cjk");
        insertWindow("src-cjk", 1, 1, "中文内容" + "😀".repeat(10));
        List<RetrievalHydrator.WindowIdentity> identities = hydrator.loadWindowIndex(
                WS, List.of("src-cjk"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults()).windows();
        hydrator.hydrateWindowBodies(WS, identities,
                new RetrievalHydrator.WindowBodyBudget(2_000_000, 64 * 1024, 96));
        long loadedBytes = hydrator.lastWindowLoadUsage().loadedBytes();
        // 4 个汉字 12 字节 + 10 个 emoji 各 4 字节 = 52 字节；按字符计数只会得到 14
        org.junit.jupiter.api.Assertions.assertEquals(52, loadedBytes,
                "字节预算必须按 UTF-8 实际字节数计数");
    }

    @org.junit.jupiter.api.Test
    void legacyHeadOnlyEntryStillWorks() {
        insertSourceWithWindows("src-legacy", 5, "兼容入口内容");
        Map<String, List<NoteRetrievalService.ReadingWindow>> windows =
                hydrator.hydrateNoteWindows(WS, List.of("src-legacy"));
        org.junit.jupiter.api.Assertions.assertEquals(5, windows.get("src-legacy").size());
    }

    @Test
    void higherRelevanceWindowWinsTightBodyBudget() {
        insertSourceRow("src-rank");
        for (int i = 1; i <= 5; i++) {
            insertWindow("src-rank", i, 1, "内容第" + i + "段");
        }
        String head = "chk-src-rank-1";
        String tail = "chk-src-rank-5";
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS,
                List.of(new RetrievalHydrator.SourceScope("src-rank", "snap-src-rank")),
                List.of(head, tail),
                Map.of(head, 0.1d, tail, 0.9d),
                List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults());
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, index.windows(), new RetrievalHydrator.WindowBodyBudget(2_000_000, 64 * 1024, 1));
        // 预算只够一个窗口时，靠后但语义相关性更高的窗口必须优先留下
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("win-src-rank-5"), List.copyOf(bodies.contentById().keySet()));
    }

    @Test
    void bodyBudgetFollowsCallerOrderNotDatabaseRowOrder() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        RetrievalHydrator reading = new RetrievalHydrator(jdbc);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.jdbc.core.RowCallbackHandler callback = invocation.getArgument(1);
            java.sql.ResultSet laterRow = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(laterRow.getString("id")).thenReturn("win-b");
            org.mockito.Mockito.when(laterRow.getString("content")).thenReturn("bbbb");
            java.sql.ResultSet earlierRow = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(earlierRow.getString("id")).thenReturn("win-a");
            org.mockito.Mockito.when(earlierRow.getString("content")).thenReturn("aaaaaa");
            // SQL 故意先返回调用方顺序靠后的行
            callback.processRow(laterRow);
            callback.processRow(earlierRow);
            return null;
        }).when(jdbc).query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class),
                org.mockito.ArgumentMatchers.any(Object[].class));
        List<RetrievalHydrator.WindowIdentity> identities = List.of(
                new RetrievalHydrator.WindowIdentity("win-a", "source-a", "snapshot-a", "chunk-a", 1,
                        "h", "t", "", "", 1, "loc", "head-fallback"),
                new RetrievalHydrator.WindowIdentity("win-b", "source-a", "snapshot-a", "chunk-b", 2,
                        "h", "t", "", "", 1, "loc", "head-fallback"));
        RetrievalHydrator.BodyLoadResult bodies = reading.hydrateWindowBodies(
                WS, identities, new RetrievalHydrator.WindowBodyBudget(2_000_000, 64 * 1024, 1));
        // 预算按调用方给出的身份顺序消费：行序靠前的 win-b 不能挤掉 win-a
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("win-a"), List.copyOf(bodies.contentById().keySet()));
    }

    @Test
    void blankWindowBodyIsDroppedAndNotCounted() {
        insertSourceRow("src-blank");
        insertWindow("src-blank", 1, 1, " ");
        insertWindow("src-blank", 2, 1, "真实内容");
        List<RetrievalHydrator.WindowIdentity> identities = hydrator.loadWindowIndex(
                WS, List.of("src-blank"), List.of(), List.of(),
                RetrievalHydrator.WindowIndexBudget.defaults()).windows();
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, identities, RetrievalHydrator.WindowBodyBudget.defaults());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("win-src-blank-2"), List.copyOf(bodies.contentById().keySet()));
        org.junit.jupiter.api.Assertions.assertEquals(1, hydrator.lastWindowLoadUsage().droppedEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, hydrator.lastWindowLoadUsage().loadedWindows());
    }

    @Test
    void roundRobinConsumesBodyBudgetAcrossSources() {
        insertSourceWithWindows("src-rr-a", 3, "共同内容");
        insertSourceWithWindows("src-rr-b", 3, "共同内容");
        RetrievalHydrator.WindowIndexResult index = hydrator.loadWindowIndex(
                WS,
                List.of(new RetrievalHydrator.SourceScope("src-rr-a", "snap-src-rr-a"),
                        new RetrievalHydrator.SourceScope("src-rr-b", "snap-src-rr-b")),
                List.of(), Map.of(), List.of(), RetrievalHydrator.WindowIndexBudget.defaults());
        RetrievalHydrator.BodyLoadResult bodies = hydrator.hydrateWindowBodies(
                WS, index.windows(), new RetrievalHydrator.WindowBodyBudget(2_000_000, 64 * 1024, 2));
        // 跨来源轮询：预算只够两条时必须一个来源一条，不能被第一个来源占满
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("win-src-rr-a-1", "win-src-rr-b-1"),
                List.copyOf(bodies.contentById().keySet()));
    }

    @Test
    void recalledSnapshotChangedDropsSourceInsteadOfMixingWindows() {
        insertSourceRow("src-pin");
        insertWindow("src-pin", 1, 1, "旧快照内容");
        jdbc.update("insert into source_snapshot (id, source_id, version_no, index_status) "
                + "values ('snap-src-pin-v2', 'src-pin', 2, 'INDEXED')");
        jdbc.update("insert into source_chunk (id, workspace_id, source_id, source_snapshot_id, chunk_no, "
                + "heading, projection_status) values ('chk-pin-2', ?, 'src-pin', 'snap-src-pin-v2', 1, '', 'PROJECTED')",
                WS);
        jdbc.update("insert into source_window (id, source_chunk_id, window_no, content) "
                + "values ('win-pin-2', 'chk-pin-2', 1, '新快照内容')");

        RetrievalHydrator.WindowIndexResult stale = hydrator.loadWindowIndex(
                WS, List.of(new RetrievalHydrator.SourceScope("src-pin", "snap-src-pin")),
                List.of(), Map.of(), List.of(), RetrievalHydrator.WindowIndexBudget.defaults());
        // 召回快照已变更：整源退出，不能改用新快照窗口
        org.junit.jupiter.api.Assertions.assertTrue(stale.windows().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, stale.droppedSnapshotSources());

        RetrievalHydrator.WindowIndexResult current = hydrator.loadWindowIndex(
                WS, List.of(new RetrievalHydrator.SourceScope("src-pin", "snap-src-pin-v2")),
                List.of(), Map.of(), List.of(), RetrievalHydrator.WindowIndexBudget.defaults());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("chk-pin-2"),
                current.windows().stream().map(RetrievalHydrator.WindowIdentity::chunkId).toList());
    }

    // === 测试基础设施 ===

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retrieval-hydrator-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        // source 表按真实 DDL 不含 source_id 列
        template.execute("""
                create table source (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null default 'READY',
                    index_status varchar(32) not null default 'INDEXED',
                    title varchar(512),
                    generated_by varchar(64),
                    generated_ref_id varchar(64)
                )
                """);
        template.execute("""
                create table source_snapshot (
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no bigint not null,
                    index_status varchar(32) not null default 'INDEXED'
                )
                """);
        template.execute("""
                create table source_chunk (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    source_snapshot_id varchar(36) not null,
                    chunk_no int not null,
                    heading varchar(512),
                    location_info varchar(512),
                    projection_status varchar(32) not null default 'PROJECTED'
                )
                """);
        template.execute("""
                create table source_window (
                    id varchar(36) primary key,
                    source_chunk_id varchar(36) not null,
                    window_no int not null,
                    content text,
                    location_info varchar(512)
                )
                """);
        return template;
    }

    private void insertSourceWithWindows(String sourceId, int windowCount, String content) {
        insertSourceRow(sourceId);
        for (int i = 1; i <= windowCount; i++) {
            insertWindow(sourceId, i, 1, content + " 第" + i + "节");
        }
    }

    private void insertSourceRow(String sourceId) {
        jdbc.update("insert into source (id, workspace_id, status, index_status, title) "
                + "values (?, ?, 'READY', 'INDEXED', ?)", sourceId, WS, sourceId + " 标题");
        jdbc.update("insert into source_snapshot (id, source_id, version_no, index_status) "
                + "values (?, ?, 1, 'INDEXED')", "snap-" + sourceId, sourceId);
    }

    private void insertWindow(String sourceId, int chunkNo, int windowNo, String content) {
        String chunkId = "chk-" + sourceId + "-" + chunkNo;
        int updated = jdbc.update("""
                insert into source_chunk (id, workspace_id, source_id, source_snapshot_id, chunk_no,
                        heading, projection_status)
                values (?, ?, ?, ?, ?, '', 'PROJECTED')
                """, chunkId, WS, sourceId, "snap-" + sourceId, chunkNo);
        if (updated == 0) {
            throw new IllegalStateException("chunk insert failed");
        }
        jdbc.update("insert into source_window (id, source_chunk_id, window_no, content) "
                + "values (?, ?, ?, ?)", "win-" + sourceId + "-" + chunkNo, chunkId, windowNo, content);
    }
}
