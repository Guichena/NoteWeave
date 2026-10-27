package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.AuditActorProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ANS-01 定向验证：收尾只用一条 CAS 判定「状态 + 流持有者 + 首版消息是否仍可收尾」，
 * 抢不到时按归属给出终态或 SUPERSEDED，绝不把运行留在中间态、也不把异常抛给流式调用方。
 */
class AnswerRunCompletionTest {

    private static final String WORKSPACE = "w";
    private static final String STREAM = "stream-1";

    private JdbcTemplate jdbc;
    private AnswerRunService service;
    private int messageSeq = 10;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:answer-complete-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id, owner_id, name, status) values ('w', 'local-user', '工作台', 'ACTIVE')");
        jdbc.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status)
                values ('conversation', 'w', '问答', 'QA', 'ACTIVE')
                """);
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content)
                values ('query', 'conversation', 'w', 1, 'USER', '问题')
                """);
        AuditActorProvider actor = mock(AuditActorProvider.class);
        when(actor.currentOrSystem("ANSWER")).thenReturn("system:test");
        service = new AnswerRunService(jdbc, actor, new SimpleMeterRegistry(), new ObjectMapper());
    }

    @Test
    void completeWritesTheFinalRevisionAndClosesTheRun() {
        fixture("run-1", "GENERATING", STREAM, "STREAMING");

        var outcome = service.complete(WORKSPACE, "run-1", STREAM, "最终答案", 42);

        assertThat(outcome).isEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);
        assertThat(column("select status from answer_run where id = 'run-1'")).isEqualTo("COMPLETED");
        assertThat(column("select stream_owner from answer_run where id = 'run-1'")).isNull();
        assertThat(column("select status from message_revision where answer_run_id = 'run-1'")).isEqualTo("FINAL");
        assertThat(column("select content from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("最终答案");
        assertThat(jdbc.queryForObject(
                "select output_tokens from message_revision where answer_run_id = 'run-1'", Integer.class))
                .isEqualTo(42);
        assertThat(eventTypes("run-1")).containsExactly("answer.status", "answer.completed");
    }

    @Test
    void completeIsIdempotentForAFinishedRun() {
        fixture("run-1", "GENERATING", STREAM, "STREAMING");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "第一次", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);
        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "第二次", 2))
                .isEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);

        // 第二次不再改写已定稿的正文，也不再重复发终态事件
        assertThat(column("select content from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("第一次");
        assertThat(eventTypes("run-1")).containsExactly("answer.status", "answer.completed");
    }

    @Test
    void completeReportsCancellationWhenCancelWonTheRace() {
        fixture("run-1", "GENERATING", STREAM, "STREAMING");
        jdbc.update("update answer_run set status = 'CANCELLED' where id = 'run-1'");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "迟到的答案", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.CANCELLED);
        assertThat(column("select status from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("STREAMING");
        assertThat(eventTypes("run-1")).isEmpty();
    }

    @Test
    void completeReportsFailureWhenFailureWonTheRace() {
        fixture("run-1", "GENERATING", STREAM, "STREAMING");
        jdbc.update("update answer_run set status = 'FAILED' where id = 'run-1'");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "迟到的答案", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.FAILED);
        assertThat(column("select status from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("STREAMING");
    }

    /** 重连后另一个流持有租约：本流只报 SUPERSEDED，不碰状态、不发事件。 */
    @Test
    void completeIsSupersededWhenAnotherStreamOwnsTheLease() {
        fixture("run-1", "GENERATING", "stream-2", "STREAMING");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "答案", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.SUPERSEDED);
        assertThat(column("select status from answer_run where id = 'run-1'")).isEqualTo("GENERATING");
        assertThat(column("select stream_owner from answer_run where id = 'run-1'")).isEqualTo("stream-2");
        assertThat(column("select status from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("STREAMING");
        assertThat(eventTypes("run-1")).isEmpty();
    }

    /** 首版消息已经不在 STREAMING：显式失败，别把运行永远留在 GENERATING。 */
    @Test
    void completeFailsTheRunWhenTheRevisionIsNoLongerStreaming() {
        fixture("run-1", "GENERATING", STREAM, "PARTIAL");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "答案", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.FAILED);
        assertThat(column("select status from answer_run where id = 'run-1'")).isEqualTo("FAILED");
        assertThat(column("select error_code from answer_run where id = 'run-1'"))
                .isEqualTo("ANSWER_MESSAGE_REVISION_INVALID");
        assertThat(column("select status from message_revision where answer_run_id = 'run-1'"))
                .isEqualTo("PARTIAL");
        assertThat(eventTypes("run-1")).containsExactly("answer.failed");
    }

    /** 运行已经不在可收尾状态（还没开始生成）：同样显式失败，而不是抛 409 让调用方悬挂。 */
    @Test
    void completeFailsTheRunWhenItIsNotFinalizableYet() {
        fixture("run-1", "RETRIEVING", STREAM, "STREAMING");

        assertThat(service.complete(WORKSPACE, "run-1", STREAM, "答案", 1))
                .isEqualTo(AnswerRunService.CompletionOutcome.FAILED);
        assertThat(column("select status from answer_run where id = 'run-1'")).isEqualTo("FAILED");
        assertThat(column("select error_code from answer_run where id = 'run-1'"))
                .isEqualTo("ANSWER_RUN_NOT_FINALIZABLE");
    }

    private void fixture(String runId, String status, String streamOwner, String revisionStatus) {
        String answerMessageId = "answer-" + runId;
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content)
                values (?, 'conversation', 'w', ?, 'ASSISTANT', '')
                """, answerMessageId, messageSeq++);
        jdbc.update("""
                insert into answer_run(id, workspace_id, conversation_id, mode, status, query_message_id,
                    answer_message_id, assistant_request_id, stream_owner, version, created_by, updated_by)
                values (?, 'w', 'conversation', 'QA', ?, 'query', ?, ?, ?, 0, 'system:test', 'system:test')
                """, runId, status, answerMessageId, "request-" + runId, streamOwner);
        jdbc.update("""
                insert into message_revision(id, workspace_id, answer_run_id, message_id, revision_no, status,
                    content, created_by)
                values (?, 'w', ?, ?, 1, ?, '', 'system:test')
                """, "revision-" + runId, runId, answerMessageId, revisionStatus);
    }

    private String column(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private java.util.List<String> eventTypes(String runId) {
        return jdbc.queryForList(
                "select event_type from answer_event where answer_run_id = ? order by seq", String.class, runId);
    }
}
