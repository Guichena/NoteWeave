package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.BuildStatus;
import com.noteweave.source.SourceCatalogVersionService;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * IDX-04：重建改成后台任务后要证明三件事——请求线程不被占用、
 * 同一工作台不会并发重建、失败后锁能释放且别名不动。
 */
class RetrievalBackfillServiceRebuildTest {

    private JdbcTemplate jdbc;
    private RetrievalIndexBuildRepository builds;
    private RetrievalRebuildLockRepository locks;
    private SourceRetrievalProjectionService projection;
    private RetrievalIndexManager indexManager;
    private RetrievalBackfillSourceFinalizer finalizer;
    private SourceCatalogVersionService catalog;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retrieval-rebuild-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                insert into workspace(id, owner_id, name, status, wiki_enabled)
                values ('ws', 'local-user', '工作台', 'ACTIVE', true)
                """);
        builds = new RetrievalIndexBuildRepository(jdbc);
        locks = new RetrievalRebuildLockRepository(jdbc);
        projection = mock(SourceRetrievalProjectionService.class);
        indexManager = mock(RetrievalIndexManager.class);
        finalizer = mock(RetrievalBackfillSourceFinalizer.class);
        catalog = mock(SourceCatalogVersionService.class);
        when(catalog.current("ws")).thenReturn(1L);
    }

    @Test
    void rebuildRunsInTheBackgroundAndPagesThroughSources() {
        seedSource("source-a", 2);
        seedSource("source-b", 2);
        seedSource("source-c", 2);
        projectAs(2, 1);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor capture = queued::set;
        RetrievalBackfillService service = service(capture, 1);

        RetrievalBackfillService.BackfillResult result = service.startRebuild("ws");

        // 请求线程只拿到构建单号，真正的回填还排在队列里
        assertThat(queued.get()).isNotNull();
        assertThat(result.qaBuild().status()).isEqualTo(BuildStatus.BACKFILLING);
        assertThat(result.qaBuild().expectedCount()).isEqualTo(6);
        assertThat(result.noteBuild().expectedCount()).isEqualTo(3);
        verify(projection, never()).projectSnapshotToIndexes(anyString(), anyString(), anyString(),
                anyString(), anyString());

        queued.get().run();

        verify(projection, times(3)).projectSnapshotToIndexes(eq("ws"), anyString(), anyString(),
                anyString(), anyString());
        verify(finalizer, times(3)).markIndexed(eq("ws"), any());
        verify(indexManager).switchAliases(any());
        assertThat(builds.findById(result.qaBuild().id()).status()).isEqualTo(BuildStatus.COMPLETED);
        assertThat(builds.findById(result.noteBuild().id()).status()).isEqualTo(BuildStatus.COMPLETED);
        assertThat(locks.acquiredAt("ws")).isEmpty();
    }

    @Test
    void secondRebuildIsRejectedWhileOneIsRunning() {
        seedSource("source-a", 1);
        projectAs(1, 1);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RetrievalBackfillService service = service(queued::set, 10);
        service.startRebuild("ws");

        assertThatThrownBy(() -> service.startRebuild("ws"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code())
                        .isEqualTo("RETRIEVAL_REBUILD_IN_PROGRESS"));
    }

    @Test
    void failedRebuildKeepsTheAliasAndReleasesTheLock() {
        seedSource("source-a", 2);
        when(projection.effectiveProjectionEmbeddingVersion()).thenReturn("embedding-batch-v5:1024:test");
        org.mockito.Mockito.doThrow(new IllegalStateException("向量服务不可用"))
                .when(projection).projectSnapshotToIndexes(anyString(), anyString(), anyString(),
                        anyString(), anyString());
        RetrievalBackfillService service = service(Runnable::run, 10);

        RetrievalBackfillService.BackfillResult result = service.startRebuild("ws");

        assertThat(builds.findById(result.qaBuild().id()).status()).isEqualTo(BuildStatus.FAILED);
        assertThat(builds.findById(result.qaBuild().id()).lastErrorCode())
                .isEqualTo("RETRIEVAL_BACKFILL_INCOMPLETE");
        verify(indexManager, never()).switchAliases(any());
        // 锁必须释放，否则工作台会永久卡在「重建中」，只能人工清库
        assertThat(locks.acquiredAt("ws")).isEmpty();
    }

    @Test
    void rebuildReleasesTheLockWhenTheWorkspaceCatalogChanges() {
        seedSource("source-a", 1);
        projectAs(1, 1);
        // 第一次读 1，进入回填后目录版本变化，必须中止并释放锁
        when(catalog.current("ws")).thenReturn(1L, 2L, 2L, 2L);
        RetrievalBackfillService service = service(Runnable::run, 10);

        RetrievalBackfillService.BackfillResult result = service.startRebuild("ws");

        assertThat(builds.findById(result.qaBuild().id()).status()).isEqualTo(BuildStatus.FAILED);
        assertThat(builds.findById(result.qaBuild().id()).lastErrorCode())
                .isEqualTo("RETRIEVAL_BACKFILL_FAILED");
        verify(indexManager, never()).switchAliases(any());
        assertThat(locks.acquiredAt("ws")).isEmpty();
    }

    private RetrievalBackfillService service(Executor executor, int pageSize) {
        return new RetrievalBackfillService(jdbc,
                new NoteWeaveProperties(null, null, null, null, null, null, null, null),
                builds, projection, indexManager, catalog, finalizer, locks, executor, pageSize);
    }

    /** 模拟投影服务的副作用：按该快照的片段数写入 READY 的 QA 行，并写一条 NOTE 行。 */
    private void projectAs(int chunksPerSource, int noteRowsPerSource) {
        when(projection.effectiveProjectionEmbeddingVersion()).thenReturn("embedding-batch-v5:1024:test");
        org.mockito.Mockito.doAnswer(invocation -> {
            String sourceId = invocation.getArgument(1);
            String snapshotId = invocation.getArgument(2);
            String qaIndex = invocation.getArgument(3);
            String noteIndex = invocation.getArgument(4);
            for (int i = 0; i < chunksPerSource; i++) {
                insertProjection("QA_CHUNK", sourceId + "-qa-" + i, sourceId, snapshotId, qaIndex);
            }
            for (int i = 0; i < noteRowsPerSource; i++) {
                insertProjection("NOTE_SOURCE", sourceId + "-note-" + i, sourceId, snapshotId, noteIndex);
            }
            return null;
        }).when(projection).projectSnapshotToIndexes(eq("ws"), anyString(), anyString(),
                anyString(), anyString());
    }

    private void insertProjection(String type, String entityId, String sourceId, String snapshotId,
                                  String targetIndex) {
        jdbc.update("""
                insert into retrieval_projection(
                    id, workspace_id, projection_type, entity_id, source_id, source_snapshot_id,
                    content_hash, embedding_text_hash, embedding_provider, embedding_model,
                    embedding_dimensions, embedding_version, index_schema_version, target_index, status)
                values (?, 'ws', ?, ?, ?, ?, 'h', 'eh', 'openai-compatible', 'model', 1024,
                    'embedding-batch-v5:1024:test', 'schema', ?, 'READY')
                """, UUID.randomUUID().toString(), type, entityId, sourceId, snapshotId, targetIndex);
    }

    private void seedSource(String sourceId, int chunks) {
        String fileObjectId = "fo-" + sourceId;
        jdbc.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size)
                values (?, 'ws', ?, ?, 1)
                """, fileObjectId, "key/" + sourceId, "sha-" + sourceId);
        jdbc.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type,
                    status, parse_status, index_status)
                values (?, 'ws', ?, ?, 'FILE', 'READY', 'PARSED', 'PENDING')
                """, sourceId, fileObjectId, "资料 " + sourceId);
        String snapshotId = "snap-" + sourceId;
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key,
                    sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'PENDING')
                """, snapshotId, sourceId, fileObjectId, "key/" + sourceId, "sha-" + sourceId);
        for (int i = 0; i < chunks; i++) {
            jdbc.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id,
                        chunk_no, content, token_estimate)
                    values (?, 'ws', ?, ?, ?, ?, 10)
                    """, "chunk-" + sourceId + "-" + i, sourceId, snapshotId, i, "正文 " + i);
        }
    }
}
