package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FA-04 定向验证：编辑幂等身份、请求摘要冲突、二进制版本拦截与事件事实。
 * 服务依赖以 mock 提供（同包可替身包内方法）；事件存储用内存桩，真实 SQL
 * 见 JdbcArtifactEditEventStoreTest。
 */
class ArtifactEditServiceFlowTest {

    private final JdbcTemplate jdbc = jdbcTemplate();
    private final RecordingEventStore eventStore = new RecordingEventStore();
    private final ArtifactJobWriteRepository writeRepository = mock(ArtifactJobWriteRepository.class);
    private final ArtifactExportService exportService = mock(ArtifactExportService.class);
    private final ArtifactEditService service = service(eventStore, writeRepository, exportService);

    private static final String BASE_MARKDOWN = "# 基础版本\n这是可以被编辑的正文。";
    private static final String EDITED_MARKDOWN = "# 基础版本\n这是编辑后的新正文，结论改写。";

    @Test
    void sameIdentitySamePayloadReplaysWithoutSecondVersion() {
        ArtifactVersionEditRequest request = request("edit-test-0001", EDITED_MARKDOWN);
        ArtifactVersionEditResponse first = service.editVersion("ws-1", "job-1", 1, request);
        org.junit.jupiter.api.Assertions.assertTrue(first.created());
        org.junit.jupiter.api.Assertions.assertEquals("EVENT_STORED", first.memoryFeedbackStatus());

        ArtifactVersionEditResponse replay = service.editVersion("ws-1", "job-1", 1, request);
        org.junit.jupiter.api.Assertions.assertFalse(replay.created());
        org.junit.jupiter.api.Assertions.assertEquals("REPLAY", replay.filePreparationStatus());
        verify(writeRepository, times(1)).appendEditVersion(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyString());
        org.junit.jupiter.api.Assertions.assertEquals(1, eventStore.rows.size());
        org.junit.jupiter.api.Assertions.assertEquals("local-user",
                eventStore.rows.get("edit-test-0001").actingUserId());
    }

    @Test
    void sameIdentityDifferentPayloadConflicts() {
        service.editVersion("ws-1", "job-1", 1, request("edit-test-0002", EDITED_MARKDOWN));
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () ->
                service.editVersion("ws-1", "job-1", 1,
                        request("edit-test-0002", "完全不同的正文内容")));
    }

    @Test
    void invalidRequestIdRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () ->
                service.editVersion("ws-1", "job-1", 1, request("bad id!", EDITED_MARKDOWN)));
        org.junit.jupiter.api.Assertions.assertTrue(eventStore.rows.isEmpty());
    }

    @Test
    void pdfBinaryVersionRejected() {
        jdbc.update("""
                insert into artifact_file(id, artifact_version_id, file_format, file_role, variant,
                        sequence_no, file_name, status)
                values ('file-pdf', 'ver-1', 'PDF', 'PRIMARY_PDF', '', 0, 'a.pdf', 'READY')
                """);
        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> service.editVersion("ws-1", "job-1", 1, request("edit-test-0003", EDITED_MARKDOWN)));
        org.junit.jupiter.api.Assertions.assertEquals("ARTIFACT_EDIT_TYPE_UNSUPPORTED", exception.code());
        org.junit.jupiter.api.Assertions.assertTrue(eventStore.rows.isEmpty());
    }

    @Test
    void unchangedMarkdownProducesNoEvent() {
        ArtifactVersionEditResponse response = service.editVersion("ws-1", "job-1", 1,
                request("edit-test-0004", BASE_MARKDOWN));
        org.junit.jupiter.api.Assertions.assertFalse(response.created());
        org.junit.jupiter.api.Assertions.assertEquals("UNCHANGED", response.filePreparationStatus());
        org.junit.jupiter.api.Assertions.assertTrue(eventStore.rows.isEmpty());
    }

    @Test
    void eventCarriesDiffContractFields() {
        service.editVersion("ws-1", "job-1", 1, request("edit-test-0005", EDITED_MARKDOWN));
        ArtifactEditEventStore.ArtifactEditEventRecord record = eventStore.rows.get("edit-test-0005");
        org.junit.jupiter.api.Assertions.assertNotNull(record);
        org.junit.jupiter.api.Assertions.assertFalse(record.requestDigest().isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(record.changedFragments().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(record.changedChars() > 0);
        org.junit.jupiter.api.Assertions.assertEquals(ArtifactVersionDiffV2.ALGORITHM,
                record.diffAlgorithm());
        org.junit.jupiter.api.Assertions.assertEquals("task-origin-1", record.originTaskId());
    }

    // === 测试基础设施 ===

    private ArtifactEditService service(
            ArtifactEditEventStore store,
            ArtifactJobWriteRepository writeRepository,
            ArtifactExportService exportService
    ) {
        ArtifactJobService jobService = mock(ArtifactJobService.class);
        when(exportService.runInShortTransaction(any())).thenAnswer(invocation ->
                invocation.getArgument(0, java.util.function.Supplier.class).get());
        return new ArtifactEditService(
                jdbc,
                new ObjectMapper(),
                jobService,
                writeRepository,
                exportService,
                mock(ApplicationEventPublisher.class),
                store,
                new CurrentUserProvider(true));
    }

    private ArtifactVersionEditRequest request(String requestId, String markdown) {
        return new ArtifactVersionEditRequest(requestId, 1, markdown, "测试编辑");
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-edit-flow-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table artifact_job(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    latest_version_no int not null,
                    result_title varchar(512)
                )
                """);
        template.execute("""
                create table artifact_version(
                    id varchar(36) primary key,
                    artifact_job_id varchar(36) not null,
                    skill_key varchar(64),
                    version_no int not null,
                    title varchar(512),
                    content_markdown text,
                    result_payload_json text,
                    trace_summary text,
                    citations_json text,
                    origin_task_id varchar(36)
                )
                """);
        template.execute("""
                create table artifact_file(
                    id varchar(36) primary key,
                    artifact_version_id varchar(36) not null,
                    file_format varchar(16),
                    file_role varchar(32),
                    variant varchar(64),
                    sequence_no int,
                    file_name varchar(255),
                    status varchar(32)
                )
                """);
        template.update("insert into artifact_job(id, workspace_id, latest_version_no) "
                + "values ('job-1', 'ws-1', 1)");
        template.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, title,
                        content_markdown, result_payload_json, citations_json, origin_task_id)
                values ('ver-1', 'job-1', 'report', 1, '基础版本', ?, '{}', '[]', 'task-origin-1')
                """, BASE_MARKDOWN);
        return template;
    }

    /** 内存事件存储桩：按请求身份记录首行，重复身份抛 DuplicateKeyException 语义。 */
    private static final class RecordingEventStore implements ArtifactEditEventStore {
        final Map<String, ArtifactEditEventRecord> rows = new HashMap<>();

        @Override
        public void insertEvent(ArtifactEditEventRecord event) {
            if (rows.containsKey(event.requestId())) {
                throw new org.springframework.dao.DuplicateKeyException("duplicate " + event.requestId());
            }
            rows.put(event.requestId(), event);
        }

        @Override
        public ArtifactEditEventRecord findByRequestId(String workspaceId, String artifactJobId,
                                                       String requestId) {
            return rows.get(requestId);
        }
    }
}
