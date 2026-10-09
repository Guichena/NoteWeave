package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.task.TaskService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SegmentSummaryPromotionService {

    private final JdbcTemplate jdbcTemplate;
    private final TaskService taskService;
    private final RunReplayRedactionService replayRedactionService;

    public SegmentSummaryPromotionService(JdbcTemplate jdbcTemplate, TaskService taskService,
                                          RunReplayRedactionService replayRedactionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
        this.replayRedactionService = replayRedactionService;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public PromotionResult promote(String segmentId, String revisionId, PromoteSegmentSummaryRequest request) {
        LockedRevision revision = jdbcTemplate.query("""
                select revision.id, revision.status, revision.source_segment_version, segment.lock_version
                from segment_summary_revision revision
                join conversation_segment segment on segment.id = revision.segment_id
                where revision.id = ? and revision.segment_id = ?
                for update
                """, rs -> rs.next() ? new LockedRevision(
                rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)) : null, revisionId, segmentId);
        if (revision == null) {
            throw new BusinessException("SEGMENT_SUMMARY_REVISION_NOT_FOUND", "Segment summary revision does not exist", HttpStatus.NOT_FOUND);
        }
        if (!"BUILDING".equals(revision.status())) {
            throw new BusinessException("SEGMENT_SUMMARY_PROMOTION_INVALID", "Only BUILDING summaries can be promoted", HttpStatus.CONFLICT);
        }
        if (revision.sourceSegmentVersion() != revision.segmentLockVersion()) {
            jdbcTemplate.update("""
                    update segment_summary_revision set status = 'STALE'
                    where id = ? and status = 'BUILDING'
                    """, revision.id());
            throw new BusinessException("SEGMENT_SUMMARY_PROMOTION_STALE",
                    "Segment changed while the summary was being built", HttpStatus.CONFLICT);
        }
        int promoted = jdbcTemplate.update("""
                update segment_summary_revision
                set status = 'READY', summary_text = ?, content_hash = ?, summary_method = ?,
                    ready_at = current_timestamp
                where id = ? and status = 'BUILDING'
                """, request.summaryText(), request.contentHash(), request.summaryMethod(), revision.id());
        if (promoted != 1) {
            throw new BusinessException("SEGMENT_SUMMARY_PROMOTION_CONFLICT",
                    "Segment summary revision was changed concurrently", HttpStatus.CONFLICT);
        }
        completeSummaryTasks(revision.id(), segmentId);
        return new PromotionResult(segmentId, revisionId, "READY");
    }

    @Transactional
    public PromotionResult delete(String segmentId, String revisionId) {
        int deleted = jdbcTemplate.update("""
                update segment_summary_revision
                set status = 'DELETED', summary_text = '', content_hash = null
                where id = ? and segment_id = ? and status <> 'DELETED'
                """, revisionId, segmentId);
        if (deleted == 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from segment_summary_revision where id = ? and segment_id = ?",
                    Integer.class, revisionId, segmentId);
            if (exists == null || exists == 0) {
                throw new BusinessException("SEGMENT_SUMMARY_REVISION_NOT_FOUND", "Segment summary revision does not exist", HttpStatus.NOT_FOUND);
            }
        }
        replayRedactionService.redactDeletedSummaryRevision(revisionId);
        return new PromotionResult(segmentId, revisionId, "DELETED");
    }

    private void completeSummaryTasks(String revisionId, String segmentId) {
        List<String> taskIds = jdbcTemplate.query("""
                select id from task
                where target_type = 'SEGMENT_SUMMARY_REVISION' and target_id = ?
                  and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, (rs, rowNum) -> rs.getString("id"), revisionId);
        for (String taskId : taskIds) {
            String status = jdbcTemplate.queryForObject(
                    "select task_status from task where id = ?", String.class, taskId);
            if ("PENDING".equals(status)) {
                taskService.startTask(taskId);
            }
            taskService.completeTask(taskId, "COMPLETED", "Conversation segment summary is ready", segmentId);
        }
    }

    public record PromotionResult(String segmentId, String revisionId, String status) {
    }

    private record LockedRevision(String id, String status, int sourceSegmentVersion, int segmentLockVersion) {
    }
}
