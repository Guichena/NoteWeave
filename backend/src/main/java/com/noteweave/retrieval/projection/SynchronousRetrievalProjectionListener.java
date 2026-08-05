package com.noteweave.retrieval.projection;

import com.noteweave.source.SourceParseService.SynchronousRetrievalProjectionRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SynchronousRetrievalProjectionListener {
    private static final Logger log = LoggerFactory.getLogger(SynchronousRetrievalProjectionListener.class);

    private final SourceRetrievalProjectionCoordinator coordinator;
    private final SourceRetrievalProjectionFinalizer finalizer;
    private final JdbcTemplate jdbcTemplate;

    public SynchronousRetrievalProjectionListener(
            SourceRetrievalProjectionCoordinator coordinator,
            SourceRetrievalProjectionFinalizer finalizer,
            JdbcTemplate jdbcTemplate
    ) {
        this.coordinator = coordinator;
        this.finalizer = finalizer;
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(SynchronousRetrievalProjectionRequested event) {
        try {
            coordinator.projectAndFinalize(event.workspaceId(), event.sourceId(),
                    event.sourceSnapshotId(), event.taskId());
            jdbcTemplate.update("""
                    update task_outbox set status = 'SENT', sent_at = current_timestamp, last_error = null
                    where id = ? and status = 'READY'
                    """, event.outboxId());
        } catch (RuntimeException ex) {
            finalizer.finalizeFailed(
                    event.workspaceId(),
                    event.sourceId(),
                    event.sourceSnapshotId(),
                    event.taskId(),
                    "RETRIEVAL_PROJECTION_FAILED"
            );
            jdbcTemplate.update("""
                    update task_outbox
                    set status = 'DEAD_LETTER', attempt_count = attempt_count + 1,
                        last_error = ?, dead_lettered_at = current_timestamp
                    where id = ? and status = 'READY'
                    """, "RETRIEVAL_PROJECTION_FAILED", event.outboxId());
            log.error("Synchronous retrieval projection failed: sourceId={}, snapshotId={}",
                    event.sourceId(), event.sourceSnapshotId(), ex);
        }
    }
}
