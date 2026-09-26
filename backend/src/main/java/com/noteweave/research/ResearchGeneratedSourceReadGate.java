package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates the originating Research Run before exposing a generated Source. */
@Service
public class ResearchGeneratedSourceReadGate {
    private final JdbcTemplate jdbc;
    private final ResearchContextV2Gate contextGate;

    public ResearchGeneratedSourceReadGate(JdbcTemplate jdbc, ResearchContextV2Gate contextGate) {
        this.jdbc = jdbc;
        this.contextGate = contextGate;
    }

    public boolean visible(String workspaceId, String generatedBy, String generatedRefId) {
        if (!"research_agent".equals(generatedBy)) return true;
        if (generatedRefId == null || generatedRefId.isBlank()) {
            throw new BusinessException("RESEARCH_SOURCE_ORIGIN_INVALID",
                    "Generated Research Source has no origin", HttpStatus.CONFLICT);
        }
        Origin origin = jdbc.query("""
                select rr.context_snapshot_id, ris.replay_availability
                from research_run rr
                left join run_input_snapshot ris on ris.id = rr.context_snapshot_id
                where rr.id = ? and rr.workspace_id = ?
                """, rs -> rs.next() ? new Origin(rs.getString(1), rs.getString(2)) : null,
                generatedRefId, workspaceId);
        if (origin == null) {
            throw new BusinessException("RESEARCH_SOURCE_ORIGIN_INVALID",
                    "Generated Research Source origin is unavailable", HttpStatus.CONFLICT);
        }
        if (origin.snapshotId() == null) return true;
        if ("METADATA_ONLY".equals(origin.replayAvailability())) {
            return false;
        }
        contextGate.requireReadable(generatedRefId);
        return true;
    }

    public void requireReadable(String workspaceId, String generatedBy, String generatedRefId) {
        if (!visible(workspaceId, generatedBy, generatedRefId)) {
            throw new BusinessException("RESEARCH_SOURCE_CONTEXT_REDACTED",
                    "Generated Research Source is no longer readable", HttpStatus.CONFLICT);
        }
    }

    private record Origin(String snapshotId, String replayAvailability) {}
}
