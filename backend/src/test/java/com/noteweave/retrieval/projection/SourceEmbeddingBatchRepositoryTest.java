package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.Ids;
import com.noteweave.source.SourceTopicNamesFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class SourceEmbeddingBatchRepositoryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SourceEmbeddingBatchRepository repository;

    @ParameterizedTest
    @ValueSource(ints = {64, 65, 129})
    void initializationSplitsIntoBoundedBatchesAndOnlyLastCompletionQueuesIndex(int count) {
        Fixture fixture = fixture(count);
        List<SourceEmbeddingBatchRepository.Batch> rows = initialize(fixture);
        assertThat(rows).hasSize((count + 63) / 64);
        assertThat(rows.stream().mapToInt(SourceEmbeddingBatchRepository.Batch::chunkCount).sum()).isEqualTo(count);
        assertThat(rows).allSatisfy(row -> assertThat(row.chunkCount()).isBetween(1, 64));
        initialize(fixture);
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            var claimed = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), i, rows.get(0).batchVersion());
            assertThat(repository.complete(claimed, "batch-" + i, "a".repeat(64), ignored -> "manifest.json")).isEqualTo(i == 0);
            assertThat(repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), i, rows.get(0).batchVersion())).isNull();
        }
        assertThat(outbox(fixture, "noteweave.source.index")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select processing_stage from source_snapshot where id = ?", String.class, fixture.snapshot())).isEqualTo("INDEXING");
    }

    @Test
    void retryKeepsRealCallLedgerAndStopsBeforeThirdCall() {
        Fixture fixture = fixture(1);
        var first = initialize(fixture).get(0);
        var claim = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, first.batchVersion());
        repository.beforeModelCall(claim, 1);
        repository.release(claim);
        var retry = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, first.batchVersion());
        repository.beforeModelCall(retry, 1);
        assertThatThrownBy(() -> repository.beforeModelCall(retry, 1)).isInstanceOf(com.noteweave.common.BusinessException.class)
                .extracting(failure -> ((com.noteweave.common.BusinessException) failure).code()).isEqualTo("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED");
        assertThat(repository.list(fixture.workspace(), fixture.source(), fixture.snapshot(), first.batchVersion()).get(0).modelCalls()).isEqualTo(2);
    }

    @Test
    void expiredLeaseIsRequeuedAndLateOwnerCannotCallOrCommit() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        var first = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        jdbc.update("update task_outbox set status = 'SENT' where task_id = ?", fixture.task());
        jdbc.update("update source_embedding_batch set lease_until = timestamp '2020-01-01 00:00:00' where id = ?", row.id());
        repository.recoverExpiredLeases();
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
        var second = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(() -> repository.beforeModelCall(first, 1)).hasMessageContaining("LEASE_LOST");
        assertThatThrownBy(() -> repository.complete(first, "late.bin", "a".repeat(64), ignored -> "manifest.json"))
                .hasMessageContaining("LEASE_LOST");
        assertThat(repository.acceptsFailure(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion())).isFalse();
    }

    @Test
    void newTaskKeepsCompletedBatchAndInvalidatesRunningOwner() {
        Fixture fixture = fixture(65);
        var rows = initialize(fixture);
        var done = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, rows.get(0).batchVersion());
        repository.complete(done, "done.bin", "a".repeat(64), ignored -> "manifest.json");
        var running = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 1, rows.get(0).batchVersion());
        repository.beforeModelCall(running, 1);
        String nextTask = task(fixture.workspace(), fixture.source());
        jdbc.update("update task set task_status = 'FAILED' where id = ?", fixture.task());
        jdbc.update("update source_snapshot set embedding_task_id = ? where id = ?", nextTask, fixture.snapshot());
        Fixture retry = new Fixture(fixture.workspace(), fixture.source(), fixture.snapshot(), nextTask);
        var next = initialize(retry);
        assertThat(next.get(0).status()).isEqualTo("COMPLETED");
        assertThat(next.get(0).objectKey()).isEqualTo("done.bin");
        assertThat(next.get(1).modelCalls()).isEqualTo(1);
        assertThat(next.get(1).leaseToken()).isNull();
        assertThatThrownBy(() -> repository.beforeModelCall(running, 1)).hasMessageContaining("STALE");
        assertThat(repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 1, rows.get(0).batchVersion())).isNull();
    }

    @Test
    void workspaceLatestSnapshotAndFrozenVersionRejectStaleMessages() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        assertThat(repository.claim("another-workspace", fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion())).isNull();
        var changed = repository.initialize(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(),
                "new-model:3", "chunk-v1", "context-v1", (count, note) -> 1).get(0);
        assertThat(changed.batchVersion()).isNotEqualTo(row.batchVersion());
        assertThat(repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion())).isNull();
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                select ?, source_id, file_object_id, 2, object_key, sha256, 'PENDING', 'PENDING' from source_snapshot where id = ?
                """, Ids.newId(), fixture.snapshot());
        assertThat(repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, changed.batchVersion())).isNull();
    }

    @Test
    void sourceMetadataChangeRejectsLateResultAndProducesANewFrozenPlan() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        var claim = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        jdbc.update("update source set title = '新标题' where id = ?", fixture.source());
        assertThatThrownBy(() -> repository.complete(claim, "late.bin", "a".repeat(64), ignored -> "manifest.json"))
                .hasMessageContaining("元数据已变化");
        var next = initialize(fixture).get(0);
        assertThat(next.batchVersion()).isNotEqualTo(row.batchVersion());
        assertThat(repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion())).isNull();
    }

    @Test
    void changedChunkCannotUseFrozenPlanOrStartAnotherCall() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        jdbc.update("update source_chunk set content = '已改变' where source_snapshot_id = ?", fixture.snapshot());
        assertThatThrownBy(() -> repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion()))
                .hasMessageContaining("片段已变化");
    }

    @Test
    void cancelledTaskCannotCommitAfterModelReturns() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        var claim = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        jdbc.update("update task set task_status = 'CANCELLED' where id = ?", fixture.task());
        assertThatThrownBy(() -> repository.complete(claim, "late.bin", "a".repeat(64), ignored -> "manifest.json")).hasMessageContaining("STALE");
        assertThat(outbox(fixture, "noteweave.source.index")).isZero();
    }

    @Test
    void parallelCompletionsCannotQueueIndexTwice() throws Exception {
        Fixture fixture = fixture(65);
        var rows = initialize(fixture);
        var first = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, rows.get(0).batchVersion());
        var second = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 1, rows.get(0).batchVersion());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> repository.complete(first, "first.bin", "a".repeat(64), ignored -> "manifest.json"));
            var b = executor.submit(() -> repository.complete(second, "second.bin", "b".repeat(64), ignored -> "manifest.json"));
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(false, true);
            assertThat(outbox(fixture, "noteweave.source.index")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    /** 部署侧覆盖资料流水线主题名时，批次投递必须跟随配置，否则调度器不认领、流水线停在向量化阶段。 */
    @Test
    void customTopicNamesAreUsedForBatchAndIndexOutbox() {
        Fixture fixture = fixture(65);
        SourceEmbeddingBatchRepository custom = new SourceEmbeddingBatchRepository(jdbc, transactions,
                SourceTopicNamesFixture.custom("acme.parse", "acme.chunk", "acme.embed", "acme.index",
                        "acme.projection"));
        List<SourceEmbeddingBatchRepository.Batch> rows = initialize(custom, fixture);
        assertThat(rows).hasSize(2);
        assertThat(outbox(fixture, "acme.embed")).isEqualTo(2);
        assertThat(outbox(fixture, "noteweave.source.embed")).isZero();
        var expired = custom.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, rows.get(0).batchVersion());
        jdbc.update("update task_outbox set status = 'SENT' where task_id = ?", fixture.task());
        jdbc.update("update source_embedding_batch set lease_until = timestamp '2020-01-01 00:00:00' where id = ?", expired.id());
        custom.recoverExpiredLeases();
        assertThat(outbox(fixture, "acme.embed")).isEqualTo(3);
        assertThat(outbox(fixture, "noteweave.source.embed")).isZero();
        assertThatThrownBy(() -> custom.complete(expired, "late.bin", "a".repeat(64), ignored -> "manifest.json"))
                .hasMessageContaining("LEASE_LOST");
        for (int i = rows.size() - 1; i >= 0; i--) {
            var claimed = custom.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), i, rows.get(0).batchVersion());
            assertThat(custom.complete(claimed, "batch-" + i, "a".repeat(64), ignored -> "manifest.json")).isEqualTo(i == 0);
        }
        assertThat(outbox(fixture, "acme.index")).isEqualTo(1);
        assertThat(outbox(fixture, "noteweave.source.index")).isZero();
    }

    @Test
    void renewalUsesDatabaseClockAndExpiredOrReplacedTokenCannotRenewReleaseOrSpend() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        var first = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        jdbc.update("update source_embedding_batch set lease_until = timestampadd(second, 30, current_timestamp) where id = ?", row.id());
        repository.renew(first);
        assertThat(repository.list(fixture.workspace(), fixture.source(), fixture.snapshot(), row.batchVersion()).get(0).leaseUntil())
                .isAfter(first.leaseUntil().minusSeconds(2));
        jdbc.update("update source_embedding_batch set lease_until = timestamp '2020-01-01 00:00:00' where id = ?", row.id());
        assertThatThrownBy(() -> repository.renew(first)).hasMessageContaining("LEASE_LOST");
        repository.release(first);
        assertThat(repository.list(fixture.workspace(), fixture.source(), fixture.snapshot(), row.batchVersion()).get(0).status()).isEqualTo("RUNNING");
        var second = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        assertThatThrownBy(() -> repository.renew(first)).hasMessageContaining("LEASE_LOST");
        assertThatThrownBy(() -> repository.beforeEmbeddingRequest(first)).hasMessageContaining("LEASE_LOST");
        repository.release(first);
        assertThat(repository.list(fixture.workspace(), fixture.source(), fixture.snapshot(), row.batchVersion()).get(0).leaseToken()).isEqualTo(second.leaseToken());
    }

    @Test
    void embeddingBudgetIsAtomicAndSurvivesClaimsAndNewTask() throws Exception {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        var claim = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        jdbc.update("update source_embedding_batch set embedding_request_budget = 1 where id = ?", row.id());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> spend = () -> {
            start.await();
            try { repository.beforeEmbeddingRequest(claim); return true; }
            catch (com.noteweave.retrieval.provider.RetrievalProviderException exhausted) { return false; }
        };
        try {
            var a = executor.submit(spend); var b = executor.submit(spend); start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        repository.release(claim);
        var retry = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        assertThatThrownBy(() -> repository.beforeEmbeddingRequest(retry)).hasMessageContaining("预算已耗尽");
        String newTask = task(fixture.workspace(), fixture.source());
        jdbc.update("update task set task_status='FAILED' where id=?", fixture.task());
        jdbc.update("update source_snapshot set embedding_task_id=? where id=?", newTask, fixture.snapshot());
        initialize(new Fixture(fixture.workspace(), fixture.source(), fixture.snapshot(), newTask));
        assertThat(jdbc.queryForObject("select embedding_requests_used from source_embedding_batch where id=?", Integer.class, row.id())).isEqualTo(1);
    }

    @Test
    void historicalUnknownBudgetNeverPretendsZeroOrSendsAgain() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        jdbc.update("update source_embedding_batch set embedding_requests_used=null where id=?", row.id());
        var claim = repository.claim(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(), 0, row.batchVersion());
        assertThatThrownBy(() -> repository.beforeEmbeddingRequest(claim))
                .isInstanceOf(com.noteweave.retrieval.provider.RetrievalProviderException.class)
                .extracting(ex -> ((com.noteweave.retrieval.provider.RetrievalProviderException) ex).errorCode())
                .isEqualTo("SOURCE_EMBEDDING_BUDGET_LEGACY_UNKNOWN");
        assertThat(jdbc.queryForObject("select embedding_requests_used from source_embedding_batch where id=?", Integer.class, row.id())).isNull();
    }

    @Test
    void readyRecoverySuppressesInflightButSentDoesNotPreventRedeliveryAndConcurrentScansDeduplicate() throws Exception {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        makeDue(row.id());
        repository.recoverExpiredLeases();
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(1);
        jdbc.update("update task_outbox set status='SENT' where task_id=?", fixture.task());
        makeDue(row.id());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(repository::recoverExpiredLeases);
            var b = executor.submit(repository::recoverExpiredLeases);
            a.get(); b.get();
        } finally { executor.shutdownNow(); }
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
        repository.recoverExpiredLeases();
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
    }

    @Test
    void oldVersionCannotRecoverOrBlockNewVersionInflight() {
        Fixture fixture = fixture(1);
        var old = initialize(fixture).get(0);
        var changed = repository.initialize(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(),
                "new-model:3", "chunk-v1", "context-v1", (count, note) -> 1).get(0);
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
        makeDue(old.id());
        repository.recoverExpiredLeases();
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
        assertThat(changed.batchVersion()).isNotEqualTo(old.batchVersion());
    }

    @Test
    void invalidOldVersionsDoNotStarveCurrentReadyAndRecoveryRollsBackWithOutbox() {
        Fixture fixture = fixture(1);
        var row = initialize(fixture).get(0);
        for (int i = 0; i < 101; i++) {
            jdbc.update("""
                    insert into source_embedding_batch(id, workspace_id, source_id, source_snapshot_id, task_id,
                        batch_version, embedding_version, chunker_version, context_prompt_version, batch_no,
                        chunk_offset, chunk_count, total_chunks, total_batches, chunk_digest, source_metadata_digest,
                        embedding_request_budget, status, lease_token, lease_until, updated_at)
                    select ?, workspace_id, source_id, source_snapshot_id, task_id, ?, embedding_version,
                        chunker_version, context_prompt_version, batch_no, chunk_offset, chunk_count, total_chunks,
                        total_batches, chunk_digest, source_metadata_digest, embedding_request_budget,
                        'RUNNING', 'obsolete', timestamp '2020-01-01 00:00:00', timestamp '2020-01-01 00:00:00'
                    from source_embedding_batch where id=?
                    """, Ids.newId(), String.format("%064x", i), row.id());
        }
        jdbc.update("update task_outbox set status='SENT' where task_id=?", fixture.task());
        makeDue(row.id());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.recoverExpiredLeases();
            assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(1);
        repository.recoverExpiredLeases();
        assertThat(outbox(fixture, "noteweave.source.embed")).isEqualTo(2);
    }

    private void makeDue(String batchId) {
        jdbc.update("update source_embedding_batch set next_recovery_at=timestamp '2020-01-01 00:00:00' where id=?", batchId);
    }

    private List<SourceEmbeddingBatchRepository.Batch> initialize(Fixture fixture) {
        return initialize(repository, fixture);
    }
    private List<SourceEmbeddingBatchRepository.Batch> initialize(SourceEmbeddingBatchRepository target, Fixture fixture) {
        return target.initialize(fixture.workspace(), fixture.source(), fixture.snapshot(), fixture.task(),
                "model:3", "chunk-v1", "context-v1", (count, note) -> (count + 7) / 8 + (note ? 1 : 0));
    }
    private int outbox(Fixture fixture, String topic) {
        return jdbc.queryForObject("select count(*) from task_outbox where task_id = ? and topic = ?", Integer.class, fixture.task(), topic);
    }
    private String task(String workspace, String source) {
        String task = Ids.newId();
        jdbc.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'SOURCE_PARSE', 'RUNNING', 'SOURCE', ?)
                """, task, workspace, source);
        return task;
    }
    private Fixture fixture(int chunks) {
        String workspace = Ids.newId(), source = Ids.newId(), snapshot = Ids.newId(), file = Ids.newId();
        jdbc.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', '批次测试', 'ACTIVE')", workspace);
        jdbc.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, 1, 'text/plain')
                """, file, workspace, "original-" + file, "a".repeat(64));
        jdbc.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, '批次资料', 'DOCUMENT', 'PROCESSING', 'PARSED', 'INDEXING')
                """, source, workspace, file);
        String task = task(workspace, source);
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256,
                    parse_status, index_status, processing_stage, embedding_task_id)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'INDEXING', 'EMBEDDING', ?)
                """, snapshot, source, file, "original-" + file, "a".repeat(64), task);
        for (int index = 0; index < chunks; index++) jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
                values (?, ?, ?, ?, ?, ?, 2)
                """, Ids.newId(), workspace, source, snapshot, index, "片段 " + index);
        return new Fixture(workspace, source, snapshot, task);
    }
    private record Fixture(String workspace, String source, String snapshot, String task) { }
}
