package com.noteweave.research;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class ResearchRunListQueryRepository {

    private final ResearchRunReadRepository delegate;

    @Autowired
    public ResearchRunListQueryRepository(ResearchRunReadRepository delegate) {
        this.delegate = delegate;
    }

    /**
     * Compatibility constructor for focused tests and legacy callers that supplied JdbcTemplate
     * directly before the read repository was introduced.
     */
    public ResearchRunListQueryRepository(JdbcTemplate jdbcTemplate) {
        this(new ResearchRunReadRepository(jdbcTemplate, new ObjectMapper()));
    }

    public List<ResearchRunListRow> listRows(String workspaceId) {
        return delegate.listRows(workspaceId);
    }

    public List<ResearchRunListRow> listRows(String workspaceId, int limit, int offset) {
        return delegate.listRows(workspaceId, limit, offset);
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
        Instant updatedAt,
        String contextSnapshotId
) {
}
