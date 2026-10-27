package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NR18：历史版本再生成使用该版本 origin Run 的冻结输入。
 * v1（要求 A）/v2（要求 B）场景下选择 v1 必须取 A；来源不可恢复（缺 origin 任务或
 * Run 记录缺失）时明确失败，不再静默取最新 Run。
 */
class ArtifactRegenerationSourceTest {

    private JdbcTemplate jdbc;
    private ArtifactJobReadRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-regen-source-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table artifact_job(
                    id varchar(36) primary key, workspace_id varchar(36), skill_key varchar(64),
                    user_requirement varchar(500), inputs_json clob,
                    source_scope_json clob, control_pack_json clob)
                """);
        jdbc.execute("""
                create table artifact_job_run(
                    task_id varchar(36) primary key, artifact_job_id varchar(36), run_no int,
                    trigger_type varchar(32), source_version_no int,
                    user_requirement varchar(500), inputs_json clob, source_scope_json clob,
                    control_pack_json clob, input_snapshot_id varchar(36), reserved_version_id varchar(36))
                """);
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key, artifact_job_id varchar(36), skill_key varchar(64),
                    version_no int, origin_task_id varchar(36))
                """);
        repository = new ArtifactJobReadRepository(jdbc);
        jdbc.update("""
                insert into artifact_job(id, workspace_id, skill_key, user_requirement)
                values ('job', 'ws-1', 'lesson_pdf', '最新要求B（不应被 v1 再生成使用）')
                """);
        jdbc.update("""
                insert into artifact_job_run(task_id, artifact_job_id, run_no, user_requirement,
                                             input_snapshot_id)
                values ('task-1', 'job', 1, '要求A', 'snap-a')
                """);
        jdbc.update("""
                insert into artifact_job_run(task_id, artifact_job_id, run_no, user_requirement,
                                             input_snapshot_id)
                values ('task-2', 'job', 2, '要求B', 'snap-b')
                """);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, origin_task_id)
                values ('ver-1', 'job', 'lesson_pdf', 1, 'task-1')
                """);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, origin_task_id)
                values ('ver-2', 'job', 'lesson_pdf', 2, 'task-2')
                """);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, origin_task_id)
                values ('ver-3-legacy', 'job', 'lesson_pdf', 3, null)
                """);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, origin_task_id)
                values ('ver-4-ghost', 'job', 'lesson_pdf', 4, 'task-gone')
                """);
    }

    @Test
    void regeneratingV1UsesTheFrozenInputsOfItsOwnRun() {
        var row = repository.loadRegenerationRowForVersion("ws-1", "job", 1);
        assertThat(row.userRequirement()).isEqualTo("要求A");
        assertThat(row.inputSnapshotId()).isEqualTo("snap-a");
        // 最新 run 编号仍来自全量（新 run 编号递增用），与输入来源解耦
        assertThat(row.latestRunNo()).isEqualTo(2);

        assertThat(repository.loadRegenerationRowForVersion("ws-1", "job", 2).userRequirement())
                .isEqualTo("要求B");
    }

    @Test
    void unrecoverableOriginFailsExplicitlyInsteadOfFallingBackToLatest() {
        assertFailureCode(3, "ARTIFACT_REGENERATION_SOURCE_UNRECOVERABLE");
        assertFailureCode(4, "ARTIFACT_REGENERATION_SOURCE_UNRECOVERABLE");
        assertFailureCode(99, "ARTIFACT_VERSION_NOT_FOUND");
    }

    private void assertFailureCode(int versionNo, String expectedCode) {
        try {
            repository.loadRegenerationRowForVersion("ws-1", "job", versionNo);
        } catch (BusinessException error) {
            assertThat(error.code()).isEqualTo(expectedCode);
            return;
        }
        throw new AssertionError("version " + versionNo + " must fail with " + expectedCode);
    }
}
