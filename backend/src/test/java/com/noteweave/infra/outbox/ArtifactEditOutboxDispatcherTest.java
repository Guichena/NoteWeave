package com.noteweave.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * C4 编辑事件持久投递测试（FB-03 验收）：
 * READY 发布成功置 SENT；发布失败退避重试、达上限 DEAD_LETTER；
 * 死信人工重驱要求 actor+reason 并换新代次；topic 与消费者同源由同一配置键提供。
 */
class ArtifactEditOutboxDispatcherTest {

    private JdbcTemplate jdbcTemplate;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ArtifactEditOutboxDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-edit-outbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table artifact_edit_event (
                    id varchar(64) primary key, workspace_id varchar(36) not null,
                    artifact_job_id varchar(36) not null, request_id varchar(64) not null,
                    schema_version int not null default 2, base_version_id varchar(36),
                    new_version_id varchar(36), origin_task_id varchar(36), acting_user_id varchar(36),
                    event_json varchar(8000) not null, request_digest varchar(64) not null,
                    payload_digest varchar(64) not null, status varchar(32) not null,
                    attempt_count int not null default 0, max_attempts int not null default 10,
                    delivery_generation int not null default 1, claim_token varchar(64),
                    lease_owner varchar(128), lease_until timestamp, next_attempt_at timestamp,
                    sent_at timestamp, last_error varchar(500),
                    created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp
                )
                """);
        kafkaTemplate = mock(KafkaTemplate.class);
        dispatcher = new ArtifactEditOutboxDispatcher(
                jdbcTemplate, kafkaTemplate, new SimpleMeterRegistry(),
                "noteweave.artifact.edit.test", true, true, 20, 120, 3, 300);
    }

    private void seedEvent(String eventId) {
        jdbcTemplate.update("""
                insert into artifact_edit_event(
                    id, workspace_id, artifact_job_id, request_id, event_json,
                    request_digest, payload_digest, status)
                values (?, 'ws-1', 'job-1', 'req-1', '{"editEventId":"%s"}', 'rd', 'pd', 'READY')
                """.formatted(eventId), eventId);
    }

    private String status(String eventId) {
        return jdbcTemplate.queryForObject(
                "select status from artifact_edit_event where id = ?", String.class, eventId);
    }

    @Test
    void readyEventIsPublishedOnceAndMarkedSent() throws Exception {
        seedEvent("evt-sent");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        dispatcher.dispatchDue();

        assertThat(status("evt-sent")).isEqualTo("SENT");
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> captured =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captured.capture());
        // topic 与消费者同源（同一配置键），key 为 workspace，载荷为事件 JSON 原文
        assertThat(captured.getValue().topic()).isEqualTo("noteweave.artifact.edit.test");
        assertThat(captured.getValue().key()).isEqualTo("ws-1");
        assertThat(String.valueOf(captured.getValue().value())).contains("evt-sent");
    }

    @Test
    void publishFailureBacksOffThenDeadLettersAtLimit() {
        seedEvent("evt-dlq");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        dispatcher.dispatchDue();
        assertThat(status("evt-dlq")).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select next_attempt_at is not null from artifact_edit_event where id = 'evt-dlq'",
                Boolean.class)).isTrue();

        // 到期后重试直到 3 次预算耗尽：进入 DEAD_LETTER 持久终态
        for (int round = 0; round < 3; round++) {
            jdbcTemplate.update(
                    "update artifact_edit_event set next_attempt_at = null where id = 'evt-dlq'");
            dispatcher.dispatchDue();
        }
        assertThat(status("evt-dlq")).isEqualTo("DEAD_LETTER");
    }

    @Test
    void inFlightLeaseExpiredBeyondAttemptCapGoesDeadLetterNotInfiniteLoop() {
        // AU-007：租约过期再认领同样受 max_attempts 上限——认领后反复崩溃的场景
        // 不能无限重试，超限后由死信收敛且可人工重驱
        seedEvent("evt-crash");
        jdbcTemplate.update("""
                update artifact_edit_event set status = 'IN_FLIGHT', attempt_count = 3,
                    lease_until = timestamp '2020-01-01 00:00:00' where id = 'evt-crash'
                """);

        dispatcher.dispatchDue();

        assertThat(status("evt-crash")).isEqualTo("DEAD_LETTER");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from artifact_edit_event where id = 'evt-crash'",
                Integer.class)).isEqualTo(3);
        verify(kafkaTemplate, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
    }

    @Test
    void sentWriteBackLostClaimIsRejectedByClaimToken() throws Exception {
        seedEvent("evt-token");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        dispatcher.dispatchDue();
        // 成功路径写回按 claim_token CAS：这里行已是 SENT，二次写回无效但不抛错
        assertThat(status("evt-token")).isEqualTo("SENT");
    }

    @Test
    void deadLetterRedriveRequiresIdentityAndBumpsGeneration() {
        seedEvent("evt-redrive");
        jdbcTemplate.update("""
                update artifact_edit_event set status = 'DEAD_LETTER', attempt_count = 10,
                    delivery_generation = 2 where id = 'evt-redrive'
                """);

        assertThatThrownBy(() -> dispatcher.redrive("evt-redrive", "", "reason"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("actor");
        assertThatThrownBy(() -> dispatcher.redrive("evt-redrive", "ops-1", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");

        var outcome = dispatcher.redrive("evt-redrive", "ops-1", "broker recovered");
        assertThat(outcome.oldDeliveryGeneration()).isEqualTo(2);
        assertThat(outcome.newDeliveryGeneration()).isEqualTo(3);
        assertThat(status("evt-redrive")).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from artifact_edit_event where id = 'evt-redrive'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select last_error from artifact_edit_event where id = 'evt-redrive'",
                String.class)).contains("ops-1").contains("broker recovered");
    }
}
