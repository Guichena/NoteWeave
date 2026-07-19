package com.noteweave.chat;

import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QaPassageRetriever {

    private static final Logger log = LoggerFactory.getLogger(QaPassageRetriever.class);

    private final JdbcTemplate jdbcTemplate;
    private final ChunkSearchPort chunkSearchPort;
    private final RetrievalHydrator retrievalHydrator;
    private final QaHybridRetriever hybridRetriever;

    public QaPassageRetriever(
            JdbcTemplate jdbcTemplate,
            ChunkSearchPort chunkSearchPort,
            RetrievalHydrator retrievalHydrator
    ) {
        this(jdbcTemplate, chunkSearchPort, retrievalHydrator, null);
    }

    @Autowired
    public QaPassageRetriever(
            JdbcTemplate jdbcTemplate,
            ChunkSearchPort chunkSearchPort,
            RetrievalHydrator retrievalHydrator,
            QaHybridRetriever hybridRetriever
    ) {
        this.jdbcTemplate = jdbcTemplate;
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
                        workspaceId, query, allowedSources);
                return new RetrievalResult(hybrid.chunks(), hybrid.degraded(),
                        hybrid.degradationReasons(), hybrid.measurements());
            } catch (Exception ex) {
                degradationReasons.add("qa_hybrid_retrieval_error");
                log.warn("QA hybrid retrieval failed; fallback=mysql; errorCode={}",
                        ex instanceof com.noteweave.retrieval.provider.RetrievalProviderException provider
                                ? provider.errorCode() : "QA_HYBRID_RETRIEVAL_FAILED");
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
                            "chunk:" + hit.chunkNo(),
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
        } catch (Exception ex) {
            degradationReasons.add("qa_primary_search_error");
            log.warn("Primary QA retrieval failed; fallback=mysql; reason=qa_primary_search_error");
        }

        if (relevantPrimaryHitCount > 0 && rejectedOwnershipCount == relevantPrimaryHitCount
                && !degradationReasons.contains("qa_primary_ownership_rejected")) {
            degradationReasons.add("qa_primary_ownership_rejected");
        }
        degradationReasons.add("qa_mysql_fallback");

        StringBuilder sql = new StringBuilder("""
                select c.id, c.source_id, c.source_snapshot_id, c.chunk_no, c.heading, c.content, c.location_info,
                       s.title, s.source_type,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json
                from source_chunk c
                join source s on s.id = c.source_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = c.source_id
                where c.workspace_id = ?
                  and s.workspace_id = c.workspace_id
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                """);
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        if (!allowedSourceIds.isEmpty()) {
            sql.append(" and s.id in (")
                    .append(String.join(",", allowedSourceIds.stream().map(ignored -> "?").toList()))
                    .append(")\n");
            params.addAll(allowedSourceIds);
        }
        sql.append("""
                order by s.updated_at desc, c.chunk_no asc
                limit 80
                """);
        List<RetrievedChunk> candidates = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getInt("chunk_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("location_info"),
                rs.getString("source_type"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id"),
                0,
                ""
        ), params.toArray());
        Set<String> terms = extractTerms(query);
        List<RetrievedChunk> scopedCandidates = candidates.stream()
                .filter(chunk -> allowedSources.isEmpty() || allowedSources.contains(chunk.sourceId()))
                .toList();
        List<RetrievedChunk> scoredCandidates = scopedCandidates.stream()
                .map(chunk -> {
                    int contentScore = score(chunk.content(), terms) * 3;
                    int metadataScore = score(chunk.title() + "\n" + chunk.sourceType(), terms) * 2;
                    int totalScore = contentScore + metadataScore;
                    return chunk.withScore(totalScore).withMatchReason(
                            qaMatchReason(contentScore, metadataScore));
                })
                .sorted(Comparator.comparingInt(RetrievedChunk::score).reversed())
                .toList();
        List<RetrievedChunk> relevantCandidates = admitRetrievedChunks(
                query, scoredCandidates, profile);
        List<RetrievedChunk> selected = selectDiverseEvidence(
                relevantCandidates, QaEvidenceSelectionPolicy.DEFAULT_EVIDENCE_LIMIT);
        measurements.put("primary_hit_count", (long) primaryHitCount);
        measurements.put("scoped_primary_hit_count", (long) scopedPrimaryHitCount);
        measurements.put("relevant_primary_hit_count", (long) relevantPrimaryHitCount);
        measurements.put("relevance_rejected_count", (long) rejectedRelevanceCount);
        measurements.put("ownership_rejected_count", (long) rejectedOwnershipCount);
        measurements.put("mysql_fallback_used", 1L);
        measurements.put("mysql_candidate_count", (long) scopedCandidates.size());
        measurements.put("mysql_relevance_rejected_count",
                (long) scopedCandidates.size() - relevantCandidates.size());
        measurements.put("selected_count", (long) selected.size());
        return new RetrievalResult(selected, true, degradationReasons, measurements);
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

    private List<RetrievedChunk> admitRetrievedChunks(
            String query,
            List<RetrievedChunk> candidates,
            QaRetrievalStrategyProfile profile
    ) {
        Set<Integer> admitted = QaEvidenceRelevancePolicy.admittedIndexes(
                profile.relevancePolicyVersion(),
                query,
                candidates.stream()
                        .map(chunk -> new QaEvidenceRelevancePolicy.Candidate(
                                chunk.sourceId(),
                                chunk.sourceSnapshotId(),
                                chunk.chunkNo(),
                                chunk.title(),
                                chunk.content(),
                                chunk.score()))
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

    private String qaMatchReason(int contentScore, int metadataScore) {
        List<String> reasons = new ArrayList<>();
        if (contentScore > 0) {
            reasons.add("chunk-keyword");
        }
        if (metadataScore > 0) {
            reasons.add("metadata-filter");
        }
        if (reasons.isEmpty()) {
            reasons.add("workspace-recent");
        }
        return String.join(", ", reasons);
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

    private Set<String> extractTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("[^\\p{IsHan}a-zA-Z0-9]+");
        Set<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms;
    }

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                score += Math.max(1, term.length());
            }
        }
        return score;
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
            Map<String, Long> measurements
    ) {
        public RetrievalResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            degradationReasons = degradationReasons == null
                    ? List.of() : List.copyOf(degradationReasons);
            measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        }
    }
}
