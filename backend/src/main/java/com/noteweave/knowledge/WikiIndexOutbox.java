package com.noteweave.knowledge;

import com.noteweave.common.Ids;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Wiki 页面索引的发件箱。入队与页面写入在同一事务，投递失败不回滚页面。
 *
 * <p>B-07 多实例与旧写防护：入队递增 knowledge_item.index_generation（UPSERT 与 DELETE 均递增），
 * 行携带 desired_generation；投递以租约 CAS 认领；消费前重新读取最新知识版本与当前代次，
 * 行代次已被取代时按 SUPERSEDED 确认；删除写持久墓碑文档，迟到的旧 UPSERT 被
 * ES external version 拒绝，不能复活已删除页面。
 */
@Service
public class WikiIndexOutbox {
    private static final Logger log = LoggerFactory.getLogger(WikiIndexOutbox.class);
    static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_SECONDS = 60;
    private static final long LEASE_SECONDS = 120;
    private static final int EMBED_CHAR_LIMIT = 8_000;

    private final JdbcTemplate jdbcTemplate;
    private final WikiPageIndex pageIndex;
    private final EmbeddingClient embeddingClient;
    private final String instanceId = "wiki-index-" + UUID.randomUUID();

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
                select id, workspace_id, item_id, version_id, operation, attempts, desired_generation
                from wiki_index_outbox
                where status in ('PENDING', 'PROCESSING')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                  and (lease_until is null or lease_until < current_timestamp)
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new Row(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("item_id"),
                rs.getString("version_id"),
                rs.getString("operation"),
                rs.getInt("attempts"),
                rs.getLong("desired_generation")
        ), BATCH);
        for (Row row : rows) {
            dispatch(row);
        }
        backfill();
    }

    private void enqueue(String workspaceId, String itemId, String operation) {
        if (workspaceId == null || itemId == null) {
            return;
        }
        // IDX-01：先算目标版本，再按 (条目, 操作, 目标版本) 去重。
        // 同版本的重复入队（写路径与 backfill 撞车、在飞行的行）合并成一行；
        // 新版本不受在飞行的旧行影响，不必等到下一轮 backfill 才补上。
        String versionId = "DELETE".equals(operation) ? null : latestVersionId(itemId);
        if (pending(itemId, operation, versionId)) {
            return;
        }
        // 入队与页面写入同事务：UPSERT 与 DELETE 都递增条目级单调代次
        jdbcTemplate.update("update knowledge_item set index_generation = index_generation + 1 where id = ?", itemId);
        Long generation = jdbcTemplate.query("select index_generation from knowledge_item where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, itemId);
        jdbcTemplate.update("""
                insert into wiki_index_outbox(
                    id, workspace_id, item_id, version_id, operation, status, attempts,
                    desired_generation, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, ?, current_timestamp, current_timestamp)
                """, Ids.newId(), workspaceId, itemId, versionId, operation,
                generation == null ? 1L : generation);
    }

    /** 同一目标版本已有待投递或在飞行中的行时不再重复入队；租约过期的行由 dispatchPending 续租重试。 */
    private boolean pending(String itemId, String operation, String versionId) {
        Integer count = versionId == null
                ? jdbcTemplate.queryForObject("""
                        select count(*) from wiki_index_outbox
                        where item_id = ? and operation = ? and version_id is null
                          and status in ('PENDING', 'PROCESSING')
                        """, Integer.class, itemId, operation)
                : jdbcTemplate.queryForObject("""
                        select count(*) from wiki_index_outbox
                        where item_id = ? and operation = ? and version_id = ?
                          and status in ('PENDING', 'PROCESSING')
                        """, Integer.class, itemId, operation, versionId);
        return count != null && count > 0;
    }

    private String latestVersionId(String itemId) {
        return jdbcTemplate.query("""
                select latest_version_id from knowledge_item where id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, itemId);
    }

    private void dispatch(Row row) {
        // 认领租约：CAS 防多实例重复投递
        int claimed = jdbcTemplate.update("""
                update wiki_index_outbox
                set status = 'PROCESSING', lease_owner = ?, lease_until = timestampadd(second, ?, current_timestamp),
                    updated_at = current_timestamp
                where id = ? and status in ('PENDING', 'PROCESSING')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                  and (lease_until is null or lease_until < current_timestamp)
                """, instanceId, LEASE_SECONDS, row.id());
        if (claimed != 1) {
            return;
        }
        try {
            // 消费前读取当前权威代次：行代次已被更高代次取代时按 SUPERSEDED 确认。
            // current == null（条目行已不存在）按 SUPERSEDED 收敛；V199 旧行的 0 代次
            // 按下限 1 写入（v2 修订），绝不写 0 版本。
            Long current = jdbcTemplate.query("select index_generation from knowledge_item where id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, row.itemId());
            if (current == null || current > row.desiredGeneration()) {
                markSent(row, "SUPERSEDED");
                return;
            }
            long generation = Math.max(1L, current);
            String error = "DELETE".equals(row.operation())
                    ? (pageIndex.delete(row.workspaceId(), row.itemId(), generation) ? null : "WIKI_INDEX_WRITE_FAILED")
                    : upsert(row, generation);
            if (error == null) {
                markSent(row, null);
                return;
            }
            fail(row, error);
        } catch (RuntimeException ex) {
            log.warn("Wiki 索引发件箱投递失败: {}", ex.toString());
            fail(row, ex.getMessage() == null ? "WIKI_INDEX_WRITE_FAILED" : ex.getMessage());
        }
    }

    /** 消费前读取最新知识版本；条目已不可见时按删除写墓碑。 */
    private String upsert(Row row, long generation) {
        Optional<Page> page = loadPage(row.workspaceId(), row.itemId());
        if (page.isEmpty()) {
            return pageIndex.delete(row.workspaceId(), row.itemId(), generation)
                    ? null : "WIKI_INDEX_WRITE_FAILED";
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
                embedding), generation);
        return written ? null : "WIKI_INDEX_WRITE_FAILED";
    }

    private static String embedText(Page page) {
        String text = String.join(Character.toString(10),
                page.title() == null ? "" : page.title(),
                page.summary() == null ? "" : page.summary(),
                page.content() == null ? "" : page.content()).trim();
        return text.length() <= EMBED_CHAR_LIMIT ? text : text.substring(0, EMBED_CHAR_LIMIT);
    }

    private void markSent(Row row, String note) {
        jdbcTemplate.update("""
                update wiki_index_outbox
                set status = 'SENT', updated_at = current_timestamp, last_error = ?, lease_owner = null,
                    lease_until = null, next_attempt_at = null
                where id = ? and lease_owner = ?
                """, note, row.id(), instanceId);
    }

    /**
     * IDX-01：补齐没有索引行的页面。
     *
     * <p>同一个 latest version 只要已有行就算「已存在」，不论它在 PENDING、在飞行中（PROCESSING，
     * 含租约已过期的超租约行，交给 dispatchPending 续租重试）、已成功（SENT）还是已永久失败（FAILED）。
     * 之前只认 PENDING/SENT，导致两件事：永久失败的页每 30s 被重新入队，永远失败下去；
     * 而且这些页会一直占满批次前 20 名，后面的缺页永远补不上。永久失败的页现在交给
     * {@link #redriveFailed} 人工恢复，指标见 {@link #metrics()}。
     */
    private void backfill() {
        List<PageRef> missing = jdbcTemplate.query("""
                select i.workspace_id, i.id
                from knowledge_item i
                where i.item_type = 'WIKI' and i.status = 'ACTIVE'
                  and not exists (
                      select 1 from wiki_index_outbox o
                      where o.item_id = i.id
                        and o.operation = 'UPSERT'
                        and o.status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED')
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

    private void fail(Row row, String error) {
        int attempts = row.attempts() + 1;
        boolean terminal = attempts >= MAX_ATTEMPTS;
        String message = error == null ? "" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbcTemplate.update("""
                update wiki_index_outbox
                set status = ?, attempts = ?, last_error = ?, updated_at = current_timestamp,
                    lease_owner = null, lease_until = null,
                    next_attempt_at = case when ? then null else timestampadd(second, ?, current_timestamp) end
                where id = ? and lease_owner = ?
                """, terminal ? "FAILED" : "PENDING", attempts, message, terminal,
                RETRY_BACKOFF_SECONDS, row.id(), instanceId);
    }

    /** FB-08：投递视角指标与受控人工恢复（仅当前权威代次的 FAILED 行可重驱）。 */
    public java.util.Map<String, Long> metrics() {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total,
                       coalesce(max(timestampdiff(second, created_at, current_timestamp)), 0) as oldest_age
                from wiki_index_outbox group by status
                """, rs -> {
            counts.put("wiki-index." + rs.getString("status") + ".count", rs.getLong("total"));
            counts.put("wiki-index." + rs.getString("status") + ".oldest_age_seconds", rs.getLong("oldest_age"));
        });
        return counts;
    }

    /**
     * 只重驱仍是当前权威代次、且仍指向 latest version 的 FAILED 行。
     * IDX-01：代次相等不等于版本最新——入队被去重跳过时代次不会递增，所以版本也要单独判。
     */
    public boolean redriveFailed(String outboxId, String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("WIKI_INDEX_REDRIVE_IDENTITY_REQUIRED");
        }
        Integer eligible = jdbcTemplate.queryForObject("""
                select count(*) from wiki_index_outbox o
                join knowledge_item i on i.id = o.item_id
                where o.id = ? and o.status = 'FAILED'
                  and o.desired_generation = i.index_generation
                  and (o.version_id is null or o.version_id = i.latest_version_id)
                """, Integer.class, outboxId);
        if (eligible == null || eligible == 0) {
            return false;
        }
        return jdbcTemplate.update("""
                update wiki_index_outbox
                set status = 'PENDING', attempts = 0, next_attempt_at = null,
                    last_error = ?, updated_at = current_timestamp
                where id = ? and status = 'FAILED'
                """, "redriven by " + actor + ": " + reason.substring(0, Math.min(400, reason.length())),
                outboxId) == 1;
    }

    private record Row(String id, String workspaceId, String itemId, String versionId, String operation,
                       int attempts, long desiredGeneration) {
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
