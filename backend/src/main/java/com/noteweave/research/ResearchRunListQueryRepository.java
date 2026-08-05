package com.noteweave.research;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResearchRunListQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ResearchRunListQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ResearchRunListRow> listRows(String workspaceId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, source_scope_json,
                       resumed_from_research_run_id, resumed_from_checkpoint_no, status,
                       final_report_title, final_report_markdown, report_source_id, created_at, updated_at
                from research_run
                where workspace_id = ?
                order by updated_at desc, created_at desc, id desc
                """, (rs, rowNum) -> new ResearchRunListRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                rs.getString("question"),
                rs.getString("profile_key"),
                rs.getString("source_scope_json"),
                blank(rs.getString("resumed_from_research_run_id")),
                (Integer) rs.getObject("resumed_from_checkpoint_no"),
                rs.getString("status"),
                blank(rs.getString("final_report_title")),
                rs.getString("final_report_markdown"),
                blank(rs.getString("report_source_id")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        ), workspaceId);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }
}

record ResearchRunListRow(
        String researchRunId,
        String workspaceId,
        String taskId,
        String question,
        String profileKey,
        String sourceScopeJson,
        String resumedFromResearchRunId,
        Integer resumedFromCheckpointNo,
        String status,
        String finalReportTitle,
        String finalReportMarkdown,
        String reportSourceId,
        Instant createdAt,
        Instant updatedAt
) {
}
