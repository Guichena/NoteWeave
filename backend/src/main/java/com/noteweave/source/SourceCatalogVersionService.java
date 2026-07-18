package com.noteweave.source;

import com.noteweave.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SourceCatalogVersionService {

    private final JdbcTemplate jdbcTemplate;

    public SourceCatalogVersionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long current(String workspaceId) {
        Long version = jdbcTemplate.query("""
                select source_catalog_version from workspace where id = ?
                """, rs -> rs.next() ? rs.getLong("source_catalog_version") : null, workspaceId);
        if (version == null) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return version;
    }

    public void bump(String workspaceId) {
        int updated = jdbcTemplate.update("""
                update workspace
                set source_catalog_version = source_catalog_version + 1
                where id = ?
                """, workspaceId);
        if (updated != 1) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }
}
