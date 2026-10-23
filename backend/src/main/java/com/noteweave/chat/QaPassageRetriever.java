package com.noteweave.chat;

import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class QaPassageRetriever {

    private static final Logger log = LoggerFactory.getLogger(QaPassageRetriever.class);

    private final ChunkSearchPort chunkSearchPort;
    private final RetrievalHydrator retrievalHydrator;
    private final QaHybridRetriever hybridRetriever;

    public QaPassageRetriever(
            ChunkSearchPort chunkSearchPort,
            RetrievalHydrator retrievalHydrator
    ) {
        this(chunkSearchPort, retrievalHydrator, null);
    }

    @Autowired
    public QaPassageRetriever(
            ChunkSearchPort chunkSearchPort,
            RetrievalHydrator retrievalHydrator,
            QaHybridRetriever hybridRetriever
    ) {
        this.chunkSearchPort = chunkSearchPort;
        this.retrievalHydrator = retrievalHydrator;
        this.hybridRetriever = hybridRetriever;
    }

    public List<RetrievedChunk> retrieve(String workspaceId, String query) {
        return retrieveWithDiagnostics(workspaceId, query, Set.of()).chunks();
    }

    public List<RetrievedChunk> retrieve(
            String workspaceId,
            String query,
            Set<String> sourceScope
    ) {
        return retrieveWithDiagnostics(workspaceId, query, sourceScope).chunks();
    }

    public RetrievalResult retrieveWithDiagnostics(
            String workspaceId,
            String query,
            Set<String> sourceScope
    ) {
        return retrieveWithDiagnostics(
                workspaceId, query, sourceScope, QaRetrievalStrategyProfile.V2);
    }

    public RetrievalResult retrieveWithDiagnostics(
            String workspaceId,
            String query,
            Set<String> sourceScope,
            QaRetrievalStrategyProfile profile
    ) {
        return retrieveWithDiagnostics(
                workspaceId, RetrievalChannels.same(query), sourceScope, profile);
    }

    public RetrievalResult retrieveWithDiagnostics(
            String workspaceId,
            RetrievalChannels channels,
            Set<String> sourceScope,
            QaRetrievalStrategyProfile profile
    ) {
        RetrievalChannels safeChannels = channels == null ? RetrievalChannels.same("") : channels;
        String query = safeChannels.lexicalQuery();
        Objects.requireNonNull(profile, "QA retrieval strategy profile is required");
        List<String> allowedSourceIds = normalizeSourceScope(sourceScope);
        Set<String> allowedSources = new LinkedHashSet<>(allowedSourceIds);
        List<String> degradationReasons = new ArrayList<>();
        Map<String, Long> measurements = new LinkedHashMap<>();
        measurements.put("strategy_v2_enabled", profile.v2Enabled() ? 1L : 0L);
        int primaryHitCount = 0;
        int scopedPrimaryHitCount = 0;
        int relevantPrimaryHitCount = 0;
        int rejectedRelevanceCount = 0;
        int rejectedOwnershipCount = 0;
        if (hybridRetriever != null) {
            try {
                QaHybridRetriever.HybridResult hybrid = hybridRetriever.retrieve(
                        workspaceId, safeChannels, allowedSources, profile);
                Map<String, Long> hybridMeasurements = new LinkedHashMap<>(hybrid.measurements());
                hybridMeasurements.put("strategy_v2_enabled", profile.v2Enabled() ? 1L : 0L);
                return new RetrievalResult(hybrid.chunks(), hybrid.degraded(),
                        hybrid.degradationReasons(), hybridMeasurements, hybrid.rankedCandidates());
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                degradationReasons.add("qa_hybrid_retrieval_error");
                String errorCode = ex instanceof com.noteweave.retrieval.provider.RetrievalProviderException provider
                        ? provider.errorCode() : "QA_HYBRID_RETRIEVAL_FAILED";
                log.error("QA hybrid retrieval failed; errorCode={}", errorCode);
                throw new BusinessException(
                        "QA_RETRIEVAL_PROVIDER_UNAVAILABLE",
                        "QA retrieval provider is unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }
        }
        if (hybridRetriever == null) try {
            List<ChunkSearchHit> searchHits = chunkSearchPort.search(workspaceId, query, 12);
            primaryHitCount = searchHits.size();
            List<ChunkSearchHit> scopedSearchHits = searchHits.stream()
                    .filter(hit -> allowedSources.isEmpty() || allowedSources.contains(hit.sourceId()))
                    .toList();
            scopedPrimaryHitCount = scopedSearchHits.size();
            List<ChunkSearchHit> relevantSearchHits = admitSearchHits(
                    query, scopedSearchHits, profile);
            rejectedRelevanceCount = scopedPrimaryHitCount - relevantSearchHits.size();
            if (!relevantSearchHits.isEmpty()) {
                Map<String, PassageOwnership> ownershipByChunkId =
                        retrievalHydrator.hydratePassageOwnership(
                                workspaceId,
                                relevantSearchHits.stream().map(ChunkSearchHit::chunkId).toList());
                List<ChunkSearchHit> ownedSearchHits = new ArrayList<>();
                for (ChunkSearchHit hit : relevantSearchHits) {
                    PassageOwnership ownership = ownershipByChunkId.get(hit.chunkId());
                    if (ownership == null
                            || !hit.sourceId().equals(ownership.sourceId())
                            || !hit.sourceSnapshotId().equals(ownership.sourceSnapshotId())) {
                        continue;
                    }
                    ownedSearchHits.add(hit);
                }
                rejectedOwnershipCount = relevantSearchHits.size() - ownedSearchHits.size();
                if (rejectedOwnershipCount > 0) {
                    degradationReasons.add("qa_primary_ownership_rejected");
                    log.warn("Rejected {} QA search hits that failed workspace passage ownership validation",
                            rejectedOwnershipCount);
                }
                List<ChunkSearchHit> finalSearchHits = admitSearchHits(
                        query, ownedSearchHits, profile);
                rejectedRelevanceCount += ownedSearchHits.size() - finalSearchHits.size();
                relevantPrimaryHitCount = finalSearchHits.size();
                List<RetrievedChunk> searchResults = new ArrayList<>();
                for (ChunkSearchHit hit : finalSearchHits) {
                    PassageOwnership ownership = ownershipByChunkId.get(hit.chunkId());
                    searchResults.add(new RetrievedChunk(
                            hit.chunkId(),
                            ownership.sourceId(),
                            ownership.sourceSnapshotId(),
                            Integer.parseInt(hit.chunkNo()),
                            hit.title(),
                            hit.content(),
                            ownership.locationOr("chunk:" + hit.chunkNo()),
                            hit.sourceType(),
                            ownership.generatedBy(),
                            ownership.generatedRefId(),
                            (int) Math.round(hit.score() * 10),
                            "fulltext:bm25"
                    ));
                }
                if (!searchResults.isEmpty()) {
                    List<RetrievedChunk> selected = selectDiverseEvidence(
                            searchResults, QaEvidenceSelectionPolicy.DEFAULT_EVIDENCE_LIMIT);
                    measurements.put("primary_hit_count", (long) primaryHitCount);
                    measurements.put("scoped_primary_hit_count", (long) scopedPrimaryHitCount);
                    measurements.put("relevant_primary_hit_count", (long) relevantPrimaryHitCount);
                    measurements.put("relevance_rejected_count", (long) rejectedRelevanceCount);
                    measurements.put("ownership_rejected_count", (long) rejectedOwnershipCount);
                    measurements.put("mysql_fallback_used", 0L);
                    measurements.put("selected_count", (long) selected.size());
                    return new RetrievalResult(
                            selected,
                            !degradationReasons.isEmpty(),
                            degradationReasons,
                            measurements
                    );
                }
            } else if (scopedSearchHits.isEmpty()) {
                degradationReasons.add("qa_primary_no_scoped_hits");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            degradationReasons.add("qa_primary_search_error");
            log.error("Primary QA retrieval failed; reason=qa_primary_search_error");
            throw new BusinessException(
                    "QA_RETRIEVAL_PROVIDER_UNAVAILABLE",
                    "QA retrieval provider is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        if (relevantPrimaryHitCount > 0 && rejectedOwnershipCount == relevantPrimaryHitCount
                && !degradationReasons.contains("qa_primary_ownership_rejected")) {
            degradationReasons.add("qa_primary_ownership_rejected");
        }
        measurements.put("primary_hit_count", (long) primaryHitCount);
        measurements.put("scoped_primary_hit_count", (long) scopedPrimaryHitCount);
        measurements.put("relevant_primary_hit_count", (long) relevantPrimaryHitCount);
        measurements.put("relevance_rejected_count", (long) rejectedRelevanceCount);
        measurements.put("ownership_rejected_count", (long) rejectedOwnershipCount);
        measurements.put("mysql_fallback_used", 0L);
        measurements.put("selected_count", 0L);
        return new RetrievalResult(
                List.of(),
                !degradationReasons.isEmpty(),
                degradationReasons,
                measurements
        );
    }

    private List<ChunkSearchHit> admitSearchHits(
            String query,
            List<ChunkSearchHit> candidates,
            QaRetrievalStrategyProfile profile
    ) {
        Set<Integer> admitted = QaEvidenceRelevancePolicy.admittedIndexes(
                profile.relevancePolicyVersion(),
                query,
                candidates.stream()
                        .map(hit -> new QaEvidenceRelevancePolicy.Candidate(
                                hit.sourceId(),
                                hit.sourceSnapshotId(),
                                Integer.parseInt(hit.chunkNo()),
                                hit.title(),
                                hit.content(),
                                hit.score()))
                        .toList());
        return IntStream.range(0, candidates.size())
                .filter(admitted::contains)
                .mapToObj(candidates::get)
                .toList();
    }

    private List<String> normalizeSourceScope(Set<String> sourceScope) {
        if (sourceScope == null || sourceScope.isEmpty()) {
            return List.of();
        }
        return sourceScope.stream()
                .filter(sourceId -> sourceId != null && !sourceId.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private List<RetrievedChunk> selectDiverseEvidence(List<RetrievedChunk> chunks, int limit) {
        return QaEvidenceSelectionPolicy.select(
                        chunks, limit, RetrievedChunk::sourceId, RetrievedChunk::chunkId)
                .stream()
                .map(item -> item.sourceDiversity()
                        ? item.item().withMatchReason(
                                appendReason(item.item().matchReason(), "source-diversity"))
                        : item.item())
                .toList();
    }

    private String appendReason(String current, String reason) {
        if (current == null || current.isBlank()) {
            return reason;
        }
        if (current.contains(reason)) {
            return current;
        }
        return current + ", " + reason;
    }

    public record RetrievedChunk(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String content,
            String locationInfo,
            String sourceType,
            String generatedBy,
            String generatedRefId,
            int score,
            String matchReason,
            double rawScore,
            double fusedScore,
            double rerankScore
    ) {
        public RetrievedChunk(
                String chunkId, String sourceId, String sourceSnapshotId, int chunkNo,
                String title, String content, String locationInfo, String sourceType,
                String generatedBy, String generatedRefId, int score, String matchReason
        ) {
            this(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo,
                    sourceType, generatedBy, generatedRefId, score, matchReason,
                    score, score, score);
        }

        RetrievedChunk withScore(int nextScore) {
            return new RetrievedChunk(
                    chunkId, sourceId, sourceSnapshotId, chunkNo, title, content,
                    locationInfo, sourceType, generatedBy, generatedRefId,
                    nextScore, matchReason, nextScore, nextScore, nextScore);
        }

        RetrievedChunk withMatchReason(String nextMatchReason) {
            return new RetrievedChunk(
                    chunkId, sourceId, sourceSnapshotId, chunkNo, title, content,
                    locationInfo, sourceType, generatedBy, generatedRefId,
                    score, nextMatchReason, rawScore, fusedScore, rerankScore);
        }
    }

    public record RetrievalResult(
            List<RetrievedChunk> chunks,
            boolean degraded,
            List<String> degradationReasons,
            Map<String, Long> measurements,
            List<RetrievalExecutionTrace.RankedCandidateTrace> rankedCandidates
    ) {
        public RetrievalResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            degradationReasons = degradationReasons == null
                    ? List.of() : List.copyOf(degradationReasons);
            measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
            rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        }

        public RetrievalResult(
                List<RetrievedChunk> chunks,
                boolean degraded,
                List<String> degradationReasons,
                Map<String, Long> measurements
        ) {
            this(chunks, degraded, degradationReasons, measurements, List.of());
        }
    }
}
