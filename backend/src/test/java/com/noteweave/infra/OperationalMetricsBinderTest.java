package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OperationalMetricsBinderTest {

    @Test
    void databaseBacklogAndSourceGaugesShouldReflectCurrentRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:operational-metrics-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table task_outbox(id varchar(36), status varchar(32), created_at timestamp)");
        jdbcTemplate.execute("create table source(id varchar(36), status varchar(32))");
        jdbcTemplate.execute("create table source_chunk(id varchar(36), projection_status varchar(32))");
        jdbcTemplate.update("insert into task_outbox values ('o1', 'READY', current_timestamp)");
        jdbcTemplate.update("insert into task_outbox values ('o2', 'DEAD_LETTER', current_timestamp)");
        jdbcTemplate.update("insert into source values ('s1', 'READY')");
        jdbcTemplate.update("insert into source_chunk values ('c1', 'PENDING')");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OperationalMetricsBinder(jdbcTemplate, registry).bind();

        assertThat(registry.get("noteweave.outbox.messages").tag("status", "READY").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("noteweave.outbox.messages").tag("status", "DEAD_LETTER").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("noteweave.source.messages").tag("status", "READY").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("noteweave.source.projection_chunks").tag("status", "PENDING").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("noteweave.outbox.oldest_ready_age_seconds").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }
}
