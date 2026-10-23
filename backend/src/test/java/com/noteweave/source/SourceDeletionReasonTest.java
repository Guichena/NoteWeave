package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.task.TaskCommandPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 资料删除原因对笔记可用性的影响。 */
class SourceDeletionReasonTest {

    private JdbcTemplate jdbcTemplate;
    private SourceService service;
    private SourceWikiCommandPort wikiCommandPort;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-delete-reason-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table workspace(
                    id varchar(36) primary key,
                    source_catalog_version bigint not null default 1
                )
                """);
        jdbcTemplate.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    object_key varchar(500) not null,
                    ref_count int not null
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    file_object_id varchar(36) not null,
                    title varchar(300) not null,
                    status varchar(32) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    delete_reason varchar(16) not null default 'USER',
                    updated_by varchar(80),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null,
                    object_key varchar(500),
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table task(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    target_type varchar(32),
                    target_id varchar(36),
                    task_type varchar(32),
                    task_status varchar(32)
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_type varchar(32) not null,
                    title varchar(300) not null,
                    status varchar(32) not null,
                    latest_version_id varchar(36),
                    created_by varchar(80),
                    updated_by varchar(80),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version(
                    id varchar(36) primary key,
                    item_id varchar(36) not null,
                    version_no int not null,
                    content text not null,
                    summary varchar(1000)
                )
                """);
        jdbcTemplate.execute("""
                create table citation(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version_citation(
                    id varchar(36) primary key,
                    knowledge_version_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);

        jdbcTemplate.update("insert into workspace(id) values ('workspace-1')");
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, ref_count)
                values ('file-1', 'workspace-1', 'workspace/workspace-1/source-1.txt', 2)
                """);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, status,
                    parse_status, index_status, updated_by, updated_at
                ) values (
                    'source-1', 'workspace-1', 'file-1', '资料一', 'READY',
                    'PARSED', 'INDEXED', 'SYSTEM:TEST', current_timestamp
                )
                """);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, version_no, object_key, parse_status, index_status
                ) values (
                    'snapshot-1', 'source-1', 1,
                    'workspace/workspace-1/source/source-1/snapshot/1/original.txt',
                    'PARSED', 'INDEXED'
                )
                """);
        jdbcTemplate.update("""
                insert into citation(id, workspace_id, source_id)
                values ('citation-1', 'workspace-1', 'source-1')
                """);
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, title, status, latest_version_id,
                    created_by, updated_by, updated_at
                ) values (
                    'note-1', 'workspace-1', 'NOTE', '引用资料一的笔记', 'ACTIVE', 'version-1',
                    'USER:TEST', 'USER:TEST', current_timestamp
                )
                """);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary)
                values ('version-1', 'note-1', 1, '原始笔记内容', '摘要')
                """);
        jdbcTemplate.update("""
                insert into knowledge_version_citation(
                    id, knowledge_version_id, citation_id, sort_order
                ) values ('kvc-1', 'version-1', 'citation-1', 0)
                """);
        // 另一条不引用待删除资料的笔记，用于确认合规删除不会扩大影响范围
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, title, status, latest_version_id,
                    created_by, updated_by, updated_at
                ) values (
                    'note-2', 'workspace-1', 'NOTE', '其他笔记', 'ACTIVE', 'version-2',
                    'USER:TEST', 'USER:TEST', current_timestamp
                )
                """);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary)
                values ('version-2', 'note-2', 1, '其他笔记内容', '摘要')
                """);

        wikiCommandPort = mock(SourceWikiCommandPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        AuditActorProvider auditActorProvider = mock(AuditActorProvider.class);
        when(auditActorProvider.currentOrSystem("SOURCE")).thenReturn("USER:TEST");
        ResearchGeneratedSourceReadGate researchGate = mock(ResearchGeneratedSourceReadGate.class);
        when(researchGate.visible(anyString(), anyString(), anyString())).thenReturn(true);
        service = new SourceService(
                jdbcTemplate,
                mock(WorkspaceAccessGuard.class),
                wikiCommandPort,
                auditActorProvider,
                mock(SourceCatalogCache.class),
                new SourceCatalogVersionService(jdbcTemplate),
                new SimpleMeterRegistry(),
                mock(TaskCommandPort.class),
                eventPublisher,
                mock(RunReplayRedactionService.class),
                researchGate);
    }

    @Test
    void userDeletionShouldKeepReferencingNoteActive() {
        DeleteSourceResponse response = service.deleteSource("workspace-1", "source-1");

        assertThat(response.status()).isEqualTo("DELETED");
        assertThat(sourceColumn("status")).isEqualTo("DELETED");
        assertThat(sourceColumn("delete_reason")).isEqualTo("USER");
        assertThat(noteStatus("note-1")).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_version where id = 'version-1'", Integer.class))
                .isEqualTo(1);

        ArgumentCaptor<SourceDeletedEvent> event = ArgumentCaptor.forClass(SourceDeletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo("USER");
    }

    @Test
    void complianceDeletionShouldRevokeReferencingNoteAndKeepAuditRows() {
        service.deleteSource("workspace-1", "source-1", "COMPLIANCE");

        assertThat(sourceColumn("status")).isEqualTo("DELETED");
        assertThat(sourceColumn("delete_reason")).isEqualTo("COMPLIANCE");
        assertThat(noteStatus("note-1")).isEqualTo("REVOKED");
        assertThat(noteStatus("note-2")).isEqualTo("ACTIVE");
        // 笔记、版本、引用和审计字段都保留，只改变可用状态
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_item where id = 'note-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = 'version-1'", String.class))
                .isEqualTo("原始笔记内容");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_version_citation where id = 'kvc-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select updated_by from knowledge_item where id = 'note-1'", String.class))
                .isEqualTo("USER:TEST");

        ArgumentCaptor<SourceDeletedEvent> event = ArgumentCaptor.forClass(SourceDeletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo("COMPLIANCE");
    }

    @Test
    void invalidDeletionReasonShouldBeRejectedWithBadRequest() {
        assertThatThrownBy(() -> service.deleteSource("workspace-1", "source-1", "SENSITIVE"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SOURCE_DELETE_REASON_INVALID");
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(sourceColumn("status")).isEqualTo("READY");
        assertThat(noteStatus("note-1")).isEqualTo("ACTIVE");
    }

    @Test
    void controllerShouldRejectInvalidReasonBeforeCallingService() {
        SourceService mockedService = mock(SourceService.class);
        SourceController controller = new SourceController(
                mockedService, mock(SourceReprocessService.class));

        assertThatThrownBy(() -> controller.deleteSource("workspace-1", "source-1", "SENSITIVE"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SOURCE_DELETE_REASON_INVALID");
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(mockedService);
    }

    private String sourceColumn(String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from source where id = 'source-1'", String.class);
    }

    private String noteStatus(String noteId) {
        return jdbcTemplate.queryForObject(
                "select status from knowledge_item where id = ?", String.class, noteId);
    }
}
