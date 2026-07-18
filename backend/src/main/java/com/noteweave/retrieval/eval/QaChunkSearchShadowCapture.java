package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.RetrievalGoldSet.Candidate;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QaChunkSearchShadowCapture {
    private static final int DEFAULT_SEARCH_TOP_K = 12;

    private final ChunkSearchPort chunkSearchPort;
    private final RetrievalSnapshotSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final int searchTopK;

    @Autowired
    public QaChunkSearchShadowCapture(
            ChunkSearchPort chunkSearchPort,
            RetrievalSnapshotSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this(chunkSearchPort, sanitizer, objectMapper, DEFAULT_SEARCH_TOP_K);
    }

    QaChunkSearchShadowCapture(
            ChunkSearchPort chunkSearchPort,
            RetrievalSnapshotSanitizer sanitizer,
            ObjectMapper objectMapper,
            int searchTopK
    ) {
        this.chunkSearchPort = chunkSearchPort;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.searchTopK = Math.max(1, searchTopK);
    }

    public CaptureResult captureAndWrite(
            Path rawGoldSetPath,
            Path sanitizedGoldOutput,
            Path shadowOutput,
            String snapshotVersion,
            String salt
    ) throws Exception {
        RetrievalGoldSet rawGoldSet = objectMapper.readValue(rawGoldSetPath.toFile(), RetrievalGoldSet.class);
        CaptureResult result = capture(rawGoldSet, snapshotVersion, salt);
        createParent(sanitizedGoldOutput);
        createParent(shadowOutput);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(sanitizedGoldOutput.toFile(), result.sanitizedGoldSet());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(shadowOutput.toFile(), result.shadowSnapshot());
        return result;
    }

    public CaptureResult capture(
            RetrievalGoldSet rawGoldSet,
            String snapshotVersion,
            String salt
    ) {
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("QA shadow snapshotVersion is required");
        }
        List<GoldCase> qaCases = rawGoldSet.cases().stream()
                .filter(goldCase -> "QA".equals(goldCase.mode()))
                .toList();
        if (qaCases.isEmpty()) {
            throw new IllegalArgumentException("QA shadow capture requires at least one QA gold case");
        }
        RetrievalGoldSet qaGoldSet = new RetrievalGoldSet(
                rawGoldSet.schemaVersion(),
                rawGoldSet.datasetVersion() + "-qa-shadow",
                qaCases
        );
        RetrievalSnapshotSanitizer.SanitizedExport sanitized = sanitizer.sanitize(qaGoldSet, salt);
        List<CaseRanking> rankings = qaCases.stream()
                .map(goldCase -> captureCase(goldCase, salt))
                .toList();
        return new CaptureResult(
                sanitized.goldSet(),
                new RetrievalShadowSnapshot(
                        RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                        snapshotVersion,
                        QaRetrievalStrategyProfile.V2.profileVersion(),
                        rankings
                ),
                sanitized.redactionStats()
        );
    }

    private CaseRanking captureCase(GoldCase goldCase, String salt) {
        long started = System.nanoTime();
        List<ChunkSearchHit> hits = chunkSearchPort.search(
                goldCase.workspaceId(), goldCase.query(), searchTopK);
        long latencyMicros = Math.max(1L, (System.nanoTime() - started + 999L) / 1_000L);
        List<ChunkSearchHit> safeHits = hits == null ? List.of() : hits;
        Map<String, Candidate> candidateByEvidenceId = new HashMap<>();
        goldCase.candidates().forEach(candidate -> candidateByEvidenceId.put(
                candidate.evidenceId(), candidate));
        Set<String> allowedSourceIds = new HashSet<>(goldCase.allowedSourceIds());
        List<ChunkSearchHit> scopedHits = safeHits.stream()
                .filter(hit -> allowedSourceIds.isEmpty() || allowedSourceIds.contains(hit.sourceId()))
                .toList();
        Set<Integer> admittedIndexes = QaEvidenceRelevancePolicy.admittedIndexes(
                goldCase.query(),
                scopedHits.stream()
                        .map(hit -> new QaEvidenceRelevancePolicy.Candidate(
                                hit.sourceId(),
                                hit.sourceSnapshotId(),
                                Integer.parseInt(hit.chunkNo()),
                                hit.title(),
                                hit.content(),
                                hit.score()))
                        .toList());
        List<ChunkSearchHit> admittedHits = IntStream.range(0, scopedHits.size())
                .filter(admittedIndexes::contains)
                .mapToObj(scopedHits::get)
                .toList();
        List<RankedEvidence> ranked = QaEvidenceSelectionPolicy.selectFinalBundle(
                        admittedHits,
                        ChunkSearchHit::sourceId,
                        ChunkSearchHit::chunkId,
                        hit -> Math.round(hit.score() * 10.0d),
                        hit -> hit.content() == null ? 0 : hit.content().length())
                .stream()
                .map(hit -> {
                    Candidate candidate = candidateByEvidenceId.get(hit.chunkId());
                    List<String> citationIds = candidate == null
                            ? List.of(sanitizer.pseudonym(
                                    "citation-unmapped", hit.chunkId(), salt))
                            : candidate.citationIds().stream()
                                    .map(id -> sanitizer.pseudonym("citation", id, salt))
                                    .toList();
                    return new RankedEvidence(
                            sanitizer.pseudonym("evidence", hit.chunkId(), salt),
                            sanitizer.pseudonym("source", hit.sourceId(), salt),
                            hit.score(),
                            citationIds
                    );
                })
                .toList();
        return new CaseRanking(
                sanitizer.pseudonym("case", goldCase.id(), salt),
                latencyMicros,
                safeHits.size(),
                ranked
        );
    }

    private void createParent(Path output) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public record CaptureResult(
            RetrievalGoldSet sanitizedGoldSet,
            RetrievalShadowSnapshot shadowSnapshot,
            RetrievalSnapshotSanitizer.RedactionStats redactionStats
    ) {
    }
}
