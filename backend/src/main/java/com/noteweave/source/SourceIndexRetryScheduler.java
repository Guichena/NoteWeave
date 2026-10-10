package com.noteweave.source;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 检索索引失败后的自动重试。失败收尾时已按退避写入 next_index_retry_at，
 * 这里定期找出到期的快照，从向量化阶段重新投递。重试次数用尽或错误不可重试时不会写入重试时间。
 * 多个实例同时运行时，靠快照状态的条件更新保证同一个快照只会被重新投递一次。
 */
@Component
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class SourceIndexRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourceIndexRetryScheduler.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbcTemplate;
    private final SourceReprocessService reprocessService;
    private final TransactionTemplate transactionTemplate;

    public SourceIndexRetryScheduler(JdbcTemplate jdbcTemplate, SourceReprocessService reprocessService,
                                     PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.reprocessService = reprocessService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${noteweave.source.index-retry-poll-ms:30000}")
    public void retryDueSnapshots() {
        List<Due> due = jdbcTemplate.query("""
                select s.workspace_id, s.id, ss.id, ss.index_attempt_count
                from source_snapshot ss
                join source s on s.id = ss.source_id
                where ss.index_status = 'FAILED' and ss.parse_status = 'PARSED'
                  and ss.next_index_retry_at is not null and ss.next_index_retry_at <= current_timestamp
                  and s.status = 'FAILED' and s.index_status = 'FAILED'
                order by ss.next_index_retry_at
                limit ?
                """, (rs, rowNum) -> new Due(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                BATCH_SIZE);
        for (Due item : due) {
            try {
                String taskId = transactionTemplate.execute(status -> reprocessService.requeueIndexing(
                        item.workspaceId(), item.sourceId(), item.snapshotId(),
                        "自动重试检索索引（第 " + (item.attempts() + 1) + " 次）", true));
                if (taskId != null) {
                    log.info("Auto retry retrieval index: sourceId={}, snapshotId={}, attempt={}, taskId={}",
                            item.sourceId(), item.snapshotId(), item.attempts() + 1, taskId);
                }
            } catch (RuntimeException ex) {
                log.warn("Auto retry retrieval index failed to queue: sourceId={}, reason={}",
                        item.sourceId(), ex.getMessage());
            }
        }
    }

    private record Due(String workspaceId, String sourceId, String snapshotId, int attempts) {
    }
}
