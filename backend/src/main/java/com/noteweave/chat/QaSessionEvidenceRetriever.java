package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F4-02：QA 会话证据通道。
 *
 * <p>只把冻结截止前有效回合实际引用过的资料片段作为候选，按规范身份（来源 + 快照 + 分块）
 * 去重，并重新检查来源当前有效；引用文本只是选择线索，最终是否进入回答仍由公共预算、
 * 作用域守卫与资格检查决定。</p>
 */
@Component
public class QaSessionEvidenceRetriever implements EvidenceRetriever {

    public static final String CHANNEL = "QA_SESSION_EVIDENCE";
    private static final double DECAY_PER_TURN = 0.08d;
    private static final double MIN_WEIGHT = 0.3d;

    private final JdbcTemplate jdbcTemplate;

    public QaSessionEvidenceRetriever(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(AnswerContext context, RetrievalPlan plan,
                                            RetrievalPlan.Step step) {
        if (context.conversationId() == null || context.conversationId().isBlank()
                || context.queryMessageId() == null || context.queryMessageId().isBlank()) {
            return empty();
        }
        Integer cutoffSeq = jdbcTemplate.query("""
                select message_seq from conversation_message where id = ? and workspace_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : null,
                context.queryMessageId(), context.workspaceId());
        if (cutoffSeq == null) {
            return empty();
        }
        List<CitationRow> rows = jdbcTemplate.query("""
                select m.message_seq, c.id as citation_id, c.source_id, c.source_snapshot_id,
                       c.source_chunk_id, c.title, c.quote_text, c.location_info
                from conversation_message m
                join message_citation mc on mc.message_id = m.id
                join citation c on c.id = mc.citation_id
                join source s on s.id = c.source_id
                where m.workspace_id = ? and m.conversation_id = ? and m.message_seq <= ?
                  and m.context_status = 'CURRENT'
                  and s.status = 'ACTIVE'
                  and c.source_id is not null and c.quote_text is not null
                order by m.message_seq desc, c.created_at desc
                limit 60
                """, (rs, rowNum) -> new CitationRow(
                rs.getInt("message_seq"), rs.getString("citation_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("source_chunk_id"),
                rs.getString("title"), rs.getString("quote_text"), rs.getString("location_info")),
                context.workspaceId(), context.conversationId(), cutoffSeq);
        Map<String, EvidenceBundle.Evidence> deduped = new LinkedHashMap<>();
        for (CitationRow row : rows) {
            String identity = identity(row);
            if (deduped.containsKey(identity)) {
                continue;
            }
            double weight = weight(cutoffSeq, row.messageSeq());
            deduped.put(identity, toEvidence(row, weight));
        }
        List<EvidenceBundle.Evidence> evidence = deduped.values().stream()
                .limit(Math.max(0, step.candidateLimit()))
                .toList();
        return new EvidenceRetrievalResult(
                evidence,
                Map.of("session_evidence_version", "qa-session-evidence-v1"),
                false,
                List.of(),
                Map.of("session_candidates", (long) rows.size(),
                        "session_deduped", (long) deduped.size()),
                List.of());
    }

    private EvidenceRetrievalResult empty() {
        return new EvidenceRetrievalResult(List.of(), Map.of(), false, List.of(), Map.of(), List.of());
    }

    private EvidenceBundle.Evidence toEvidence(CitationRow row, double weight) {
        String passageId = row.chunkId() == null || row.chunkId().isBlank()
                ? row.citationId() : row.chunkId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("session_citation", "true");
        metadata.put("citation_id", row.citationId());
        metadata.put("match_reason", "SESSION_CITATION");
        metadata.put("freshness_status", "CURRENT_AT_RETRIEVAL");
        return new EvidenceBundle.Evidence(
                "session:" + row.citationId(),
                "PASSAGE",
                row.sourceId(),
                row.snapshotId(),
                passageId,
                "",
                "",
                row.title() == null ? "" : row.title(),
                row.quoteText(),
                row.location() == null ? "" : row.location(),
                weight,
                weight,
                0.0d,
                "workspace-source:" + row.sourceId(),
                Instant.now(),
                "SESSION_CITATION",
                row.quoteText() == null ? 0 : row.quoteText().length(),
                metadata,
                com.noteweave.answer.strategy.ScoreInfo.unknown());
    }

    private static String identity(CitationRow row) {
        String chunk = row.chunkId() == null ? "" : row.chunkId();
        return row.sourceId() + "|" + (row.snapshotId() == null ? "" : row.snapshotId()) + "|" + chunk;
    }

    private static double weight(int cutoffSeq, int messageSeq) {
        double value = 1.0d - DECAY_PER_TURN * Math.max(0, cutoffSeq - messageSeq);
        return Math.round(Math.max(MIN_WEIGHT, Math.min(1.0d, value)) * 10_000d) / 10_000d;
    }

    private record CitationRow(int messageSeq, String citationId, String sourceId, String snapshotId,
                               String chunkId, String title, String quoteText, String location) {
    }
}
