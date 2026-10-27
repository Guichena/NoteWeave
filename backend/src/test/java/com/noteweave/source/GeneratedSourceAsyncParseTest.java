package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SRC-01：生成来源保存只写对象与元数据，解析通过 outbox 异步入队；
 * 关闭开关时回到事务内同步解析（回滚路径）。
 */
class GeneratedSourceAsyncParseTest {

    private JdbcTemplate jdbc;
    private SourceParsePort parsePort;
    private SourceMessagingMode messagingMode;
    private SourceTopicNames topicNames;
    private GeneratedSourceService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:generated-async-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table source(
                    id varchar(36) primary key, workspace_id varchar(36) not null,
                    file_object_id varchar(36), title varchar(300), source_type varchar(64),
                    status varchar(32), parse_status varchar(32), index_status varchar(32),
                    generated_by varchar(64), generated_ref_id varchar(64),
                    created_by varchar(64), updated_by varchar(64),
                    created_at timestamp default current_timestamp
                )
                """);
        jdbc.execute("""
                create table source_snapshot(
                    id varchar(36) primary key, source_id varchar(36) not null,
                    file_object_id varchar(36), version_no int, object_key varchar(500),
                    sha256 varchar(64), parse_status varchar(32), index_status varchar(32)
                )
                """);
        jdbc.execute("""
                create table file_object(
                    id varchar(36) primary key, workspace_id varchar(36),
                    object_key varchar(500), sha256 varchar(64), file_size bigint,
                    mime_type varchar(160), ref_count int,
                    status varchar(32) default 'ACTIVE', object_generation bigint default 1
                )
                """);
        jdbc.execute("""
                create table task_outbox(
                    id varchar(36) primary key, task_id varchar(36) not null,
                    topic varchar(120) not null, message_key varchar(120),
                    payload_json clob not null, status varchar(32) not null
                )
                """);
        parsePort = mock(SourceParsePort.class);
        messagingMode = mock(SourceMessagingMode.class);
        topicNames = mock(SourceTopicNames.class);
        when(topicNames.parse()).thenReturn("noteweave.source.parse");
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem(anyString())).thenReturn("tester");
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
        when(taskCommandPort.createTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("task-1");
        service = new GeneratedSourceService(
                jdbc,
                mock(ObjectStorage.class),
                parsePort,
                actorProvider,
                mock(SourceCatalogVersionService.class),
                taskCommandPort,
                topicNames,
                messagingMode,
                new ObjectMapper());
    }

    @Test
    void savingEnqueuesParseInsteadOfParsingInline() {
        when(messagingMode.isAsyncEnabled()).thenReturn(true);

        GeneratedSourceResult result = service.saveMarkdown(
                "ws-1", "报告", "# 内容", "GENERATED_NOTE_ANSWER", "note_answer", "msg-1");

        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(result.parseStatus()).isEqualTo("PENDING");
        assertThat(result.indexStatus()).isEqualTo("PENDING");
        verify(parsePort, never()).parseAndIndex(anyString(), anyString(), anyString(), any(byte[].class));

        Map<String, Object> outbox = jdbc.queryForMap(
                "select task_id, topic, message_key, payload_json, status from task_outbox");
        assertThat(outbox.get("TASK_ID")).isEqualTo("task-1");
        assertThat(outbox.get("TOPIC")).isEqualTo("noteweave.source.parse");
        assertThat(outbox.get("MESSAGE_KEY")).isEqualTo(result.sourceId());
        assertThat(outbox.get("STATUS")).isEqualTo("READY");
        String payload = (String) outbox.get("PAYLOAD_JSON");
        assertThat(payload).contains(result.sourceId()).contains("snapshotId").contains("task-1");
    }

    @Test
    void rollbackSwitchKeepsSynchronousParsing() {
        when(messagingMode.isAsyncEnabled()).thenReturn(true);
        service.configureAsyncParse(false);

        GeneratedSourceResult result = service.saveMarkdown(
                "ws-1", "报告", "# 内容", "GENERATED_NOTE_ANSWER", "note_answer", "msg-1");

        verify(parsePort).parseAndIndex(eq("ws-1"), eq(result.sourceId()), anyString(), any(byte[].class));
        assertThat(jdbc.queryForObject("select count(*) from task_outbox", Integer.class)).isZero();
    }
}
