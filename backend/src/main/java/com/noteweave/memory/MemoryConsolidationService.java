package com.noteweave.memory;

import com.noteweave.chat.ChatLlmClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 后台整理：合并近似重复，并把长期没有强化的新记忆送回复核。
 * 模型不可用时不合并。历史观察记录不因为缺少强化次数而被移出控制包。
 */
@Service
public class MemoryConsolidationService {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private static final String MERGE_PROMPT = """
            判断下面两条记忆是否在说同一条回答偏好。
            只输出 JSON：{"merge": true} 或 {"merge": false}。不要解释。""";
    private static final int MERGE_BATCH_SIZE = 40;
    private static final Timestamp MERGE_CURSOR_ORIGIN = Timestamp.from(Instant.EPOCH);
    private static final String MERGE_CURSOR_ORIGIN_ID = "";
    private static final String MERGE_BATCH_SQL = """
            select i.id, i.workspace_id, i.reinforcement_count, i.semantic_slot_key, r.display_text, i.created_at
            from memory_item i
            join memory_runtime_revision r on r.id = i.current_revision_id
            where i.status = 'ACTIVE' and i.review_status = 'APPROVED' and r.status = 'ACTIVE'
              and (i.created_at > ? or (i.created_at = ? and i.id > ?))
            order by i.created_at, i.id
            limit 40
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ChatLlmClient llmClient;
    private final MemoryStatementMatcher statementMatcher;
    private final MemoryEvidenceService evidenceService;
    private final CanonicalMemoryReviewService reviewService;
    private final boolean enabled;
    private final int staleDays;
    /** 实例内 keyset 游标：上一批末尾的 (created_at, id)；扫到末尾后复位到起始点。 */
    private ConsolidationCursor mergeCursor = new ConsolidationCursor(MERGE_CURSOR_ORIGIN, MERGE_CURSOR_ORIGIN_ID);

    public MemoryConsolidationService(
            JdbcTemplate jdbcTemplate,
            ChatLlmClient llmClient,
            MemoryStatementMatcher statementMatcher,
            MemoryEvidenceService evidenceService,
            CanonicalMemoryReviewService reviewService,
            @Value("${noteweave.memory.consolidation-enabled:true}") boolean enabled,
            @Value("${noteweave.memory.consolidation-stale-days:90}") int staleDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmClient = llmClient;
        this.statementMatcher = statementMatcher;
        this.evidenceService = evidenceService;
        this.reviewService = reviewService;
        this.enabled = enabled;
        this.staleDays = staleDays;
    }

    @Scheduled(
            initialDelayString = "${noteweave.memory.consolidation-delay-ms:3600000}",
            fixedDelayString = "${noteweave.memory.consolidation-delay-ms:3600000}"
    )
    public void scheduled() {
        try {
            consolidate();
        } catch (RuntimeException ex) {
            log.warn("Memory consolidation failed: {}", ex.getMessage());
        }
    }

    public int consolidate() {
        if (!enabled) {
            return 0;
        }
        int flagged = flagStale();
        if (llmClient == null || !llmClient.isEnabled()) {
            log.info("Memory consolidation merge skipped: MEMORY_CONSOLIDATION_JUDGE_UNAVAILABLE");
            return flagged;
        }
        return flagged + mergeDuplicates();
    }

    private int flagStale() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(Math.max(1, staleDays))));
        return jdbcTemplate.update("""
                update memory_item
                set review_status = 'REVIEW_REQUIRED', updated_at = current_timestamp
                where status = 'ACTIVE' and review_status = 'APPROVED'
                  and slot_schema_version = 'candidate-v3'
                  and reinforcement_count = 0
                  and last_reinforced_at is not null
                  and last_reinforced_at < ?
                """, cutoff);
    }

    private synchronized int mergeDuplicates() {
        ConsolidationCursor start = mergeCursor;
        List<Item> items = jdbcTemplate.query(MERGE_BATCH_SQL, (rs, rowNum) -> new Item(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getInt("reinforcement_count"),
                MemorySlots.publicSlot(rs.getString("semantic_slot_key")),
                rs.getString("display_text"),
                rs.getTimestamp("created_at")),
                start.createdAt(), start.createdAt(), start.id());
        if (items.size() < MERGE_BATCH_SIZE) {
            mergeCursor = new ConsolidationCursor(MERGE_CURSOR_ORIGIN, MERGE_CURSOR_ORIGIN_ID);
        } else {
            Item last = items.get(items.size() - 1);
            mergeCursor = new ConsolidationCursor(last.createdAt(), last.id());
        }
        int merged = 0;
        for (int i = 0; i < items.size() && merged < 5; i++) {
            for (int j = i + 1; j < items.size() && merged < 5; j++) {
                Item left = items.get(i);
                Item right = items.get(j);
                if (!left.workspaceId().equals(right.workspaceId())
                        || "unknown".equals(left.slot())
                        || !left.slot().equals(right.slot())
                        || !statementMatcher.equivalent(left.text(), right.text())) {
                    continue;
                }
                Boolean decision = decideMerge(left.text(), right.text());
                if (!Boolean.TRUE.equals(decision)) {
                    continue;
                }
                Item keep = left.reinforcementCount() >= right.reinforcementCount() ? left : right;
                Item drop = keep == left ? right : left;
                evidenceService.observe(keep.id(), "consolidation:" + drop.id(), "SUPPORT",
                        MemoryEvidence.Band.INFERRED);
                if (reviewService.mergeDuplicate(keep.workspaceId(), keep.id(), drop.id())) {
                    merged++;
                }
            }
        }
        return merged;
    }

    private Boolean decideMerge(String left, String right) {
        String user = "记忆一：\n" + left + "\n记忆二：\n" + right;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String output = llmClient.streamChat(MERGE_PROMPT, user, 40, token -> { });
                Boolean parsed = parseMerge(output);
                if (parsed != null) {
                    return parsed;
                }
                user = user + "\n上次输出不合规，只输出 JSON。";
            } catch (RuntimeException ex) {
                log.warn("Memory consolidation judge failed: {}", ex.getMessage());
                return null;
            }
        }
        return null;
    }

    static Boolean parseMerge(String output) {
        if (output == null || !output.contains("merge")) {
            return null;
        }
        String compact = output.replaceAll("\\s", "").toLowerCase();
        if (compact.contains("\"merge\":true")) {
            return true;
        }
        if (compact.contains("\"merge\":false")) {
            return false;
        }
        return null;
    }

    private record ConsolidationCursor(Timestamp createdAt, String id) {
    }

    record Item(String id, String workspaceId, int reinforcementCount, String slot, String text, Timestamp createdAt) {
    }
}
