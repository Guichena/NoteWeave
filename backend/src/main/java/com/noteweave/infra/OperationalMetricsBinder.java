package com.noteweave.infra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetricsBinder {

    private static final String[] OUTBOX_STATUSES = {"READY", "PROCESSING", "SENT", "DEAD_LETTER"};
    private static final String[] SOURCE_STATUSES = {"PROCESSING", "READY", "FAILED", "DELETED"};
    private static final String[] PROJECTION_STATUSES = {"PENDING", "PROJECTED", "FAILED"};

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public OperationalMetricsBinder(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bind() {
        for (String status : OUTBOX_STATUSES) {
            Gauge.builder("noteweave.outbox.messages", this, ignored -> countByStatus("task_outbox", status))
                    .description("Task outbox messages by status")
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("noteweave.outbox.oldest_ready_age_seconds", this, ignored -> oldestReadyAgeSeconds())
                .description("Age in seconds of the oldest dispatchable task outbox message")
                .register(meterRegistry);
        for (String status : SOURCE_STATUSES) {
            Gauge.builder("noteweave.source.messages", this, ignored -> countByStatus("source", status))
                    .description("Sources by aggregate status")
                    .tag("status", status)
                    .register(meterRegistry);
        }
        for (String status : PROJECTION_STATUSES) {
            Gauge.builder("noteweave.source.projection_chunks", this,
                            ignored -> countByStatusColumn("source_chunk", "projection_status", status))
                    .description("Source chunks by Elasticsearch projection status")
                    .tag("status", status)
                    .register(meterRegistry);
        }
    }

    private double countByStatus(String table, String status) {
        return countByStatusColumn(table, "status", status);
    }

    private double countByStatusColumn(String table, String column, String status) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from " + table + " where " + column + " = ?", Long.class, status);
            return count == null ? 0.0 : count.doubleValue();
        } catch (DataAccessException ex) {
            return Double.NaN;
        }
    }

    private double oldestReadyAgeSeconds() {
        try {
            Timestamp oldest = jdbcTemplate.queryForObject("""
                    select min(created_at) from task_outbox
                    where status in ('READY', 'PROCESSING')
                    """, Timestamp.class);
            if (oldest == null) {
                return 0.0;
            }
            return Math.max(0L, Duration.between(oldest.toInstant(), Instant.now()).toSeconds());
        } catch (DataAccessException ex) {
            return Double.NaN;
        }
    }
}
