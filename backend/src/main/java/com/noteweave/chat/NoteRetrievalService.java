package com.noteweave.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.RetrievalHydrator.NoteSourceStats;
import com.noteweave.source.SourceTagCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NoteRetrievalService {

    private final ObjectMapper objectMapper;
    private final RetrievalHydrator retrievalHydrator;
    private final NoteReadingPlanner readingPlanner;
    private final NoteRelatedEntryService relatedEntryService;
    private final SourceTagCodec sourceTagCodec;

    public NoteRetrievalService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RetrievalHydrator retrievalHydrator,
            NoteReadingPlanner readingPlanner,
            NoteRelationGraph noteRelationGraph,
            NoteRecallRepository recallRepository
    ) {
        this(jdbcTemplate, objectMapper, retrievalHydrator, readingPlanner,
                noteRelationGraph, recallRepository, new SourceTagCodec(objectMapper));
    }

    @Autowired
    public NoteRetrievalService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RetrievalHydrator retrievalHydrator,
            NoteReadingPlanner readingPlanner,
            NoteRelationGraph noteRelationGraph,
            NoteRecallRepository recallRepository,
            SourceTagCodec sourceTagCodec
    ) {
        this.objectMapper = objectMapper;
        this.retrievalHydrator = retrievalHydrator;
        this.readingPlanner = readingPlanner;
        this.relatedEntryService = new NoteRelatedEntryService(
                jdbcTemplate, noteRelationGraph, recallRepository);
        this.sourceTagCodec = sourceTagCodec;
    }

    public List<NoteEntryMetadata> readEntriesMetadataForNote(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<String> sourceIds = sources.stream().map(CandidateSource::sourceId).toList();
        Map<String, List<ReadingWindow>> windowsBySourceId = retrievalHydrator.hydrateNoteWindows(
                workspaceId, sourceIds);
        Map<String, NoteSourceStats> statsBySourceId = retrievalHydrator.hydrateNoteSourceStats(
                workspaceId, sourceIds);
        Map<String, List<RelatedEntryPreview>> relatedEntriesBySourceId =
                relatedEntryService.relatedEntriesForSources(workspaceId, sources);
        List<NoteEntryMetadata> metadataEntries = new ArrayList<>();
        for (CandidateSource source : sources) {
            NoteSourceStats stats = statsBySourceId.getOrDefault(
                    source.sourceId(), NoteSourceStats.unknown(source.sourceId()));
            List<WindowLocator> windowLocators = readingPlanner.locators(
                    windowsBySourceId.getOrDefault(source.sourceId(), List.of()), query);
            int totalWindowCount = stats.windowCount();
            metadataEntries.add(new NoteEntryMetadata(
                    source.sourceId(),
                    source.title(),
                    source.sourceType(),
                    source.generatedBy(),
                    source.generatedRefId(),
                    source.summary(),
                    parseJsonStringArray(source.tagsJson()),
                    metadataPreview(source.metadataJson()),
                    stats.parseStatus(),
                    stats.indexStatus(),
                    source.chunkCount(),
                    totalWindowCount,
                    totalWindowCount > windowLocators.size(),
                    windowLocators,
                    relatedEntriesBySourceId.getOrDefault(source.sourceId(), List.of())
            ));
        }
        return metadataEntries;
    }
    private List<String> parseJsonStringArray(String json) {
        return sourceTagCodec.decode(json).stream().limit(8).toList();
    }

    private List<String> metadataPreview(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
            List<String> preview = new ArrayList<>();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                preview.add(entry.getKey() + "=" + entry.getValue());
                if (preview.size() >= 6) {
                    break;
                }
            }
            return preview;
        } catch (Exception ignored) {
            return List.of(metadataJson);
        }
    }

    public record CandidateSource(
            String sourceId,
            String title,
            String sourceType,
            int chunkCount,
            int windowCount,
            String generatedBy,
            String generatedRefId,
            String summary,
            String tagsJson,
            String metadataJson,
            String sampleText,
            int score,
            String recallSignals,
            List<String> matchedFields,
            List<String> coverageTerms,
            int coveredQueryTerms,
            int totalQueryTerms,
            String selectionReason,
            String verifyAdmissionReason
    ) {
        CandidateSource withScore(int nextScore) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    nextScore, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withRecallSignals(String nextRecallSignals) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, nextRecallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withMetadataRank(
                List<String> nextMatchedFields,
                List<String> nextCoverageTerms,
                int nextCoveredQueryTerms,
                int nextTotalQueryTerms
        ) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, nextMatchedFields, nextCoverageTerms, nextCoveredQueryTerms, nextTotalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withSelectionReason(String nextSelectionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    nextSelectionReason, verifyAdmissionReason);
        }

        CandidateSource withVerifyAdmissionReason(String nextVerifyAdmissionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, nextVerifyAdmissionReason);
        }

        String metadataForScoring() {
            return String.join("\n", title, sourceType, summary, tagsJson, metadataJson, sampleText);
        }
    }

    public record ReadingWindow(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String heading,
            String title,
            String generatedBy,
            String generatedRefId,
            int windowNo,
            String content,
            String locationInfo,
            int score,
            String readRole,
            int anchorWindowNo,
            String readObjective,
            double rawScore,
            double fusedScore,
            double rerankScore
    ) {
        public ReadingWindow(
                String chunkId, String sourceId, String sourceSnapshotId, int chunkNo,
                String heading, String title, String generatedBy, String generatedRefId,
                int windowNo, String content, String locationInfo, int score,
                String readRole, int anchorWindowNo, String readObjective
        ) {
            this(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy,
                    generatedRefId, windowNo, content, locationInfo, score, readRole,
                    anchorWindowNo, readObjective, score, score, score);
        }

        ReadingWindow withScore(int nextScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, nextScore, readRole, anchorWindowNo, readObjective, rawScore, nextScore, rerankScore);
        }

        ReadingWindow withRawScore(double nextRawScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, readRole, anchorWindowNo, readObjective, nextRawScore, fusedScore, rerankScore);
        }

        ReadingWindow withRerankScore(double nextRerankScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, (int) Math.round(nextRerankScore * 1000), readRole, anchorWindowNo, readObjective, rawScore, fusedScore, nextRerankScore);
        }

        ReadingWindow withReadRole(String nextReadRole) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, nextReadRole, anchorWindowNo, readObjective, rawScore, fusedScore, rerankScore);
        }

        ReadingWindow withAnchorWindowNo(int nextAnchorWindowNo) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, readRole, nextAnchorWindowNo, readObjective, rawScore, fusedScore, rerankScore);
        }

        ReadingWindow withReadObjective(String nextReadObjective) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, readRole, anchorWindowNo, nextReadObjective, rawScore, fusedScore, rerankScore);
        }

    }

    public record NoteJournalHit(
            String noteId,
            String title,
            String summary,
            String content,
            int citationCount,
            int score,
            String freshnessStatus,
            String freshnessNote,
            int staleSourceCount,
            int unavailableSourceCount
    ) {
        NoteJournalHit withScore(int nextScore) {
            return new NoteJournalHit(noteId, title, summary, content, citationCount, nextScore,
                    freshnessStatus, freshnessNote, staleSourceCount, unavailableSourceCount);
        }

        NoteJournalHit withFreshness(
                String nextFreshnessStatus,
                String nextFreshnessNote,
                int nextStaleSourceCount,
                int nextUnavailableSourceCount
        ) {
            return new NoteJournalHit(noteId, title, summary, content, citationCount, score,
                    nextFreshnessStatus, nextFreshnessNote, nextStaleSourceCount, nextUnavailableSourceCount);
        }
    }

    public record NoteRelationSignal(
            String sourceId,
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int titleOverlapScore,
            int graphNeighborhoodScore,
            int score
    ) {
        static NoteRelationSignal empty(String sourceId) {
            return new NoteRelationSignal(sourceId, 0, 0, 0, 0, 0, 0);
        }
    }

    public record NoteRecallPlan(
            List<NoteJournalHit> journalHits,
            List<CandidateSource> candidateSources,
            List<CandidateSource> relationExpansionSources,
            List<CandidateSource> verifySources,
            NoteRecallTrace trace,
            boolean degraded,
            List<String> degradationReasons,
            Map<String, Long> measurements
    ) {
        public NoteRecallPlan(List<NoteJournalHit> journalHits, List<CandidateSource> candidateSources,
                              List<CandidateSource> relationExpansionSources,
                              List<CandidateSource> verifySources, NoteRecallTrace trace) {
            this(journalHits, candidateSources, relationExpansionSources, verifySources,
                    trace, false, List.of(), Map.of());
        }

        public NoteRecallPlan {
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        }
    }

    public record NoteRecallTrace(
            int journalHitCount,
            int candidateCount,
            int relationExpansionCount,
            int verifyBatchCount,
            int metadataScoreSum,
            int semanticScoreSum,
            int noteScoreSum,
            int relationScoreSum,
            int readinessScoreSum
    ) {
        public NoteRecallTrace(int journalHitCount, int candidateCount, int relationExpansionCount,
                               int verifyBatchCount, int metadataScoreSum, int noteScoreSum,
                               int relationScoreSum, int readinessScoreSum) {
            this(journalHitCount, candidateCount, relationExpansionCount, verifyBatchCount,
                    metadataScoreSum, 0, noteScoreSum, relationScoreSum, readinessScoreSum);
        }
    }

    public record NoteEntryMetadata(
            String sourceId,
            String title,
            String sourceType,
            String generatedBy,
            String generatedRefId,
            String summary,
            List<String> tags,
            List<String> metadataSignals,
            String parseStatus,
            String indexStatus,
            int chunkCount,
            int windowCount,
            boolean hasMoreWindows,
            List<WindowLocator> windowLocators,
            List<RelatedEntryPreview> relatedEntries
    ) {
    }

    public record WindowLocator(
            int chunkNo,
            String heading,
            int windowNo,
            String locationInfo,
            int score,
            String readRole,
            Integer anchorWindowNo,
            String readObjective
    ) {
    }

    public record RelatedEntryPreview(
            String sourceId,
            String title,
            String generatedBy,
            String generatedRefId,
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int lexicalOverlapScore,
            int graphNeighborhoodScore,
            String relationReason,
            int score
    ) {
    }
}
