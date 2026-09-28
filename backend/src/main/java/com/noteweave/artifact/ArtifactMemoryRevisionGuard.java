package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks the exact Memory revisions logged for a frozen Artifact Run. */
@Component
public class ArtifactMemoryRevisionGuard {
    private final JdbcTemplate jdbc;

    public ArtifactMemoryRevisionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireActive(String taskId) {
        if (taskId == null || taskId.isBlank()) return; // Historical versions may have no Run.
        Integer stale = jdbc.queryForObject("""
                select count(*) from memory_usage_log u
                where u.target_type = 'ARTIFACT_JOB_RUN' and u.target_id = ?
                  and u.memory_revision_id is not null
                  and not exists (
                    select 1 from memory_item i
                    join memory_runtime_revision r on r.id = i.current_revision_id
                    where i.id = u.memory_item_id and i.workspace_id = u.workspace_id
                      and r.id = u.memory_revision_id
                      and i.status = 'ACTIVE' and r.status = 'ACTIVE'
                      and r.valid_from <= current_timestamp
                      and (r.valid_until is null or r.valid_until > current_timestamp)
                  )
                """, Integer.class, taskId);
        if (stale != null && stale > 0) {
            throw new BusinessException("ARTIFACT_MEMORY_REVOKED",
                    "产物使用的 Memory 版本已撤销或更新", HttpStatus.CONFLICT);
        }
    }
}
