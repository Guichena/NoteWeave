package com.noteweave.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实 MySQL 上的迁移验证。没有提供隔离库连接时跳过，不接触业务库。
 * 迁移脚本按 classpath:db/migration 的既有顺序执行，已执行脚本不修改。
 */
@EnabledIfEnvironmentVariable(named = "NOTEWEAVE_MYSQL_MIGRATION_URL", matches = ".+")
class MySqlMigrationVerificationTest {

    @Test
    void migrationsApplyCleanlyAndSecondRunIsNoOp() {
        String url = System.getenv("NOTEWEAVE_MYSQL_MIGRATION_URL");
        String user = System.getenv().getOrDefault("NOTEWEAVE_MYSQL_MIGRATION_USER", "root");
        String password = System.getenv().getOrDefault("NOTEWEAVE_MYSQL_MIGRATION_PASSWORD", "");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        MigrateResult first = flyway.migrate();
        assertThat(first.migrationsExecuted).isGreaterThan(0);
        assertThat(first.success).isTrue();

        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(second.success).isTrue();

        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
