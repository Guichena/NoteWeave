package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.source.SourceDeletedEvent;
import com.noteweave.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SourceDeletionCleanupListenerTest {

    @Test
    void shouldPersistAndCompleteCleanupTasksAfterCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("complete");
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        RetrievalProjectionWriter writer = mock(RetrievalProjectionWriter.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, provider, properties(true));
        SourceDeletedEvent event = new SourceDeletedEvent(
                "workspace-1", "source-1", java.util.List.of(
                        "workspace/workspace-1/file.txt",
                        "workspace/workspace-1/source/source-1/snapshot/1/original/file.txt"));

        listener.prepareCleanup(event);
        listener.cleanup(event);

        verify(writer).deleteSource("noteweave_qa_chunk_workspace-1", "source-1");
        verify(writer).deleteSource("noteweave_note_source_workspace-1", "source-1");
        verify(storage).delete("noteweave-source", "workspace/workspace-1/file.txt");
        verify(storage).delete("noteweave-source",
                "workspace/workspace-1/source/source-1/snapshot/1/original/file.txt");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_cleanup_task where status = 'COMPLETED'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                select min(length(object_key_sha256)) from source_cleanup_task
                where cleanup_type = 'SOURCE_OBJECT'
                """, Integer.class)).isEqualTo(64);
    }

    @Test
    void failedCleanupShouldRemainDurableAndRetryToCompletion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("retry");
        ObjectStorage storage = mock(ObjectStorage.class);
        doThrow(new IllegalStateException("storage unavailable"))
                .doNothing()
                .when(storage).delete("noteweave-source", "workspace/workspace-1/file.txt");
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, provider, properties(false));
        SourceDeletedEvent event = new SourceDeletedEvent(
                "workspace-1", "source-1", "workspace/workspace-1/file.txt");

        listener.prepareCleanup(event);
        listener.cleanup(event);

        assertThat(jdbcTemplate.queryForMap("select status, attempt_count, last_error from source_cleanup_task"))
                .containsEntry("STATUS", "READY")
                .containsEntry("ATTEMPT_COUNT", 1)
                .containsEntry("LAST_ERROR", "storage unavailable");
        jdbcTemplate.update("update source_cleanup_task set next_attempt_at = current_timestamp");

        listener.retryCleanupTasks();

        assertThat(jdbcTemplate.queryForMap("select status, attempt_count from source_cleanup_task"))
                .containsEntry("STATUS", "COMPLETED")
                .containsEntry("ATTEMPT_COUNT", 2);
        verify(storage, org.mockito.Mockito.times(2))
                .delete("noteweave-source", "workspace/workspace-1/file.txt");
    }

    @Test
    void exhaustedExpiredLeaseShouldDeadLetterWithoutSixthExecution() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("exhausted");
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, provider, properties(false));
        jdbcTemplate.update("""
                insert into source_cleanup_task(
                    id, workspace_id, source_id, cleanup_type, bucket_name, object_key,
                    object_key_sha256, status, attempt_count, lease_owner, lease_until
                ) values ('cleanup-1', 'workspace-1', 'source-1', 'SOURCE_OBJECT',
                    'noteweave-source', 'workspace/workspace-1/file.txt',
                    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                    'PROCESSING', 5, 'dead-worker', timestampadd(second, -1, current_timestamp))
                """);

        listener.retryCleanupTasks();

        assertThat(jdbcTemplate.queryForMap(
                "select status, attempt_count, dead_lettered_at from source_cleanup_task"))
                .containsEntry("STATUS", "DEAD_LETTER")
                .containsEntry("ATTEMPT_COUNT", 5)
                .doesNotContainEntry("DEAD_LETTERED_AT", null);
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @org.junit.jupiter.api.Test
    void portPathRecyclesSnapshotOwnedOriginalsInsteadOfSkippingThem() {
        // NR14：端口路径下快照独占原件没有 file_object 行——以 source_snapshot 登记为归属证明
        // 直接物理回收；共享原件走票据认领。此前快照原件被当未知对象跳过后任务仍标 COMPLETED。
        JdbcTemplate jdbcTemplate = jdbcTemplate("snapshot-port");
        jdbcTemplate.update("""
                insert into file_object(id, object_key, ref_count)
                values ('fo-1', 'workspace/workspace-1/file.txt', 0)
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, object_key)
                values ('snap-1', 'source-1',
                        'workspace/workspace-1/source/source-1/snapshot/1/original/file.txt')
                """);
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        RetrievalProjectionWriter writer = mock(RetrievalProjectionWriter.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        com.noteweave.source.storage.FileObjectLifecycleGateway gateway =
                mock(com.noteweave.source.storage.FileObjectLifecycleGateway.class);
        when(gateway.claimZeroReferenceDeletion(
                org.mockito.ArgumentMatchers.eq("workspace-1"), org.mockito.ArgumentMatchers.eq("fo-1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new com.noteweave.source.storage.FileObjectLifecyclePort.DeletionTicket(
                        "fo-1", "noteweave-source", "workspace/workspace-1/file.txt", 1L)));
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, provider, properties(true));
        listener.setFileObjectLifecycle(gateway);
        SourceDeletedEvent event = new SourceDeletedEvent(
                "workspace-1", "source-1", java.util.List.of(
                        "workspace/workspace-1/file.txt",
                        "workspace/workspace-1/source/source-1/snapshot/1/original/file.txt"));

        listener.prepareCleanup(event);
        listener.cleanup(event);

        verify(storage).delete("noteweave-source", "workspace/workspace-1/file.txt");
        verify(storage).delete("noteweave-source",
                "workspace/workspace-1/source/source-1/snapshot/1/original/file.txt");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_cleanup_task where status = 'COMPLETED'", Integer.class))
                .isEqualTo(4);
    }

    /**
     * DATA-02：归属不明的对象键不能当「清理完成」。
     * 此前直接 return，任务照样标 COMPLETED，COMPLIANCE 删除等于没删；现在必须走死信等人工核对。
     */
    @Test
    void untrackedObjectKeyDeadLettersInsteadOfCompletingSilently() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("untracked");
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalProjectionWriter> provider = mock(ObjectProvider.class);
        com.noteweave.source.storage.FileObjectLifecycleGateway gateway =
                mock(com.noteweave.source.storage.FileObjectLifecycleGateway.class);
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                jdbcTemplate, storage, provider, properties(false));
        listener.setFileObjectLifecycle(gateway);
        SourceDeletedEvent event = new SourceDeletedEvent(
                "workspace-1", "source-1", "workspace/workspace-1/untracked.bin");

        listener.prepareCleanup(event);
        listener.cleanup(event);
        for (int round = 0; round < 6; round++) {
            jdbcTemplate.update("update source_cleanup_task set next_attempt_at = current_timestamp");
            listener.retryCleanupTasks();
        }

        assertThat(jdbcTemplate.queryForMap("select status, attempt_count from source_cleanup_task"))
                .containsEntry("STATUS", "DEAD_LETTER")
                .containsEntry("ATTEMPT_COUNT", 5);
        assertThat(jdbcTemplate.queryForObject("select last_error from source_cleanup_task", String.class))
                .contains("untracked object key");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    private JdbcTemplate jdbcTemplate(String suffix) {        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-cleanup-" + suffix + "-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source_cleanup_task (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    cleanup_type varchar(32) not null,
                    projection_type varchar(32),
                    bucket_name varchar(128),
                    object_key varchar(1000),
                    object_key_sha256 char(64),
                    status varchar(32) not null,
                    attempt_count int not null default 0,
                    next_attempt_at timestamp,
                    lease_owner varchar(128),
                    lease_until timestamp,
                    last_error varchar(1000),
                    dead_lettered_at timestamp,
                    completed_at timestamp,
                    created_at timestamp not null default current_timestamp
                )
                """);
        // 共享原件引用检查需要 file_object 行；测试对象删除后 ref_count 已归零
        jdbcTemplate.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    object_key varchar(500) not null,
                    ref_count int not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    object_key varchar(500) not null
                )
                """);
        return jdbcTemplate;
    }

    private NoteWeaveProperties properties(boolean elasticsearchEnabled) {
        return new NoteWeaveProperties(
                null,
                null,
                null,
                null,
                new NoteWeaveProperties.Elasticsearch(
                        elasticsearchEnabled, "localhost", 9200, "http", "noteweave_chunk"),
                null
        );
    }
}
