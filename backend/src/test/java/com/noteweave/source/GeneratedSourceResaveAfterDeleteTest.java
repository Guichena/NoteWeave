package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.source.GeneratedSourceResult;
import com.noteweave.storage.ObjectStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NR20：删除生成资料后再次保存必须得到可用的新来源，而不是返回指向已删除资料的假成功。
 */
class GeneratedSourceResaveAfterDeleteTest {

    private JdbcTemplate jdbc;
    private GeneratedSourceService service;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:generated-resave-" + System.nanoTime()
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
        ObjectStorage storage = mock(ObjectStorage.class);
        this.storage = storage;
        SourceParsePort parsePort = mock(SourceParsePort.class);
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem(anyString())).thenReturn("tester");
        SourceCatalogVersionService catalogVersion = mock(SourceCatalogVersionService.class);
        com.noteweave.task.TaskCommandPort taskCommandPort = mock(com.noteweave.task.TaskCommandPort.class);
        when(taskCommandPort.createTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("task-1");
        SourceMessagingMode messagingMode = mock(SourceMessagingMode.class);
        // 本用例的 H2 schema 没有 task_outbox，走未启用 Kafka 的同步分支
        when(messagingMode.isAsyncEnabled()).thenReturn(false);
        service = new GeneratedSourceService(jdbc, storage, parsePort, actorProvider, catalogVersion,
                taskCommandPort, mock(SourceTopicNames.class), messagingMode,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void resaveAfterDeleteCreatesFreshUsableSourceInsteadOfReturningDeletedOne() {
        GeneratedSourceResult first =
                service.saveMarkdown("ws-1", "报告", "# 内容", "GENERATED_ARTIFACT", "artifact_agent", "ver-1");
        assertThat(first.sourceId()).isNotBlank();

        // 删除该来源后再保存：不得返回已删除来源的假成功
        jdbc.update("update source set status = 'DELETED' where id = ?", first.sourceId());
        GeneratedSourceResult second =
                service.saveMarkdown("ws-1", "报告", "# 内容", "GENERATED_ARTIFACT", "artifact_agent", "ver-1");

        assertThat(second.sourceId()).isNotEqualTo(first.sourceId());
        assertThat(second.status()).isEqualTo("PROCESSING");
        List<String> active = jdbc.queryForList("""
                select id from source where workspace_id = 'ws-1'
                  and generated_by = 'artifact_agent' and generated_ref_id = 'ver-1'
                  and status <> 'DELETED'
                """, String.class);
        assertThat(active).containsExactly(second.sourceId());

        // 未删除时的重复保存仍然幂等返回同一来源
        GeneratedSourceResult again =
                service.saveMarkdown("ws-1", "报告", "# 内容", "GENERATED_ARTIFACT", "artifact_agent", "ver-1");
        assertThat(again.sourceId()).isEqualTo(second.sourceId());
    }

    @Test
    void changedContentUnderTheSameReferenceCreatesANewSource() {
        GeneratedSourceResult first =
                service.saveMarkdown("ws-2", "笔记", "# 第一版", "GENERATED_NOTE", "note_agent", "msg-1");
        // SRC-03：同一条消息内容改过再保存，必须生成新来源；
        // 旧实现只按 (generated_by, generated_ref_id) 复用，会返回旧内容并当成保存成功
        GeneratedSourceResult second =
                service.saveMarkdown("ws-2", "笔记", "# 第二版", "GENERATED_NOTE", "note_agent", "msg-1");
        assertThat(second.sourceId()).isNotEqualTo(first.sourceId());

        // 内容没变时仍然幂等，双击不会产生两条来源
        GeneratedSourceResult again =
                service.saveMarkdown("ws-2", "笔记", "# 第一版", "GENERATED_NOTE", "note_agent", "msg-1");
        assertThat(again.sourceId()).isEqualTo(first.sourceId());

        // DATA-03：两代快照不能共用一个对象键——共用时新写入会覆盖旧快照的正文，
        // 旧来源的清理任务也会把新对象删掉
        List<String> snapshotKeys = jdbc.queryForList("""
                select ss.object_key from source_snapshot ss
                join source s on s.id = ss.source_id
                where s.workspace_id = 'ws-2'
                order by s.created_at, s.id
                """, String.class);
        assertThat(snapshotKeys).hasSize(2).doesNotHaveDuplicates();
        org.mockito.ArgumentCaptor<String> writtenKeys = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.atLeast(2))
                .write(org.mockito.ArgumentMatchers.anyString(), writtenKeys.capture(),
                        org.mockito.ArgumentMatchers.any(byte[].class));
        assertThat(writtenKeys.getAllValues()).containsAll(snapshotKeys);
    }
}
