package com.noteweave.retrieval.projection;

import com.noteweave.source.SourceCatalogVersionService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrievalBackfillSourceFinalizer {
    private final JdbcTemplate jdbcTemplate;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public RetrievalBackfillSourceFinalizer(
            JdbcTemplate jdbcTemplate,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
    }

    @Transactional
    public void markIndexed(String workspaceId, List<RetrievalBackfillService.SnapshotTarget> targets) {
        if (targets.isEmpty()) {
            return;
        }
        for (RetrievalBackfillService.SnapshotTarget target : targets) {
            int chunks = jdbcTemplate.update("""
                    update source_chunk
                    set projection_status = 'PROJECTED', projected_at = current_timestamp
                    where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                    """, workspaceId, target.sourceId(), target.snapshotId());
            if (chunks != target.chunkCount()) {
                throw new IllegalStateException("Backfill source chunk set changed before finalization");
            }
            int snapshot = jdbcTemplate.update("""
                    update source_snapshot ss
                    set index_status = 'INDEXED'
                    where ss.id = ? and ss.source_id = ? and ss.parse_status = 'PARSED'
                      and ss.version_no = (
                          select current_version from (
                              select max(current_ss.version_no) current_version
                              from source_snapshot current_ss where current_ss.source_id = ?
                          ) versions
                      )
                    """, target.snapshotId(), target.sourceId(), target.sourceId());
            if (snapshot != 1) {
                throw new IllegalStateException("Backfill snapshot is no longer current and parsed");
            }
            int source = jdbcTemplate.update("""
                    update source
                    set index_status = 'INDEXED', status = 'READY',
                        updated_by = 'SYSTEM:RETRIEVAL_BACKFILL', updated_at = current_timestamp
                    where id = ? and workspace_id = ? and status <> 'DELETED'
                    """, target.sourceId(), workspaceId);
            if (source != 1) {
                throw new IllegalStateException("Backfill source is no longer eligible for finalization");
            }
        }
        sourceCatalogVersionService.bump(workspaceId);
    }
}
