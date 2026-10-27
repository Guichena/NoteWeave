package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.task.TaskCommandPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 资料删除原因对笔记可用性的影响。 */
class SourceDeletionReasonTest {

    private JdbcTemplate jdbcTemplate;
    private SourceService service;
    private SourceCompliancePurgeService compliancePurgeService;
    private WorkspaceAccessGuard workspaceAccessGuard;
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
                    summary varchar(1000),
                    metadata_json longtext,
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
                    generation int not null default 1,
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
                    origin varchar(32),
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
                    source_id varchar(36) not null,
                    source_chunk_id varchar(36),
                    quote_text longtext not null default ''
                )
                """);
        jdbcTemplate.execute("""
                create table source_chunk(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    content longtext not null,
                    heading varchar(300),
                    context_text longtext,
                    structure_metadata_json longtext,
                    projection_status varchar(32) not null default 'PENDING'
                )
                """);
        jdbcTemplate.execute("""
                create table source_window(
                    id varchar(36) primary key,
                    source_chunk_id varchar(36) not null,
                    content longtext not null
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
        // 分块：chunk-1 被引用，chunk-2 未被引用；窗口与引用摘录都带正文
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, content, heading, context_text,
                    structure_metadata_json, projection_status
                ) values (
                    'chunk-1', 'workspace-1', 'source-1', '资料一正文片段一', '小节标题',
                    '上下文补全', '{}', 'READY'
                )
                """);
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, content, heading, context_text,
                    structure_metadata_json, projection_status
                ) values (
                    'chunk-2', 'workspace-1', 'source-1', '资料一正文片段二', null,
                    null, null, 'READY'
                )
                """);
        jdbcTemplate.update("""
                insert into source_window(id, source_chunk_id, content)
                values ('window-1', 'chunk-1', '窗口正文')
                """);
        jdbcTemplate.update("""
                update citation set source_chunk_id = 'chunk-1', quote_text = '引用摘录'
                where id = 'citation-1'
                """);
        jdbcTemplate.update("""
                update source set summary = '资料摘要', metadata_json = '{"outline":"目录"}'
                where id = 'source-1'
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
        compliancePurgeService = new SourceCompliancePurgeService(
                jdbcTemplate,
                new SimpleMeterRegistry(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        workspaceAccessGuard = mock(WorkspaceAccessGuard.class);
        service = new SourceService(
                jdbcTemplate,
                workspaceAccessGuard,
                wikiCommandPort,
                auditActorProvider,
                mock(SourceCatalogCache.class),
                new SourceCatalogVersionService(jdbcTemplate),
                new SimpleMeterRegistry(),
                mock(TaskCommandPort.class),
                eventPublisher,
                mock(RunReplayRedactionService.class),
                researchGate,
                compliancePurgeService);
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
    void complianceDeletionShouldPurgeChunkContentAndRedactCitations() {
        service.deleteSource("workspace-1", "source-1", "COMPLIANCE");

        // 未被引用的分块物理删除，被引用的分块保留空壳
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_chunk where id = 'chunk-2'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select content from source_chunk where id = 'chunk-1'", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select heading from source_chunk where id = 'chunk-1'", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_window", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select quote_text from citation where id = 'citation-1'", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select summary from source where id = 'source-1'", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select metadata_json from source where id = 'source-1'", String.class)).isNull();
    }

    @Test
    void userDeletionShouldKeepChunkContentForNoteHistory() {
        service.deleteSource("workspace-1", "source-1", "USER");

        assertThat(jdbcTemplate.queryForObject(
                "select content from source_chunk where id = 'chunk-1'", String.class))
                .isEqualTo("资料一正文片段一");
        assertThat(jdbcTemplate.queryForObject(
                "select quote_text from citation where id = 'citation-1'", String.class))
                .isEqualTo("引用摘录");
    }

    @Test
    void compliancePurgeShouldBeRerunnableForHistoricalResidue() {
        service.deleteSource("workspace-1", "source-1", "COMPLIANCE");
        // 模拟修复前已删除的历史资料：正文仍在库里
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, content, heading, context_text,
                    structure_metadata_json, projection_status
                ) values ('chunk-legacy', 'workspace-1', 'source-1', '历史残留正文', null, null, null, 'READY')
                """);
        jdbcTemplate.update("""
                update source set summary = '历史摘要' where id = 'source-1'
                """);

        assertThat(compliancePurgeService.findResidual(10)).hasSize(1);
        // dry-run 不写库
        assertThat(compliancePurgeService.purgeResidual(true, 10)).isEqualTo(1);
        assertThat(compliancePurgeService.findResidual(10)).hasSize(1);

        assertThat(compliancePurgeService.purgeResidual(false, 10)).isEqualTo(1);
        assertThat(compliancePurgeService.findResidual(10)).isEmpty();
        // 重复执行没有副作用
        assertThat(compliancePurgeService.purgeResidual(false, 10)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_chunk where content <> ''", Integer.class)).isZero();
    }

    @Test
    void complianceDeletionShouldRequireOwnerPermission() {
        service.deleteSource("workspace-1", "source-1", "COMPLIANCE");

        verify(workspaceAccessGuard).requireOwner("workspace-1");
        verify(workspaceAccessGuard, never()).requirePermission(eq("workspace-1"), eq(WorkspacePermission.SOURCE_WRITE));
    }

    @Test
    void editorWithoutOwnerRoleShouldNotComplianceDelete() {
        // EDITOR 具备 SOURCE_WRITE，但合规删除会清空正文并撤销笔记，必须 OWNER
        doThrow(new BusinessException("WORKSPACE_ACCESS_DENIED", "无权执行该工作台操作",
                HttpStatus.FORBIDDEN))
                .when(workspaceAccessGuard).requireOwner("workspace-1");

        assertThatThrownBy(() -> service.deleteSource("workspace-1", "source-1", "COMPLIANCE"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("WORKSPACE_ACCESS_DENIED"));

        assertThat(sourceColumn("status")).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select content from source_chunk where id = 'chunk-1'", String.class))
                .isEqualTo("资料一正文片段一");
    }

    @Test
    void userDeletionShouldStillUseSourceWritePermission() {
        service.deleteSource("workspace-1", "source-1", "USER");

        verify(workspaceAccessGuard).requirePermission("workspace-1", WorkspacePermission.SOURCE_WRITE);
        verify(workspaceAccessGuard, never()).requireOwner(anyString());
    }

    @Test
    void invalidDeletionReasonShouldBeRejectedWithBadRequest() {        assertThatThrownBy(() -> service.deleteSource("workspace-1", "source-1", "SENSITIVE"))
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
