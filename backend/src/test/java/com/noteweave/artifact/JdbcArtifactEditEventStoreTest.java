package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FA-04 定向验证：artifact_edit_event 窄表的 JDBC 存储在真实 H2 上执行。
 * 事件写入必须在活动事务内；(workspace, job, requestId) 唯一身份冲突抛出
 * DuplicateKeyException 由调用方事务回滚消化。
 */
class JdbcArtifactEditEventStoreTest {

    private final DriverManagerDataSource dataSource = dataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final JdbcArtifactEditEventStore store =
            new JdbcArtifactEditEventStore(jdbc, new ObjectMapper());

    @Test
    void insertRequiresActiveTransaction() {
        ArtifactEditEventStore.ArtifactEditEventRecord record = record("req-tx");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> store.insertEvent(record));
    }

    @Test
    void insertAndFindRoundTripInsideTransaction() {
        TransactionTemplate template = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        ArtifactEditEventStore.ArtifactEditEventRecord record = record("req-roundtrip");
        template.executeWithoutResult(status -> store.insertEvent(record));

        ArtifactEditEventStore.ArtifactEditEventRecord found =
                store.findByRequestId("ws-1", "job-1", "req-roundtrip");
        org.junit.jupiter.api.Assertions.assertNotNull(found);
        org.junit.jupiter.api.Assertions.assertEquals("req-roundtrip", found.requestId());
        org.junit.jupiter.api.Assertions.assertEquals("user-1", found.actingUserId());
        org.junit.jupiter.api.Assertions.assertEquals(2, found.changedSectionIds().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, found.changedFragments().size());
        org.junit.jupiter.api.Assertions.assertEquals("line-diff-v2", found.diffAlgorithm());
        org.junit.jupiter.api.Assertions.assertEquals("READY", jdbc.queryForObject(
                "select status from artifact_edit_event where id = ?",
                String.class, found.editEventId()));
        org.junit.jupiter.api.Assertions.assertTrue(jdbc.queryForObject(
                "select event_json from artifact_edit_event where id = ?",
                String.class, found.editEventId()).contains("actingUserId"));
        org.junit.jupiter.api.Assertions.assertNull(store.findByRequestId("ws-1", "job-1", "req-none"));
    }

    @Test
    void duplicateRequestIdRejectedByUniqueConstraint() {
        TransactionTemplate template = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        template.executeWithoutResult(status -> store.insertEvent(record("req-dup")));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> template.executeWithoutResult(status ->
                        store.insertEvent(record("req-dup"))));
    }

    private ArtifactEditEventStore.ArtifactEditEventRecord record(String requestId) {
        return new ArtifactEditEventStore.ArtifactEditEventRecord(
                UUID.randomUUID().toString(),
                "ws-1",
                "job-1",
                requestId,
                "digest-request",
                "ver-new",
                "ver-base",
                1,
                "task-origin",
                "user-1",
                "digest-before",
                "digest-after",
                List.of("s0-11111111", "s1-22222222"),
                List.of(new ArtifactVersionDiffV2.Fragment(
                        "s0-11111111", "before", "after", "bd", "ad", false, false, 6, 5)),
                42,
                1000,
                ArtifactVersionDiffV2.ALGORITHM,
                "{\"k\":\"v\"}",
                "digest-payload",
                "2026-10-24T00:00:00Z");
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-edit-store-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table artifact_edit_event(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    artifact_job_id varchar(36) not null,
                    request_id varchar(128) not null,
                    schema_version int not null,
                    base_version_id varchar(36) not null,
                    new_version_id varchar(36) not null,
                    origin_task_id varchar(36),
                    acting_user_id varchar(64) not null,
                    event_json text,
                    request_digest varchar(64) not null,
                    payload_digest varchar(64) not null,
                    status varchar(32) not null,
                    attempt_count int not null default 0,
                    next_attempt_at timestamp,
                    sent_at timestamp,
                    last_error varchar(1000),
                    created_at timestamp not null default current_timestamp,
                    constraint uk_artifact_edit_request unique (workspace_id, artifact_job_id, request_id)
                )
                """);
        return dataSource;
    }
}
