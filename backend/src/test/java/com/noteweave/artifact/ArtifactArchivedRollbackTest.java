package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArtifactArchivedRollbackTest {
    private JdbcTemplate jdbc;
    private ObjectStorage storage;
    private ArtifactWorkerExportClient worker;
    private ArtifactExportService exports;
    private byte[] pdf;

    @BeforeEach
    void archivedFiles() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:archived-rollback-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table artifact_job(id varchar(36), task_id varchar(36))");
        jdbc.execute("create table artifact_version(id varchar(36), artifact_job_id varchar(36), version_no int, title varchar(100), content_markdown clob, result_payload_json clob, origin_task_id varchar(36))");
        jdbc.execute("""
                create table artifact_file(id varchar(36), artifact_version_id varchar(36), file_format varchar(20),
                    file_name varchar(100), media_type varchar(100), storage_backend varchar(20), bucket_name varchar(100),
                    object_key varchar(200), size_bytes bigint, checksum_sha256 varchar(64), status varchar(20),
                    error_message varchar(100), file_role varchar(40), variant varchar(40), sequence_no int,
                    created_at timestamp default current_timestamp)
                """);
        pdf = pdf();
        byte[] markdown = "# 归档".getBytes(StandardCharsets.UTF_8);
        jdbc.update("insert into artifact_job values ('job', 'task')");
        jdbc.update("insert into artifact_version values ('version', 'job', 1, '归档', '# 归档', ?, 'task')",
                "{\"export_trace\":{\"status\":\"COMPILED\",\"file_name\":\"lesson.pdf\"}}");
        file("markdown", "MARKDOWN", "PRIMARY_MARKDOWN", "lesson.md", markdown);
        file("pdf", "PDF", "PRIMARY_PDF", "lesson.pdf", pdf);
        storage = mock(ObjectStorage.class);
        Map<String, byte[]> content = Map.of("markdown", markdown, "pdf", pdf);
        when(storage.read(eq("export"), anyString())).thenAnswer(call -> content.get(call.getArgument(1)));
        worker = mock(ArtifactWorkerExportClient.class);
        when(worker.fetch(anyString(), anyString())).thenThrow(new IllegalStateException("原 Worker 临时文件已清理"));
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class, RETURNS_DEEP_STUBS);
        when(properties.storage().minio().bucketExport()).thenReturn("export");
        exports = new ArtifactExportService(jdbc, new ObjectMapper(), storage, worker,
                mock(ArtifactSkillCatalogService.class), mock(ArtifactMemoryRevisionGuard.class),
                mock(ArtifactContextV2ShadowSnapshotService.class), mock(ResearchGeneratedSourceReadGate.class), properties);
    }

    @Test
    void archivedPdfCanBeRolledBackWithoutOriginalWorker() {
        assertThat(exports.prepareRollbackFiles("version")).hasSize(2)
                .anySatisfy(file -> { assertThat(file.format()).isEqualTo("PDF"); assertThat(file.content()).isEqualTo(pdf); });
        verifyNoInteractions(worker);
        assertThat(jdbc.queryForObject("select status from artifact_file where id = 'pdf'", String.class)).isEqualTo("READY");
    }

    @Test
    void corruptedArchiveFailsClearlyWithoutReplacingItsMetadata() {
        when(storage.read("export", "pdf")).thenReturn("损坏文件".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> exports.prepareRollbackFiles("version"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("ARTIFACT_SOURCE_FILE_INVALID"));
        verifyNoInteractions(worker);
        assertThat(jdbc.queryForObject("select status from artifact_file where id = 'pdf'", String.class)).isEqualTo("READY");
    }

    private void file(String id, String format, String role, String name, byte[] content) throws Exception {
        jdbc.update("insert into artifact_file(id, artifact_version_id, file_format, file_name, media_type, storage_backend, bucket_name, object_key, size_bytes, checksum_sha256, status, error_message, file_role, variant, sequence_no) values (?, 'version', ?, ?, 'application/octet-stream', 'local', 'export', ?, ?, ?, 'READY', '', ?, '', 0)",
                id, format, name, id, content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), role);
    }

    private byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(bytes); return bytes.toByteArray();
        }
    }
}
