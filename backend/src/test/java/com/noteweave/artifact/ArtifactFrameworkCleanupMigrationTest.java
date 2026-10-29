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
    void existingV210NeedsOnlyOneOutOfOrderUpgradeAndThenUsesDefaultAgain(@TempDir Path migrations) throws Exception {
        try (var files = Files.list(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                if (!file.getFileName().toString().startsWith("V200__")) {
                    Files.copy(file, migrations.resolve(file.getFileName()));
                }
            }
        }
        var database = database();
        String location = "filesystem:" + migrations.toAbsolutePath();
        Flyway.configure().dataSource(database).locations(location).load().migrate();
        Files.copy(source.resolve("V220__remove_legacy_artifact_framework.sql"),
                migrations.resolve("V220__remove_legacy_artifact_framework.sql"));
        assertThatThrownBy(() -> Flyway.configure().dataSource(database).locations(location).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("200");
        assertThat(Flyway.configure().dataSource(database).locations(location).outOfOrder(true).load().migrate().migrationsExecuted)
                .isEqualTo(1);
        var normal = Flyway.configure().dataSource(database).locations(location).load();
        assertThat(normal.migrate().migrationsExecuted).isZero();
        assertThat(normal.info().current().getVersion().getVersion()).isEqualTo("210");
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
