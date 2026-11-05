package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.source.SourceWikiCommandPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手工编辑过的 Wiki 页面恢复自动同步。
 *
 * <p>人工编辑后页面记为 MANUAL，自动 ingest 一律跳过；此前没有任何入口能改回来，页面只能一直停在人工版本。
 * 恢复后页面状态改为 AUTO：资料页立刻按来源重新 ingest，其余页面在下一次工作台 ingest 时更新。
 * 重新生成之前，最新版本仍是用户写的内容（没有自动编译标记、写入者不是系统），认证门按用户内容处理，
 * 不会因为状态改成 AUTO 而让回答失败。</p>
 */
@Service
public class WikiAutoSyncService {

    /** 恢复自动同步时记下的写入者；认证门据此把尚未重新生成的最新版本当作用户内容。 */
    public static final String RESUME_ACTOR = "SYSTEM:WIKI_RESUME";

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final SourceWikiCommandPort sourceWikiCommandPort;
    private final KnowledgeQueryService knowledgeQueryService;

    public WikiAutoSyncService(JdbcTemplate jdbcTemplate, WorkspaceAccessGuard workspaceAccessGuard,
                               SourceWikiCommandPort sourceWikiCommandPort,
                               KnowledgeQueryService knowledgeQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.sourceWikiCommandPort = sourceWikiCommandPort;
        this.knowledgeQueryService = knowledgeQueryService;
    }

    @Transactional
    public KnowledgeItemResponse resumeAutoSync(String itemId) {
        workspaceAccessGuard.requireKnowledgeItemPermission(itemId, WorkspacePermission.KNOWLEDGE_WRITE);
        Page page = jdbcTemplate.query("""
                select workspace_id, item_type, status, title, origin_source_id
                from knowledge_item where id = ? for update
                """, rs -> rs.next() ? new Page(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)) : null, itemId);
        if (page == null || !"ACTIVE".equals(page.status())) {
            throw new BusinessException("KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在", HttpStatus.NOT_FOUND);
        }
        if (!"WIKI".equals(page.itemType())) {
            throw new BusinessException("KNOWLEDGE_AUTO_SYNC_UNSUPPORTED", "只有 Wiki 页面可以恢复自动同步");
        }
        jdbcTemplate.update("""
                update knowledge_item set wiki_page_state = 'AUTO', updated_by = ?, updated_at = current_timestamp
                where id = ?
                """, RESUME_ACTOR, itemId);
        jdbcTemplate.update("""
                insert into wiki_log_entry(id, workspace_id, item_id, event_type, message)
                values (?, ?, ?, 'RESUME_AUTO_SYNC', ?)
                """, com.noteweave.common.Ids.newId(), page.workspaceId(), itemId,
                "页面恢复自动同步：" + page.title());
        if (page.originSourceId() != null && !page.originSourceId().isBlank()) {
            sourceWikiCommandPort.requestSourceIngest(page.workspaceId(), page.originSourceId());
        }
        return knowledgeQueryService.listItems(page.workspaceId(), null).stream()
                .filter(item -> item.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在",
                        HttpStatus.NOT_FOUND));
    }

    private record Page(String workspaceId, String itemType, String status, String title, String originSourceId) { }
}
