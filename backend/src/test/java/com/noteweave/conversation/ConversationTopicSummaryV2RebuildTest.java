package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.TaskService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 话题摘要排队只把 BUILDING（进行中）和 READY（已成功）当作“同一终点已存在”。
 * STALE 摘要的原文窗口没有变化但内容已作废，必须能在同一 segment/end_seq 上重建；
 * 由于 (segment_id, end_seq) 唯一，重建只能原地复用旧行。
 */
class ConversationTopicSummaryV2RebuildTest {

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String CONVERSATION_ID = "conversation-1";
    private static final String TOPIC_ID = "topic-1";
    private static final String SEGMENT_ID = "segment-1";
    private static final String STALE_DIGEST = "0".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TaskService tasks;
    private ConversationTopicSummaryV2Service service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:topic-summary-rebuild-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table conversation (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null
                )
                """);
        jdbc.execute("""
                create table conversation_message (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    message_seq int not null,
                    role varchar(16) not null,
                    content varchar(2000) not null,
                    context_status varchar(24) not null
                )
                """);
        jdbc.execute("""
                create table conversation_topic_segment_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    topic_id varchar(36) not null,
                    start_seq int not null,
                    end_seq int not null,
                    decision_status varchar(24) not null
                )
                """);
        // 与 V116 迁移保持一致：状态只有 BUILDING/READY/STALE，(segment_id, end_seq) 与 (segment_id, revision_no) 唯一
        jdbc.execute("""
                create table conversation_topic_summary_revision_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    topic_id varchar(36) not null,
                    segment_id varchar(36) not null,
                    revision_no int not null,
                    start_seq int not null,
                    end_seq int not null,
                    source_digest char(64) not null,
                    status varchar(24) not null,
                    summary_text clob not null,
                    content_hash char(64) null,
                    compiler_version varchar(64) not null,
                    created_at timestamp not null default current_timestamp,
                    ready_at timestamp null,
                    base_revision_id varchar(36) null,
                    summary_method varchar(32) null,
                    constraint ck_topic_summary_v2_status check (status in ('BUILDING', 'READY', 'STALE')),
                    constraint uq_topic_summary_v2_range unique (segment_id, end_seq),
                    constraint uq_topic_summary_v2_number unique (segment_id, revision_no)
                )
                """);
        jdbc.execute("""
                create table task_outbox (
                    id varchar(36) primary key,
                    task_id varchar(36) not null,
                    topic varchar(160) not null,
                    message_key varchar(160) not null,
                    payload_json clob not null,
                    status varchar(32) not null
                )
                """);
        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)",
                CONVERSATION_ID, WORKSPACE_ID);
        // 12 条消息：原文窗口只保留最近 8 条，摘要需要覆盖到 seq 4
        for (int seq = 1; seq <= 12; seq++) {
            jdbc.update("""
                    insert into conversation_message(
                        id, workspace_id, conversation_id, message_seq, role, content, context_status)
                    values (?, ?, ?, ?, ?, ?, 'CURRENT')
                    """, "message-" + seq, WORKSPACE_ID, CONVERSATION_ID, seq,
                    seq % 2 == 1 ? "USER" : "ASSISTANT", "第 " + seq + " 条消息");
        }
        jdbc.update("""
                insert into conversation_topic_segment_v2(
                    id, workspace_id, conversation_id, topic_id, start_seq, end_seq, decision_status)
                values (?, ?, ?, ?, 1, 4, 'CONFIDENT')
                """, SEGMENT_ID, WORKSPACE_ID, CONVERSATION_ID, TOPIC_ID);
        tasks = mock(TaskService.class);
        when(tasks.createTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("task-1");
        service = new ConversationTopicSummaryV2Service(jdbc, mapper, tasks);
    }

    @Test
    void staleSummaryAtTheSameEndSeqIsRebuiltInsteadOfSkipped() {
        insertRevision("revision-stale", 1, 4, "STALE", "", null);

        int queued = service.queueBuilds(WORKSPACE_ID, CONVERSATION_ID);

        assertThat(queued).isEqualTo(1);
        List<RevisionRow> rows = revisions();
        assertThat(rows).hasSize(1);
        RevisionRow rebuilt = rows.get(0);
        assertThat(rebuilt.id()).isEqualTo("revision-stale");
        assertThat(rebuilt.status()).isEqualTo("BUILDING");
        assertThat(rebuilt.revisionNo()).isEqualTo(1);
        assertThat(rebuilt.startSeq()).isEqualTo(1);
        assertThat(rebuilt.endSeq()).isEqualTo(4);
        assertThat(rebuilt.summaryText()).isEmpty();
        assertThat(rebuilt.contentHash()).isNull();
        assertThat(rebuilt.summaryMethod()).isNull();
        assertThat(rebuilt.readyAt()).isNull();
        // 来源哈希按当前原文重算，不再是作废时的占位值
        assertThat(rebuilt.sourceDigest()).hasSize(64).isNotEqualTo(STALE_DIGEST);
        verify(tasks).createTask(WORKSPACE_ID, "CONVERSATION_SUMMARY",
                "TOPIC_SUMMARY_REVISION_V2", "revision-stale", "QUEUED",
                "Conversation topic summary queued");
        JsonNode payload = outboxPayload("revision-stale");
        assertThat(payload.path("covered_start_seq").asInt()).isEqualTo(1);
        assertThat(payload.path("covered_end_seq").asInt()).isEqualTo(4);
        assertThat(payload.path("source_messages").findValuesAsText("message_seq"))
                .containsExactly("1", "2", "3", "4");
        assertThat(payload.has("base_summary_revision_id")).isFalse();
    }

    @Test
    void readySummaryAtTheSameEndSeqStillCountsAsExisting() {
        insertRevision("revision-ready", 1, 4, "READY", "- 已完成的摘要", "a".repeat(64));

        int queued = service.queueBuilds(WORKSPACE_ID, CONVERSATION_ID);

        assertThat(queued).isZero();
        List<RevisionRow> rows = revisions();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo("revision-ready");
        assertThat(rows.get(0).status()).isEqualTo("READY");
        assertThat(rows.get(0).summaryText()).isEqualTo("- 已完成的摘要");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void buildingSummaryAtTheSameEndSeqStillCountsAsExisting() {
        insertRevision("revision-building", 1, 4, "BUILDING", "", null);

        int queued = service.queueBuilds(WORKSPACE_ID, CONVERSATION_ID);

        assertThat(queued).isZero();
        assertThat(revisions()).hasSize(1);
        assertThat(revisions().get(0).status()).isEqualTo("BUILDING");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void staleSummaryAtAnotherEndSeqDoesNotBlockTheNewEnd() {
        // 同一话题段内更早终点上有一版作废摘要：新终点需要新建一版，而不是复用旧行
        insertRevision("revision-stale-old", 1, 3, "STALE", "", null);

        int queued = service.queueBuilds(WORKSPACE_ID, CONVERSATION_ID);

        assertThat(queued).isEqualTo(1);
        List<RevisionRow> rows = revisions();
        assertThat(rows).hasSize(2);
        RevisionRow old = rows.stream().filter(row -> "revision-stale-old".equals(row.id()))
                .findFirst().orElseThrow();
        RevisionRow created = rows.stream().filter(row -> !"revision-stale-old".equals(row.id()))
                .findFirst().orElseThrow();
        // 旧终点上的作废摘要保持原样，新终点另外新建一版
        assertThat(old.status()).isEqualTo("STALE");
        assertThat(old.endSeq()).isEqualTo(3);
        assertThat(created.status()).isEqualTo("BUILDING");
        assertThat(created.endSeq()).isEqualTo(4);
        assertThat(created.revisionNo()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(1);
    }

    private void insertRevision(String id, int revisionNo, int endSeq, String status,
                                String summaryText, String contentHash) {
        jdbc.update("""
                insert into conversation_topic_summary_revision_v2(
                    id, workspace_id, conversation_id, topic_id, segment_id, revision_no,
                    start_seq, end_seq, source_digest, status, summary_text, content_hash,
                    compiler_version, ready_at, summary_method)
                values (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'topic-summary-v2-incremental-a2',
                    ?, ?)
                """, id, WORKSPACE_ID, CONVERSATION_ID, TOPIC_ID, SEGMENT_ID, revisionNo, endSeq,
                STALE_DIGEST, status, summaryText, contentHash,
                "READY".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                "READY".equals(status) ? "LLM_FULL" : null);
    }

    private List<RevisionRow> revisions() {
        return jdbc.query("""
                select id, revision_no, start_seq, end_seq, source_digest, status, summary_text,
                       content_hash, summary_method, ready_at
                from conversation_topic_summary_revision_v2
                where segment_id = ? order by end_seq, revision_no
                """, (rs, index) -> new RevisionRow(rs.getString(1), rs.getInt(2), rs.getInt(3),
                rs.getInt(4), rs.getString(5).trim(), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getTimestamp(10)), SEGMENT_ID);
    }

    private int outboxCount() {
        Integer count = jdbc.queryForObject("select count(*) from task_outbox", Integer.class);
        return count == null ? 0 : count;
    }

    private JsonNode outboxPayload(String messageKey) {
        String payload = jdbc.query("""
                select payload_json from task_outbox where message_key = ?
                """, (rs, index) -> rs.getString(1), messageKey).get(0);
        try {
            return mapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record RevisionRow(String id, int revisionNo, int startSeq, int endSeq,
                               String sourceDigest, String status, String summaryText,
                               String contentHash, String summaryMethod,
                               java.sql.Timestamp readyAt) {}
}
