package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.QaGoldAnnotationDraft.DraftCandidate;
import com.noteweave.retrieval.eval.QaGoldAnnotationDraft.DraftCase;
import com.noteweave.retrieval.eval.QaGoldAnnotationRequest.CaseRequest;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QaGoldAnnotationDraftCapture {
    public static final String REQUEST_SCHEMA_VERSION = "retrieval-qa-annotation-request-v1";
    public static final String DRAFT_SCHEMA_VERSION = "retrieval-qa-annotation-draft-v2";
    public static final String PENDING_STATUS = "PENDING";

    private final ChunkSearchPort chunkSearchPort;
    private final RetrievalSnapshotSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public QaGoldAnnotationDraftCapture(
            ChunkSearchPort chunkSearchPort,
            RetrievalSnapshotSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this(chunkSearchPort, sanitizer, objectMapper, Clock.systemUTC());
    }

    QaGoldAnnotationDraftCapture(
            ChunkSearchPort chunkSearchPort,
            RetrievalSnapshotSanitizer sanitizer,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.chunkSearchPort = chunkSearchPort;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CaptureResult captureAndWrite(
            Path requestPath,
            Path sanitizedDraftOutput,
            String salt
    ) throws Exception {
        QaGoldAnnotationRequest request = objectMapper.readValue(
                requestPath.toFile(), QaGoldAnnotationRequest.class);
        CaptureResult result = capture(request, salt);
        Path parent = sanitizedDraftOutput.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(sanitizedDraftOutput.toFile(), result.draft());
        return result;
    }

    public CaptureResult capture(QaGoldAnnotationRequest request, String salt) {
        validate(request);
        RetrievalSnapshotSanitizer.RedactionSession session = sanitizer.openSession(salt);
        List<CapturedCase> capturedCases = request.cases().stream()
                .map(item -> captureCase(item, request.candidatePoolSize(), session))
                .toList();
        Map<String, List<String>> selectedEvidenceIdsByCase = new LinkedHashMap<>();
        capturedCases.forEach(item -> selectedEvidenceIdsByCase.put(
                item.draftCase().id(), item.selectedEvidenceIds()));
        return new CaptureResult(
                new QaGoldAnnotationDraft(
                        DRAFT_SCHEMA_VERSION,
                        request.datasetVersion() + "-annotation-draft",
                        request.candidatePoolSize(),
                        Instant.now(clock),
                        capturedCases.stream().map(CapturedCase::draftCase).toList()
                ),
                session.stats(),
                Map.copyOf(selectedEvidenceIdsByCase)
        );
    }

    private CapturedCase captureCase(
            CaseRequest request,
            int candidatePoolSize,
            RetrievalSnapshotSanitizer.RedactionSession session
    ) {
        long started = System.nanoTime();
        List<ChunkSearchHit> hits = chunkSearchPort.search(
                request.workspaceId(), request.query(), candidatePoolSize);
        long latencyMicros = Math.max(1L, (System.nanoTime() - started + 999L) / 1_000L);
        List<ChunkSearchHit> safeHits = hits == null ? List.of() : hits;
        Set<String> allowedSources = new LinkedHashSet<>(request.allowedSourceIds());
        Set<String> seenEvidence = new LinkedHashSet<>();
        List<DraftCandidate> candidates = new ArrayList<>();
        List<AdmissionCandidate> admissionCandidates = new ArrayList<>();
        for (int index = 0; index < safeHits.size(); index++) {
            ChunkSearchHit hit = safeHits.get(index);
            if (hit == null || hit.chunkId() == null || hit.chunkId().isBlank()
                    || !seenEvidence.add(hit.chunkId())) {
                continue;
            }
            if (hit.sourceId() == null || hit.sourceId().isBlank() || !Double.isFinite(hit.score())) {
                throw new IllegalStateException("QA annotation search hit source/score is invalid");
            }
            DraftCandidate draftCandidate = new DraftCandidate(
                    index + 1,
                    session.pseudonym("evidence", hit.chunkId()),
                    session.pseudonym("source", hit.sourceId()),
                    session.pseudonym("snapshot", hit.sourceSnapshotId()),
                    session.redact(hit.chunkNo()),
                    session.redact(hit.title()),
                    session.redact(hit.content()),
                    session.redact(hit.sourceType()),
                    hit.score(),
                    allowedSources.contains(hit.sourceId()),
                    List.of(session.pseudonym("citation", citationLabel(hit.chunkId())))
            );
            candidates.add(draftCandidate);
            if (draftCandidate.withinAllowedScope()) {
                admissionCandidates.add(new AdmissionCandidate(hit, draftCandidate));
            }
        }
        Set<Integer> admittedIndexes = QaEvidenceRelevancePolicy.admittedIndexes(
                request.query(),
                admissionCandidates.stream()
                        .map(item -> new QaEvidenceRelevancePolicy.Candidate(
                                item.hit().sourceId(),
                                item.hit().sourceSnapshotId(),
                                Integer.parseInt(item.hit().chunkNo()),
                                item.hit().title(),
                                item.hit().content(),
                                item.hit().score()))
                        .toList());
        List<AdmissionCandidate> admittedCandidates = IntStream.range(
                        0, admissionCandidates.size())
                .filter(admittedIndexes::contains)
                .mapToObj(admissionCandidates::get)
                .toList();
        List<String> selectedEvidenceIds = QaEvidenceSelectionPolicy.selectFinalBundle(
                        admittedCandidates,
                        item -> item.hit().sourceId(),
                        item -> item.hit().chunkId(),
                        item -> Math.round(item.hit().score() * 10.0d),
                        item -> text(item.hit().content()).length())
                .stream()
                .map(item -> item.draftCandidate().evidenceId())
                .toList();
        String caseId = session.pseudonym("case", request.id());
        DraftCase draftCase = new DraftCase(
                caseId,
                "QA",
                session.pseudonym("workspace", request.workspaceId()),
                session.redact(request.query()),
                request.allowedSourceIds().stream()
                        .map(sourceId -> session.pseudonym("source", sourceId))
                        .toList(),
                request.topK(),
                latencyMicros,
                safeHits.size(),
                rawInputFingerprint(request, safeHits, session),
                PENDING_STATUS,
                null,
                List.of(),
                List.of(),
                "",
                candidates
        );
        return new CapturedCase(draftCase, selectedEvidenceIds);
    }

    private String rawInputFingerprint(
            CaseRequest request,
            List<ChunkSearchHit> hits,
            RetrievalSnapshotSanitizer.RedactionSession session
    ) {
        StringBuilder canonical = new StringBuilder();
        appendFingerprintPart(canonical, request.id());
        appendFingerprintPart(canonical, request.workspaceId());
        appendFingerprintPart(canonical, request.query());
        appendFingerprintPart(canonical, Integer.toString(request.topK()));
        request.allowedSourceIds().forEach(value -> appendFingerprintPart(canonical, value));
        appendFingerprintPart(canonical, Integer.toString(hits.size()));
        for (ChunkSearchHit hit : hits) {
            if (hit == null) {
                appendFingerprintPart(canonical, "<null-hit>");
                continue;
            }
            appendFingerprintPart(canonical, hit.chunkId());
            appendFingerprintPart(canonical, hit.sourceId());
            appendFingerprintPart(canonical, hit.sourceSnapshotId());
            appendFingerprintPart(canonical, hit.chunkNo());
            appendFingerprintPart(canonical, hit.title());
            appendFingerprintPart(canonical, hit.content());
            appendFingerprintPart(canonical, hit.sourceType());
            appendFingerprintPart(canonical, Double.toHexString(hit.score()));
        }
        return session.pseudonym("raw-input", canonical.toString());
    }

    private void appendFingerprintPart(StringBuilder target, String value) {
        String part = text(value);
        target.append(part.length()).append(':').append(part).append(';');
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String citationLabel(String evidenceId) {
        return "citation-label:" + evidenceId;
    }

    private void validate(QaGoldAnnotationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("QA gold annotation request is required");
        }
        if (!REQUEST_SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported QA gold annotation request schema: " + request.schemaVersion());
        }
        if (request.datasetVersion().isBlank()) {
            throw new IllegalArgumentException("QA gold annotation datasetVersion is required");
        }
        if (request.candidatePoolSize() <= 0 || request.candidatePoolSize() > 100) {
            throw new IllegalArgumentException("QA gold annotation candidatePoolSize must be between 1 and 100");
        }
        if (request.cases().isEmpty()) {
            throw new IllegalArgumentException("QA gold annotation cases are required");
        }
        Set<String> caseIds = new LinkedHashSet<>();
        for (CaseRequest item : request.cases()) {
            if (item == null) {
                throw new IllegalArgumentException("QA gold annotation case is required");
            }
            if (item.id().isBlank() || !caseIds.add(item.id())) {
                throw new IllegalArgumentException("QA gold annotation case ids must be unique and non-blank");
            }
            if (item.workspaceId().isBlank() || item.query().isBlank()) {
                throw new IllegalArgumentException(
                        "QA gold annotation workspace/query is required: " + item.id());
            }
            if (item.allowedSourceIds().isEmpty()
                    || item.allowedSourceIds().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(
                        "QA gold annotation allowedSourceIds are required: " + item.id());
            }
            if (item.topK() <= 0 || item.topK() > request.candidatePoolSize()) {
                throw new IllegalArgumentException(
                        "QA gold annotation topK must fit candidatePoolSize: " + item.id());
            }
        }
    }

    public record CaptureResult(
            QaGoldAnnotationDraft draft,
            RetrievalSnapshotSanitizer.RedactionStats redactionStats,
            Map<String, List<String>> selectedEvidenceIdsByCase
    ) {
        public CaptureResult(
                QaGoldAnnotationDraft draft,
                RetrievalSnapshotSanitizer.RedactionStats redactionStats
        ) {
            this(draft, redactionStats, Map.of());
        }

        public CaptureResult {
            selectedEvidenceIdsByCase = selectedEvidenceIdsByCase == null
                    ? Map.of()
                    : selectedEvidenceIdsByCase.entrySet().stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey,
                                    entry -> List.copyOf(entry.getValue())));
        }
    }

    private record AdmissionCandidate(
            ChunkSearchHit hit,
            DraftCandidate draftCandidate
    ) {
    }

    private record CapturedCase(
            DraftCase draftCase,
            List<String> selectedEvidenceIds
    ) {
        private CapturedCase {
            selectedEvidenceIds = List.copyOf(selectedEvidenceIds);
        }
    }
}
