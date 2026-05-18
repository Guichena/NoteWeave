package com.noteweave.admin.service;

import com.noteweave.admin.dto.DashboardSummaryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary() {
        long taskCount = count("select count(*) from task");
        long failedTaskCount = count("select count(*) from task where task_status in ('FAILED', 'TIMEOUT')");
        return DashboardSummaryResponse.builder()
                .userCount(count("select count(*) from users"))
                .activeUserCount(count("select count(*) from users where status = 'ACTIVE'"))
                .spaceCount(count("select count(*) from space"))
                .knowledgeBaseCount(count("select count(*) from knowledge_base"))
                .documentCount(count("select count(*) from document where deleted_at is null and status <> 'DELETED'"))
                .artifactCount(count("select count(*) from artifact where deleted_at is null"))
                .taskCount(taskCount)
                .failedTaskCount(failedTaskCount)
                .runningTaskCount(count("select count(*) from task where task_status = 'RUNNING'"))
                .taskFailureRate(rate(failedTaskCount, taskCount))
                .storageBytes(count("select coalesce(sum(size), 0) from file_object"))
                .indexedDocumentCount(count("select count(*) from document where status = 'INDEXED' and deleted_at is null"))
                .documentIndexFailedCount(count("select count(*) from document where status = 'FAILED' and deleted_at is null"))
                .llmCallCount(count("select count(*) from llm_call_log"))
                .llmSuccessCount(count("select count(*) from llm_call_log where success = b'1'"))
                .llmTotalTokens(count("select coalesce(sum(total_tokens), 0) from llm_call_log"))
                .build();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0L) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
