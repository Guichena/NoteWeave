package com.noteweave.knowledge;

import com.noteweave.common.Ids;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Wiki 页面索引的发件箱。入队与页面写入在同一事务，投递失败不回滚页面。 */
@Service
public class WikiIndexOutbox {
    private static final Logger log = LoggerFactory.getLogger(WikiIndexOutbox.class);
    static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final int EMBED_CHAR_LIMIT = 8_000;

    private final JdbcTemplate jdbcTemplate;
    private final WikiPageIndex pageIndex;
    private final EmbeddingClient embeddingClient;

    public WikiIndexOutbox(
            JdbcTemplate jdbcTemplate,
            WikiPageIndex pageIndex,
            EmbeddingClient embeddingClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.pageIndex = pageIndex;
        this.embeddingClient = embeddingClient;
    }

    public void enqueueUpsert(String workspaceId, String itemId) {
        enqueue(workspaceId, itemId, "UPSERT");
    }

    public void enqueueDelete(String workspaceId, String itemId) {
        enqueue(workspaceId, itemId, "DELETE");
    }

    @Scheduled(fixedDelayString = "${noteweave.knowledge.wiki-index-dispatch-delay-ms:30000}")
    public void dispatchPending() {
        if (pageIndex == null || !pageIndex.available()) {
            return;
        }
        List<Row> rows = jdbcTemplate.query("""
                select id, workspace_id, item_id, operation, attempts
                from wiki_index_outbox
                where status = 'PENDING'
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new Row(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("item_id"),
                rs.getString("operation"),
                rs.getInt("attempts")
        ), BATCH);
        for (Row row : rows) {
            dispatch(row);
        }
        backfill();
    }

    private void enqueue(String workspaceId, String itemId, String operation) {
        if (workspaceId == null || itemId == null || pending(itemId, operation)) {
            return;
        }
        String versionId = "DELETE".equals(operation) ? null : latestVersionId(itemId);
        jdbcTemplate.update("""
                insert into wiki_index_outbox(
                    id, workspace_id, item_id, version_id, operation, status, attempts
                ) values (?, ?, ?, ?, ?, 'PENDING', 0)
                """, Ids.newId(), workspaceId, itemId, versionId, operation);
    }

    private boolean pending(String itemId, String operation) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from wiki_index_outbox
                where item_id = ? and operation = ? and status = 'PENDING'
                """, Integer.class, itemId, operation);
        return count != null && count > 0;
    }

    private String latestVersionId(String itemId) {
        return jdbcTemplate.query("""
                select latest_version_id from knowledge_item where id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, itemId);
    }

    private void dispatch(Row row) {
        try {
            String error = "DELETE".equals(row.operation())
                    ? (pageIndex.delete(row.workspaceId(), row.itemId()) ? null : "WIKI_INDEX_WRITE_FAILED")
                    : upsert(row);
            if (error == null) {
                jdbcTemplate.update("""
                        update wiki_index_outbox
                        set status = 'SENT', updated_at = current_timestamp, last_error = null
                        where id = ? and status = 'PENDING'
                        """, row.id());
                return;
            }
            fail(row, error);
        } catch (RuntimeException ex) {
            log.warn("Wiki 索引发件箱投递失败: {}", ex.toString());
            fail(row, ex.getMessage() == null ? "WIKI_INDEX_WRITE_FAILED" : ex.getMessage());
        }
    }

    private String upsert(Row row) {
        Optional<Page> page = loadPage(row.workspaceId(), row.itemId());
        if (page.isEmpty()) {
            return pageIndex.delete(row.workspaceId(), row.itemId()) ? null : "WIKI_INDEX_WRITE_FAILED";
        }
        Page current = page.get();
        List<Float> embedding = null;
        if (embeddingClient != null && embeddingClient.isEnabled()) {
            embedding = embeddingClient.embedQuery(embedText(current)).singleVector();
        }
        boolean written = pageIndex.upsert(new WikiPageIndex.Document(
                current.workspaceId(),
                current.itemId(),
                current.versionId(),
                current.versionNo(),
                current.pageKind(),
                current.title(),
                current.summary(),
                current.content(),
                current.outgoingCount(),
                current.backlinkCount(),
                current.citationCount(),
                current.unresolvedCount(),
                current.updatedAtEpochMilli(),
                embedding));
        return written ? null : "WIKI_INDEX_WRITE_FAILED";
    }

    private void backfill() {
        List<PageRef> missing = jdbcTemplate.query("""
                select i.workspace_id, i.id
                from knowledge_item i
                where i.item_type = 'WIKI' and i.status = 'ACTIVE'
                  and not exists (
                      select 1 from wiki_index_outbox o
                      where o.item_id = i.id
                        and o.operation = 'UPSERT'
                        and o.status in ('PENDING', 'SENT')
                        and o.version_id = i.latest_version_id
                  )
                order by i.updated_at, i.id
                limit ?
                """, (rs, rowNum) -> new PageRef(rs.getString(1), rs.getString(2)), BATCH);
        for (PageRef page : missing) {
            enqueueUpsert(page.workspaceId(), page.itemId());
        }
    }

    private Optional<Page> loadPage(String workspaceId, String itemId) {
        return jdbcTemplate.query("""
                select i.workspace_id, i.id, coalesce(i.page_kind, 'TOPIC') as page_kind, i.title,
                       v.id as version_id, v.version_no, v.content, coalesce(v.summary, '') as summary,
                       i.updated_at,
                       (select count(*) from knowledge_item_link l where l.source_item_id = i.id) as outgoing_count,
                       (select count(*) from knowledge_item_link l where l.target_item_id = i.id) as backlink_count,
                       (select count(*) from knowledge_version_citation c
                         where c.knowledge_version_id = v.id) as citation_count,
                       (select count(*) from knowledge_item_link l
                         where l.source_item_id = i.id and l.relation_status = 'UNRESOLVED') as unresolved_count
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ? and i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                """, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            return Optional.of(new Page(
                    rs.getString("workspace_id"),
                    rs.getString("id"),
                    rs.getString("version_id"),
                    rs.getInt("version_no"),
                    rs.getString("page_kind"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("content"),
                    rs.getInt("outgoing_count"),
                    rs.getInt("backlink_count"),
                    rs.getInt("citation_count"),
                    rs.getInt("unresolved_count"),
                    updatedAt == null ? 0L : updatedAt.toInstant().toEpochMilli()));
        }, itemId, workspaceId);
    }

    private static String embedText(Page page) {
        String text = String.join("\n",
                page.title() == null ? "" : page.title(),
                page.summary() == null ? "" : page.summary(),
                page.content() == null ? "" : page.content()).trim();
        return text.length() <= EMBED_CHAR_LIMIT ? text : text.substring(0, EMBED_CHAR_LIMIT);
    }

    private void fail(Row row, String error) {
        int attempts = row.attempts() + 1;
        String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
        String message = error == null ? "" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbcTemplate.update("""
                update wiki_index_outbox
                set status = ?, attempts = ?, last_error = ?, updated_at = current_timestamp
                where id = ? and status = 'PENDING'
                """, status, attempts, message, row.id());
    }

    private record Row(String id, String workspaceId, String itemId, String operation, int attempts) {
    }

    private record PageRef(String workspaceId, String itemId) {
    }

    private record Page(
            String workspaceId,
            String itemId,
            String versionId,
            int versionNo,
            String pageKind,
            String title,
            String summary,
            String content,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount,
            long updatedAtEpochMilli
    ) {
    }
}
