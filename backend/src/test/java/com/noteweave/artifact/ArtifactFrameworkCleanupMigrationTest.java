package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArtifactFrameworkCleanupMigrationTest {
    private final Path source = Path.of("src/main/resources/db/migration");

    private DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void freshDatabaseAppliesCleanupAndKeepsCurrentArtifactAndMediaTables() {
        var database = database();
        Flyway.configure().dataSource(database).locations("filesystem:" + source.toAbsolutePath()).load().migrate();
        verifyCurrentTables(new JdbcTemplate(database));
    }

    @Test
    void missingEditReceiptMigrationsApplyOutOfOrderWithoutMovingTheCurrentVersion(@TempDir Path migrations) throws Exception {
        // V203 改的是 V200 创建的表。旧库如果没有 V200，就不能先执行 V203。
        try (var files = Files.list(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("V200__") || name.startsWith("V203__")) {
                    continue;
                }
                Files.copy(file, migrations.resolve(name));
            }
        }
        var database = database();
        String location = "filesystem:" + migrations.toAbsolutePath();
        var baseline = Flyway.configure().dataSource(database).locations(location).load();
        baseline.migrate();
        String baselineVersion = baseline.info().current().getVersion().getVersion();
        Files.copy(source.resolve("V200__memory_artifact_edit_receipt.sql"),
                migrations.resolve("V200__memory_artifact_edit_receipt.sql"));
        Files.copy(source.resolve("V203__memory_edit_receipt_v2.sql"),
                migrations.resolve("V203__memory_edit_receipt_v2.sql"));
        assertThatThrownBy(() -> Flyway.configure().dataSource(database).locations(location).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("200");
        assertThat(Flyway.configure().dataSource(database).locations(location).outOfOrder(true).load().migrate().migrationsExecuted)
                .isEqualTo(2);
        var normal = Flyway.configure().dataSource(database).locations(location).load();
        assertThat(normal.migrate().migrationsExecuted).isZero();
        assertThat(normal.info().current().getVersion().getVersion()).isEqualTo(baselineVersion);
        verifyCurrentTables(new JdbcTemplate(database));
    }

    private void verifyCurrentTables(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("artifact_job", "artifact_version", "artifact_file", "artifact_run_input_snapshot",
                "artifact_candidate_receipt", "task_outbox", "source_media_frame", "context_v2_shadow_snapshot", "memory_usage_log");
        assertThat(tables).doesNotContain("artifact_video_material_bundle", "artifact_video_material_file", "artifact_video_knowledge_plan",
                "video_learning_request", "video_learning_request_choice", "artifact_context_v2_shadow_snapshot", "artifact_context_v2_shadow_ref");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'artifact_version'", String.class))
                .doesNotContain("material_bundle_id");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'source_chunk'", String.class))
                .contains("part", "start_ms", "end_ms", "frame_ids_json", "source_url");
    }
}
