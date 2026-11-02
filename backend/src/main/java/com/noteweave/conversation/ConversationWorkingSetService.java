package com.noteweave.conversation;

import com.noteweave.context.ConversationWorkingSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * F4-01：从冻结截止之前的有效消息与引用派生会话工作集。
 *
 * <p>工作集不新增权威表，也不携带正文；条目记录贡献回合、身份、版本与按回合距离衰减的权重。
 * 删除或脱敏的回合不再贡献，但同一资料若仍被其他有效回合引用会保留。显式资料范围、通道关闭与
 * 当前权限的过滤在调用端先于本服务执行。</p>
 */
@Service
public class ConversationWorkingSetService {

    public static final String DERIVATION_VERSION = "conversation-working-set-derivation-v1";
    public static final String REASON_DELETED_TURN = "DELETED_TURN";
    public static final String REASON_SOURCE_LIMIT = "SOURCE_LIMIT";
    public static final String REASON_RESEARCH_LIMIT = "RESEARCH_LIMIT";
    public static final String REASON_CITED_EVIDENCE_LIMIT = "CITED_EVIDENCE_LIMIT";

    private static final double DECAY_PER_TURN = 0.08d;
    private static final double MIN_WEIGHT = 0.2d;

    private final JdbcTemplate jdbcTemplate;

    public ConversationWorkingSetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConversationWorkingSet derive(String workspaceId, String conversationId, int cutoffSeq) {
        List<Contribution> contributions = jdbcTemplate.query("""
                select m.id as message_id, m.message_seq, m.role, m.context_status,
                       c.id as citation_id, c.source_id, c.source_snapshot_id, c.research_run_id
                from conversation_message m
                left join message_citation mc on mc.message_id = m.id
                left join citation c on c.id = mc.citation_id
                where m.workspace_id = ? and m.conversation_id = ? and m.message_seq <= ?
                order by m.message_seq asc, c.id asc
                """, (rs, rowNum) -> new Contribution(
                rs.getString("message_id"), rs.getInt("message_seq"), rs.getString("role"),
                rs.getString("context_status"), rs.getString("citation_id"),
                rs.getString("source_id"), rs.getString("source_snapshot_id"),
                rs.getString("research_run_id")), workspaceId, conversationId, Math.max(0, cutoffSeq));

        Map<String, SourceAccumulator> sources = new LinkedHashMap<>();
        Map<String, ResearchAccumulator> research = new LinkedHashMap<>();
        Map<String, EvidenceAccumulator> evidence = new LinkedHashMap<>();
        Set<String> deletedSourceIds = new LinkedHashSet<>();
        for (Contribution contribution : contributions) {
            boolean validTurn = validTurn(contribution);
            if (contribution.sourceId() != null && !contribution.sourceId().isBlank()) {
                sources.computeIfAbsent(contribution.sourceId(), ignored -> new SourceAccumulator())
                        .observe(contribution, validTurn);
                if (!validTurn) {
                    deletedSourceIds.add(contribution.sourceId());
                }
            }
            if (contribution.researchRunId() != null && !contribution.researchRunId().isBlank()) {
                research.computeIfAbsent(contribution.researchRunId(), ignored -> new ResearchAccumulator())
                        .observe(contribution, validTurn);
            }
            if (contribution.citationId() != null && validTurn) {
                evidence.computeIfAbsent(contribution.citationId(), ignored -> new EvidenceAccumulator())
                        .observe(contribution);
            }
        }

        List<ConversationWorkingSet.Entry> entries = new ArrayList<>();
        List<ConversationWorkingSet.Exclusion> exclusions = new ArrayList<>();
        List<SourceAccumulator> validSources = sources.values().stream()
                .filter(SourceAccumulator::hasValidTurn)
                .sorted(Comparator.comparingInt((SourceAccumulator value) -> value.lastSeq).reversed())
                .toList();
        int sourceBudget = ConversationWorkingSet.Limits.defaults().sources();
        for (int index = 0; index < validSources.size(); index++) {
            SourceAccumulator source = validSources.get(index);
            if (index >= sourceBudget) {
                exclusions.add(new ConversationWorkingSet.Exclusion(
                        ConversationWorkingSet.EntryKind.SOURCE, source.refId, REASON_SOURCE_LIMIT));
                continue;
            }
            entries.add(source.toEntry(cutoffSeq));
        }
        for (String deletedSourceId : deletedSourceIds) {
            SourceAccumulator source = sources.get(deletedSourceId);
            if (source != null && !source.hasValidTurn()) {
                exclusions.add(new ConversationWorkingSet.Exclusion(
                        ConversationWorkingSet.EntryKind.SOURCE, deletedSourceId, REASON_DELETED_TURN));
            }
        }
        List<ResearchAccumulator> validResearch = research.values().stream()
                .filter(ResearchAccumulator::hasValidTurn)
                .sorted(Comparator.comparingInt((ResearchAccumulator value) -> value.lastSeq).reversed())
                .toList();
        for (int index = 0; index < validResearch.size(); index++) {
            ResearchAccumulator run = validResearch.get(index);
            if (index >= ConversationWorkingSet.Limits.defaults().researchRuns()) {
                exclusions.add(new ConversationWorkingSet.Exclusion(
                        ConversationWorkingSet.EntryKind.RESEARCH_RUN, run.refId, REASON_RESEARCH_LIMIT));
                continue;
            }
            entries.add(run.toEntry(cutoffSeq));
        }
        List<EvidenceAccumulator> validEvidence = evidence.values().stream()
                .sorted(Comparator.comparingInt((EvidenceAccumulator value) -> value.lastSeq).reversed())
                .toList();
        for (int index = 0; index < validEvidence.size(); index++) {
            EvidenceAccumulator cited = validEvidence.get(index);
            if (index >= ConversationWorkingSet.Limits.defaults().citedEvidence()) {
                exclusions.add(new ConversationWorkingSet.Exclusion(
                        ConversationWorkingSet.EntryKind.CITED_EVIDENCE,
                        cited.refId, REASON_CITED_EVIDENCE_LIMIT));
                continue;
            }
            entries.add(cited.toEntry(cutoffSeq));
        }
        return new ConversationWorkingSet(
                ConversationWorkingSet.SCHEMA_VERSION,
                workspaceId,
                conversationId,
                Math.max(0, cutoffSeq),
                ConversationWorkingSet.Limits.defaults(),
                entries,
                exclusions,
                DERIVATION_VERSION);
    }

    private static boolean validTurn(Contribution contribution) {
        String status = contribution.contextStatus();
        return status != null && "CURRENT".equals(status.toUpperCase());
    }

    private static double weight(int cutoffSeq, int lastSeq) {
        double value = 1.0d - DECAY_PER_TURN * Math.max(0, cutoffSeq - lastSeq);
        return Math.round(Math.max(MIN_WEIGHT, Math.min(1.0d, value)) * 10_000d) / 10_000d;
    }

    private record Contribution(String messageId, int messageSeq, String role, String contextStatus,
                                String citationId, String sourceId, String sourceSnapshotId,
                                String researchRunId) {
    }

    private static final class SourceAccumulator {
        private String refId;
        private String versionId = "";
        private final Set<String> messageIds = new LinkedHashSet<>();
        private int firstSeq = Integer.MAX_VALUE;
        private int lastSeq;
        private int validTurns;

        private void observe(Contribution contribution, boolean valid) {
            refId = contribution.sourceId();
            if (contribution.sourceSnapshotId() != null && !contribution.sourceSnapshotId().isBlank()) {
                versionId = contribution.sourceSnapshotId();
            }
            if (!valid) {
                return;
            }
            validTurns++;
            messageIds.add(contribution.messageId());
            firstSeq = Math.min(firstSeq, contribution.messageSeq());
            lastSeq = Math.max(lastSeq, contribution.messageSeq());
        }

        private boolean hasValidTurn() {
            return validTurns > 0;
        }

        private ConversationWorkingSet.Entry toEntry(int cutoffSeq) {
            return new ConversationWorkingSet.Entry(
                    ConversationWorkingSet.EntryKind.SOURCE, refId, versionId,
                    weight(cutoffSeq, lastSeq), List.copyOf(messageIds), firstSeq, lastSeq);
        }
    }

    private static final class ResearchAccumulator {
        private String refId;
        private final Set<String> messageIds = new LinkedHashSet<>();
        private int firstSeq = Integer.MAX_VALUE;
        private int lastSeq;
        private int validTurns;

        private void observe(Contribution contribution, boolean valid) {
            refId = contribution.researchRunId();
            if (!valid) {
                return;
            }
            validTurns++;
            messageIds.add(contribution.messageId());
            firstSeq = Math.min(firstSeq, contribution.messageSeq());
            lastSeq = Math.max(lastSeq, contribution.messageSeq());
        }

        private boolean hasValidTurn() {
            return validTurns > 0;
        }

        private ConversationWorkingSet.Entry toEntry(int cutoffSeq) {
            return new ConversationWorkingSet.Entry(
                    ConversationWorkingSet.EntryKind.RESEARCH_RUN, refId, "",
                    weight(cutoffSeq, lastSeq), List.copyOf(messageIds), firstSeq, lastSeq);
        }
    }

    private static final class EvidenceAccumulator {
        private String refId;
        private String versionId = "";
        private final Set<String> messageIds = new LinkedHashSet<>();
        private int firstSeq = Integer.MAX_VALUE;
        private int lastSeq;

        private void observe(Contribution contribution) {
            refId = contribution.citationId();
            if (contribution.sourceSnapshotId() != null && !contribution.sourceSnapshotId().isBlank()) {
                versionId = contribution.sourceSnapshotId();
            }
            messageIds.add(contribution.messageId());
            firstSeq = Math.min(firstSeq, contribution.messageSeq());
            lastSeq = Math.max(lastSeq, contribution.messageSeq());
        }

        private ConversationWorkingSet.Entry toEntry(int cutoffSeq) {
            return new ConversationWorkingSet.Entry(
                    ConversationWorkingSet.EntryKind.CITED_EVIDENCE, refId, versionId,
                    weight(cutoffSeq, lastSeq), List.copyOf(messageIds), firstSeq, lastSeq);
        }
    }
}
