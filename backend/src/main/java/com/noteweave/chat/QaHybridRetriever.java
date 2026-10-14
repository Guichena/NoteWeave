package com.noteweave.chat;

import com.noteweave.answer.strategy.RetrievalExecutionTrace.RankedCandidateTrace;
import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.conversation.ContextTokenEstimator;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import com.noteweave.retrieval.qa.QaRerankService;
import com.noteweave.retrieval.qa.QaRerankService.RankedHit;
import com.noteweave.retrieval.qa.QaRrfFusionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QaHybridRetriever {
    private static final int LOW_RECALL_EXPANSION_THRESHOLD = 3;
    /** V3 只保留相邻段落里靠近命中段落的这一段文字：前一段取结尾，后一段取开头。 */
    static final int ADJACENT_WINDOW_CHARACTERS = 300;
    /** 相邻段落块的开头标记；证据充分性判定据此跳过相邻段落，只看命中段落。 */
    static final String ADJACENT_MARKER = "[adjacent chunk ";
    /** 写入检索追踪的候选条数和每条正文的长度上限，供离线评测排序质量。 */
    static final int RANKED_TRACE_LIMIT = 20;
    static final int RANKED_TRACE_EXCERPT_CHARACTERS = 1_200;
    private final EmbeddingClient embeddingClient;
    private final QaHybridSearchPort searchPort;
    private final QaRrfFusionService fusionService;
    private final QaRerankService rerankService;
    private final RetrievalHydrator retrievalHydrator;
    private final QaQueryExpansionService queryExpansionService;

    public QaHybridRetriever(
            EmbeddingClient embeddingClient,
            QaHybridSearchPort searchPort,
            QaRrfFusionService fusionService,
            QaRerankService rerankService,
            RetrievalHydrator retrievalHydrator,
            QaQueryExpansionService queryExpansionService
    ) {
        this.embeddingClient = embeddingClient;
        this.searchPort = searchPort;
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.retrievalHydrator = retrievalHydrator;
        this.queryExpansionService = queryExpansionService;
    }

    public HybridResult retrieve(String workspaceId, String query, Set<String> sourceScope) {
        return retrieve(workspaceId, RetrievalChannels.same(query), sourceScope);
    }

    public HybridResult retrieve(String workspaceId, RetrievalChannels channels, Set<String> sourceScope) {
        return retrieve(workspaceId, channels, sourceScope, QaRetrievalStrategyProfile.V2);
    }

    public HybridResult retrieve(
            String workspaceId,
            RetrievalChannels channels,
            Set<String> sourceScope,
            QaRetrievalStrategyProfile profile
    ) {
        QaRetrievalStrategyProfile safeProfile = profile == null ? QaRetrievalStrategyProfile.V2 : profile;
        RetrievalChannels safeChannels = channels == null ? RetrievalChannels.same("") : channels;
        if (!embeddingClient.isEnabled()) {
            throw new RetrievalProviderException("EMBEDDING_PROVIDER_DISABLED",
                    "QA hybrid retrieval requires query embedding");
        }
        EmbeddingClient.EmbeddingResult embedding = embeddingClient.embedQuery(safeChannels.vectorQuery());
        List<Float> queryVector = embedding.singleVector();
        List<QaHybridSearchPort.QaSearchHit> vectorHits = new ArrayList<>(searchPort.vectorRetrieve(
                new QaVectorQuery(workspaceId, safeChannels.vectorQuery(), queryVector, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.VECTOR_THRESHOLD)));
        List<QaHybridSearchPort.QaSearchHit> keywordHits = new ArrayList<>(searchPort.keywordRetrieve(
                keywordQuery(workspaceId, safeChannels.lexicalQuery(), sourceScope, safeChannels)));
        List<String> pipelineDegradations = new ArrayList<>();
        List<QaRrfFusionService.FusedHit> primaryFused = fusionService.fuse(
                vectorHits, keywordHits,
                QaRetrievalStrategyProfile.RRF_K,
                QaRetrievalStrategyProfile.VECTOR_WEIGHT,
                QaRetrievalStrategyProfile.KEYWORD_WEIGHT,
                QaRetrievalStrategyProfile.FUSION_LIMIT);
        List<String> expansions = primaryFused.size() < LOW_RECALL_EXPANSION_THRESHOLD
                ? extraQueries(safeChannels) : List.of();
        for (String expansion : expansions) {
            try {
                List<Float> expansionVector = embeddingClient.embedQuery(expansion).singleVector();
                mergeDistinct(vectorHits, searchPort.vectorRetrieve(new QaVectorQuery(
                        workspaceId, expansion, expansionVector, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.VECTOR_THRESHOLD)));
                mergeDistinct(keywordHits, searchPort.keywordRetrieve(new QaKeywordQuery(
                        workspaceId, expansion, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.KEYWORD_THRESHOLD)));
            } catch (RuntimeException ex) {
                pipelineDegradations.add("QA_QUERY_EXPANSION_UNAVAILABLE");
            }
        }
        List<QaRrfFusionService.FusedHit> fused = fusionService.fuse(
                vectorHits, keywordHits,
                QaRetrievalStrategyProfile.RRF_K,
                QaRetrievalStrategyProfile.VECTOR_WEIGHT,
                QaRetrievalStrategyProfile.KEYWORD_WEIGHT,
                QaRetrievalStrategyProfile.FUSION_LIMIT);
        Map<String, PassageOwnership> ownership = retrievalHydrator.hydratePassageOwnership(
                workspaceId, fused.stream().map(QaRrfFusionService.FusedHit::chunkId).toList());
        List<QaRrfFusionService.FusedHit> eligible = fused.stream().filter(hit -> {
            PassageOwnership owner = ownership.get(hit.chunkId());
            return owner != null && hit.sourceId().equals(owner.sourceId())
                    && hit.sourceSnapshotId().equals(owner.sourceSnapshotId());
        }).toList();
        QaRerankService.RerankOutcome reranked = rerankService.rerank(
                safeChannels.rerankQuery(), eligible, QaRetrievalStrategyProfile.RERANK_LIMIT);
        Map<String, List<RetrievalHydrator.AdjacentPassage>> hydratedAdjacent =
                retrievalHydrator.hydrateAdjacentPassages(
                        workspaceId, reranked.hits().stream().map(hit -> hit.hit().chunkId()).toList());
        Map<String, List<RetrievalHydrator.AdjacentPassage>> adjacent = hydratedAdjacent == null
                ? Map.of() : hydratedAdjacent;
        List<OwnedHit> owned = new ArrayList<>();
        int ownershipRejected = fused.size() - eligible.size();
        for (RankedHit ranked : reranked.hits()) {
            QaRrfFusionService.FusedHit hit = ranked.hit();
            PassageOwnership owner = ownership.get(hit.chunkId());
            if (owner == null || !hit.sourceId().equals(owner.sourceId())
                    || !hit.sourceSnapshotId().equals(owner.sourceSnapshotId())) {
                ownershipRejected++;
                continue;
            }
            owned.add(new OwnedHit(ranked, owner, adjacent.getOrDefault(hit.chunkId(), List.of())));
        }
        List<RetrievedChunk> selected = safeProfile.usesTokenBudget()
                ? selectWithinTokenBudget(owned, safeProfile)
                : selectLegacyBundle(owned);
        Map<String, Long> measurements = new LinkedHashMap<>();
        measurements.put("vector_candidate_count", (long) vectorHits.size());
        measurements.put("keyword_candidate_count", (long) keywordHits.size());
        measurements.put("rrf_candidate_count", (long) fused.size());
        measurements.put("rerank_candidate_count", (long) reranked.hits().size());
        measurements.put("ownership_rejected_count", (long) ownershipRejected);
        measurements.put("selected_count", (long) selected.size());
        if (safeProfile.usesTokenBudget()) {
            measurements.put("evidence_token_budget", (long) safeProfile.evidenceTokenBudget());
            measurements.put("selected_token_estimate", selected.stream()
                    .mapToLong(chunk -> ContextTokenEstimator.estimate(chunk.content())).sum());
        }
        measurements.put("mysql_fallback_used", 0L);
        measurements.put("query_expansion_count", (long) expansions.size());
        pipelineDegradations.addAll(reranked.degradationReasons());
        return new HybridResult(selected, !pipelineDegradations.isEmpty(),
                List.copyOf(new java.util.LinkedHashSet<>(pipelineDegradations)), measurements,
                embedding.model(), reranked.model(), rankedTrace(owned, selected));
    }

    /** V2：先按资料多样性取 6 条，再按字符预算截断；相邻段落整段拼入。只用于历史计划。 */
    private List<RetrievedChunk> selectLegacyBundle(List<OwnedHit> owned) {
        List<RetrievedChunk> chunks = owned.stream()
                .map(item -> toChunk(item, enrich(item.hit().content(), item.context()), item.context()))
                .toList();
        return QaEvidenceSelectionPolicy.selectFinalBundle(
                chunks, RetrievedChunk::sourceId, RetrievedChunk::chunkId,
                RetrievedChunk::rerankScore, chunk -> chunk.content() == null ? 0 : chunk.content().length());
    }

    /**
     * V3：在整个重排列表上做 MMR 贪心装箱，成本按带截断相邻段落的 token 数估算。
     * 选完后，如果某条证据的相邻段落本身也被选中，就不再重复拼入。
     */
    private List<RetrievedChunk> selectWithinTokenBudget(
            List<OwnedHit> owned,
            QaRetrievalStrategyProfile profile
    ) {
        List<OwnedHit> picked = QaEvidenceSelectionPolicy.selectMmrWithinBudget(
                owned,
                item -> item.owner().sourceId(),
                item -> item.hit().chunkId(),
                item -> item.hit().content(),
                item -> item.ranked().rerankScore(),
                item -> ContextTokenEstimator.estimate(trimmedEnrich(item, Set.of())),
                profile.evidenceTokenBudget(),
                profile.maxEvidence());
        Set<String> pickedIds = picked.stream().map(item -> item.hit().chunkId())
                .collect(java.util.stream.Collectors.toSet());
        return picked.stream().map(item -> {
            List<RetrievalHydrator.AdjacentPassage> context = item.context().stream()
                    .filter(passage -> !pickedIds.contains(passage.chunkId()))
                    .toList();
            return toChunk(item, trimmedEnrich(item, pickedIds), context);
        }).toList();
    }

    private RetrievedChunk toChunk(
            OwnedHit item,
            String content,
            List<RetrievalHydrator.AdjacentPassage> context
    ) {
        QaRrfFusionService.FusedHit hit = item.hit();
        PassageOwnership owner = item.owner();
        String reason = String.join(",", hit.matchedChannels()) + ",weighted-rrf,rerank";
        if (!context.isEmpty()) {
            reason += ",adjacent-context:" + context.stream()
                    .map(RetrievalHydrator.AdjacentPassage::chunkId)
                    .collect(java.util.stream.Collectors.joining("|"));
        }
        return new RetrievedChunk(
                hit.chunkId(), owner.sourceId(), owner.sourceSnapshotId(), hit.chunkNo(),
                hit.title(), content, owner.locationOr("chunk:" + hit.chunkNo()), hit.sourceType(),
                owner.generatedBy(), owner.generatedRefId(),
                (int) Math.round(item.ranked().rerankScore() * 1000), reason,
                Math.max(hit.vectorScore(), hit.keywordScore()), hit.rrfScore(), item.ranked().rerankScore());
    }

    /** 相邻段落只保留靠近命中段落的窗口，并跳过 excluded 中已作为独立证据的段落。 */
    String trimmedEnrich(OwnedHit item, Set<String> excluded) {
        List<RetrievalHydrator.AdjacentPassage> context = item.context().stream()
                .filter(passage -> !excluded.contains(passage.chunkId()))
                .map(passage -> new RetrievalHydrator.AdjacentPassage(
                        passage.anchorChunkId(), passage.anchorChunkNo(), passage.chunkId(),
                        passage.chunkNo(), passage.heading(),
                        // 窗口内的空行合并成单个换行，相邻段落块内部不出现空行，块边界可以按空行识别
                        (passage.chunkNo() < passage.anchorChunkNo()
                                ? tail(passage.content(), ADJACENT_WINDOW_CHARACTERS)
                                : head(passage.content(), ADJACENT_WINDOW_CHARACTERS))
                                .replaceAll("\\n\\s*\\n", "\n")))
                .toList();
        return enrich(item.hit().content(), context);
    }

    private static String head(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return value.substring(0, limit) + "…";
    }

    private static String tail(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return "…" + value.substring(value.length() - limit);
    }

    private List<RankedCandidateTrace> rankedTrace(List<OwnedHit> owned, List<RetrievedChunk> selected) {
        Set<String> selectedIds = selected.stream().map(RetrievedChunk::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        List<RankedCandidateTrace> trace = new ArrayList<>();
        for (int index = 0; index < owned.size() && index < RANKED_TRACE_LIMIT; index++) {
            OwnedHit item = owned.get(index);
            String content = item.hit().content() == null ? "" : item.hit().content();
            trace.add(new RankedCandidateTrace(
                    index + 1,
                    "passage:" + item.hit().chunkId(),
                    item.owner().sourceId(),
                    item.hit().chunkNo(),
                    item.ranked().rerankScore(),
                    selectedIds.contains(item.hit().chunkId()),
                    content.length() <= RANKED_TRACE_EXCERPT_CHARACTERS
                            ? content : content.substring(0, RANKED_TRACE_EXCERPT_CHARACTERS)));
        }
        return List.copyOf(trace);
    }

    record OwnedHit(
            RankedHit ranked,
            PassageOwnership owner,
            List<RetrievalHydrator.AdjacentPassage> context
    ) {
        QaRrfFusionService.FusedHit hit() {
            return ranked.hit();
        }
    }

    private List<String> extraQueries(RetrievalChannels channels) {
        if (channels.ruleExpansion()) {
            return queryExpansionService.expand(channels.vectorQuery());
        }
        return channels.alternatives();
    }

    private QaKeywordQuery keywordQuery(
            String workspaceId,
            String query,
            Set<String> sourceScope,
            RetrievalChannels channels
    ) {
        return new QaKeywordQuery(
                workspaceId,
                query,
                sourceScope,
                QaRetrievalStrategyProfile.RECALL_LIMIT,
                QaRetrievalStrategyProfile.KEYWORD_THRESHOLD,
                channels.mustTerms(),
                channels.titleTerms(),
                channels.bodyTerms()
        );
    }

    private void mergeDistinct(
            List<QaHybridSearchPort.QaSearchHit> target,
            List<QaHybridSearchPort.QaSearchHit> additions
    ) {
        Set<String> seen = target.stream().map(QaHybridSearchPort.QaSearchHit::chunkId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (additions == null) return;
        additions.stream().filter(hit -> hit != null && seen.add(hit.chunkId())).forEach(target::add);
    }

    private String enrich(String content, List<RetrievalHydrator.AdjacentPassage> adjacent) {
        if (adjacent == null || adjacent.isEmpty()) {
            return content;
        }
        StringBuilder enriched = new StringBuilder();
        adjacent.stream().filter(item -> item.chunkNo() < adjacentAnchor(adjacent))
                .forEach(item -> appendContext(enriched, item));
        if (content != null && !content.isBlank()) {
            if (!enriched.isEmpty()) enriched.append("\n\n");
            enriched.append(content);
        }
        adjacent.stream().filter(item -> item.chunkNo() > adjacentAnchor(adjacent))
                .forEach(item -> appendContext(enriched, item));
        return enriched.toString();
    }

    private int adjacentAnchor(List<RetrievalHydrator.AdjacentPassage> adjacent) {
        return adjacent.get(0).anchorChunkNo();
    }

    private void appendContext(StringBuilder enriched, RetrievalHydrator.AdjacentPassage item) {
        if (!enriched.isEmpty()) enriched.append("\n\n");
        enriched.append(ADJACENT_MARKER).append(item.chunkNo());
        if (item.heading() != null && !item.heading().isBlank()) {
            enriched.append(" - ").append(item.heading());
        }
        enriched.append("]\n").append(item.content());
    }

    public record HybridResult(
            List<RetrievedChunk> chunks,
            boolean degraded,
            List<String> degradationReasons,
            Map<String, Long> measurements,
            String embeddingModel,
            String rerankModel,
            List<RankedCandidateTrace> rankedCandidates
    ) {
        public HybridResult {
            rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        }

        public HybridResult(
                List<RetrievedChunk> chunks,
                boolean degraded,
                List<String> degradationReasons,
                Map<String, Long> measurements,
                String embeddingModel,
                String rerankModel
        ) {
            this(chunks, degraded, degradationReasons, measurements, embeddingModel, rerankModel, List.of());
        }
    }
}
