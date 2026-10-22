package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** 只在独立空测试库验证全量迁移，以及状态版本改造前的数据库升级。 */
public final class MySqlCompletionMigrationReplay {
    public static void main(String[] args) throws Exception {
        String url = required("NOTEWEAVE_MIGRATION_JDBC_URL");
        String mode = required("NOTEWEAVE_MIGRATION_MODE");
        if (!url.matches("jdbc:mysql://127[.]0[.]0[.]1:3306/noteweave_validation(?:_upgrade)?[?].*")
                || !List.of("FRESH", "UPGRADE").contains(mode)) {
            throw new IllegalArgumentException("迁移回放只允许独立空测试库与明确的迁移模式");
        }
        try (Connection connection = DriverManager.getConnection(url, "root", "")) {
            require(scalar(connection, "select count(*) from information_schema.tables where table_schema=database()") == 0,
                    "测试库非空，拒绝覆盖现有数据");
            String location = "filesystem:" + required("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration";
            if (mode.equals("UPGRADE")) {
                Flyway.configure().dataSource(url, "root", "").locations(location).target("168").cleanDisabled(true).load().migrate();
                seedLegacyRun(connection);
            }
            Flyway flyway = Flyway.configure().dataSource(url, "root", "").locations(location).cleanDisabled(true).load();
            var migration = flyway.migrate();
            flyway.validate();
            require(flyway.migrate().migrationsExecuted == 0, "重复迁移不应执行已有版本");
            require(scalar(connection, "select count(*) from flyway_schema_history where success=0") == 0, "迁移存在失败版本");
            require(scalar(connection, "select max(cast(version as unsigned)) from flyway_schema_history where success=1") == 179,
                    "迁移未到达当前版本");
            require(scalar(connection, "select count(*) from flyway_schema_history where cast(version as unsigned) between 169 and 179 and success=1") == 11,
                    "需求改造迁移未全部应用");
            for (String table : List.of("research_requirement", "research_requirement_fact", "research_requirement_revision",
                    "research_synthesis_upload_part", "research_synthesis_generation", "research_synthesis_call_permit",
                    "research_report_feedback", "research_runtime_metric", "research_cell_gain")) {
                require(scalar(connection, "select count(*) from information_schema.tables where table_schema=database() and table_name='" + table + "'") == 1,
                        "缺少迁移表：" + table);
            }
            require(scalar(connection, "select count(*) from information_schema.columns where table_schema=database() and table_name in ('research_runtime_metric','research_cell_gain') and column_name='event_seq' and extra='auto_increment'") == 2,
                    "事件顺序列未正确创建");
            if (mode.equals("UPGRADE")) {
                require(scalar(connection, "select count(*) from research_run where id='migration-legacy-run' and question='legacy question' and agent_feature_flags_json->>'$.requirement_coverage_v1'='false'") == 1,
                        "旧 Run 的内容或冻结配置发生变化");
                require(scalar(connection, "select count(*) from research_requirement where research_run_id='migration-legacy-run'") == 0,
                        "旧 Run 被静默填入新版需求");
            }
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("schema_version", "research-mysql-migration-replay.v1");
            result.put("state", "PASSED"); result.put("mode", mode);
            result.put("migrations_executed", migration.migrationsExecuted);
            result.put("latest_version", 179); result.put("provider_calls", 0);
            var report = Path.of(required("NOTEWEAVE_MIGRATION_REPORT"));
            Files.createDirectories(report.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), result);
            System.out.println(new ObjectMapper().writeValueAsString(result));
        }
    }
    private static void seedLegacyRun(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("insert into workspace(id,owner_id,name,status) values ('migration-workspace','local-user','migration fixture','ACTIVE')");
            sql.executeUpdate("insert into task(id,workspace_id,task_type,task_status,target_type,target_id) values ('migration-task','migration-workspace','RESEARCH_RUN','RUNNING','RESEARCH_RUN','migration-legacy-run')");
            sql.executeUpdate("insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,status,agent_feature_flags_json) values ('migration-legacy-run','migration-workspace','migration-task','legacy question','DEFAULT','[]','RUNNING','{\"requirement_coverage_v1\":false}')");
        }
    }
    private static long scalar(Connection connection, String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            if (!result.next()) throw new IllegalStateException("缺少数据库验证结果");
            return result.getLong(1);
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少测试配置：" + key);
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
