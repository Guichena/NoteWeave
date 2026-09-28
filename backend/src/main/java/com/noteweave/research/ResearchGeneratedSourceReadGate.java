package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    public Set<String> readableSourceIds(String workspaceId, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        Object[] parameters = new Object[ids.size() + 1];
        parameters[0] = workspaceId;
        for (int i = 0; i < ids.size(); i++) parameters[i + 1] = ids.get(i);
        List<SourceOrigin> sources = jdbc.query("""
                select id, coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source where workspace_id = ? and status = 'READY' and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (rs, rowNum) -> new SourceOrigin(rs.getString(1), rs.getString(2), rs.getString(3)),
                parameters);
        LinkedHashSet<String> readable = new LinkedHashSet<>();
        for (SourceOrigin source : sources) {
            if (visible(workspaceId, source.generatedBy(), source.generatedRefId())) {
                readable.add(source.sourceId());
            }
        }
        return Set.copyOf(readable);
    }

    private record Origin(String snapshotId, String replayAvailability) {}
    private record SourceOrigin(String sourceId, String generatedBy, String generatedRefId) {}
}
