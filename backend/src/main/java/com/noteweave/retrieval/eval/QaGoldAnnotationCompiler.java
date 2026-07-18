package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.QaGoldAnnotationDraft.DraftCandidate;
import com.noteweave.retrieval.eval.QaGoldAnnotationDraft.DraftCase;
import com.noteweave.retrieval.eval.RetrievalGoldSet.Candidate;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QaGoldAnnotationCompiler {
    public static final String REVIEWED_STATUS = "REVIEWED";

    private final QaGoldAnnotationDraftCapture draftCapture;
    private final ObjectMapper objectMapper;

    public QaGoldAnnotationCompiler(
            QaGoldAnnotationDraftCapture draftCapture,
            ObjectMapper objectMapper
    ) {
        this.draftCapture = draftCapture;
        this.objectMapper = objectMapper;
    }

    public CompilationResult compileAndWrite(
            Path requestPath,
            Path reviewedDraftPath,
            Path sanitizedGoldOutput,
            Path shadowOutput,
            String snapshotVersion,
            String salt
    ) throws Exception {
        QaGoldAnnotationRequest request = objectMapper.readValue(
                requestPath.toFile(), QaGoldAnnotationRequest.class);
        QaGoldAnnotationDraft reviewed = objectMapper.readValue(
                reviewedDraftPath.toFile(), QaGoldAnnotationDraft.class);
        CompilationResult result = compile(request, reviewed, snapshotVersion, salt);
        createParent(sanitizedGoldOutput);
        createParent(shadowOutput);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(sanitizedGoldOutput.toFile(), result.sanitizedGoldSet());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(shadowOutput.toFile(), result.shadowSnapshot());
        return result;
    }

    public CompilationResult compile(
            QaGoldAnnotationRequest request,
            QaGoldAnnotationDraft reviewed,
            String snapshotVersion,
            String salt
    ) {
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("QA reviewed annotation snapshotVersion is required");
        }
        QaGoldAnnotationDraftCapture.CaptureResult current = draftCapture.capture(request, salt);
        validateHeader(request, reviewed, current.draft());

        Map<String, DraftCase> reviewedById = casesById(reviewed.cases(), "reviewed");
        Map<String, DraftCase> currentById = casesById(current.draft().cases(), "current");
        if (!reviewedById.keySet().equals(currentById.keySet())) {
            throw new IllegalArgumentException("Reviewed QA annotation cases do not match current capture");
        }

        List<GoldCase> goldCases = new ArrayList<>();
        List<CaseRanking> shadowCases = new ArrayList<>();
        for (DraftCase currentCase : current.draft().cases()) {
            DraftCase reviewedCase = reviewedById.get(currentCase.id());
            validateCaseIdentity(reviewedCase, currentCase);
            validateAnnotations(reviewedCase);
            goldCases.add(toGoldCase(reviewedCase, currentCase));
            List<String> selectedEvidenceIds = current.selectedEvidenceIdsByCase()
                    .get(currentCase.id());
            if (selectedEvidenceIds == null) {
                throw new IllegalStateException(
                        "QA annotation raw selection sidecar is missing: " + currentCase.id());
            }
            shadowCases.add(toShadowCase(currentCase, selectedEvidenceIds));
        }

        return new CompilationResult(
                new RetrievalGoldSet(
                        RetrievalBenchmarkReplay.GOLD_SCHEMA_VERSION,
                        request.datasetVersion() + "-reviewed-sanitized",
                        goldCases
                ),
                new RetrievalShadowSnapshot(
                        RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                        snapshotVersion,
                        QaRetrievalStrategyProfile.V2.profileVersion(),
                        shadowCases
                ),
                current.redactionStats()
        );
    }

    private void validateHeader(
            QaGoldAnnotationRequest request,
            QaGoldAnnotationDraft reviewed,
            QaGoldAnnotationDraft current
    ) {
        if (reviewed == null) {
            throw new IllegalArgumentException("Reviewed QA annotation draft is required");
        }
        if (!QaGoldAnnotationDraftCapture.DRAFT_SCHEMA_VERSION.equals(reviewed.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported reviewed QA annotation draft schema: " + reviewed.schemaVersion());
        }
        String expectedDatasetVersion = request.datasetVersion() + "-annotation-draft";
        if (!expectedDatasetVersion.equals(reviewed.datasetVersion())) {
            throw new IllegalArgumentException("Reviewed QA annotation datasetVersion does not match request");
        }
        if (reviewed.candidatePoolSize() != request.candidatePoolSize()
                || reviewed.candidatePoolSize() != current.candidatePoolSize()) {
            throw new IllegalArgumentException("Reviewed QA annotation candidatePoolSize does not match request");
        }
    }

    private Map<String, DraftCase> casesById(List<DraftCase> cases, String label) {
        Map<String, DraftCase> byId = new LinkedHashMap<>();
        for (DraftCase item : cases) {
            if (item == null || item.id().isBlank() || byId.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException(label + " QA annotation case ids must be unique and non-blank");
            }
        }
        return byId;
    }

    private void validateCaseIdentity(DraftCase reviewed, DraftCase current) {
        boolean identityMatches = reviewed.id().equals(current.id())
                && reviewed.mode().equals(current.mode())
                && reviewed.workspaceId().equals(current.workspaceId())
                && reviewed.query().equals(current.query())
                && reviewed.allowedSourceIds().equals(current.allowedSourceIds())
                && reviewed.topK() == current.topK();
        if (!identityMatches) {
            throw new IllegalArgumentException(
                    "Reviewed QA annotation case identity changed: " + current.id());
        }
        if (!reviewed.rawInputFingerprint().equals(current.rawInputFingerprint())
                || reviewed.candidateCount() != current.candidateCount()
                || !reviewed.candidates().equals(current.candidates())) {
            throw new IllegalStateException(
                    "QA annotation candidate drift detected; recapture before review: " + current.id());
        }
    }

    private void validateAnnotations(DraftCase reviewed) {
        if (!REVIEWED_STATUS.equals(reviewed.annotationStatus()) || reviewed.shouldRefuse() == null) {
            throw new IllegalArgumentException(
                    "QA annotation must be REVIEWED with shouldRefuse decided: " + reviewed.id());
        }
        Set<String> candidateIds = new LinkedHashSet<>();
        Map<String, DraftCandidate> candidateById = new LinkedHashMap<>();
        for (DraftCandidate candidate : reviewed.candidates()) {
            if (candidate.evidenceId().isBlank() || !candidateIds.add(candidate.evidenceId())) {
                throw new IllegalArgumentException(
                        "QA annotation candidate ids must be unique and non-blank: " + reviewed.id());
            }
            candidateById.put(candidate.evidenceId(), candidate);
        }
        Set<String> relevantIds = uniqueLabels(
                reviewed.relevantEvidenceIds(), "relevantEvidenceIds", reviewed.id());
        Set<String> expectedCitationIds = uniqueLabels(
                reviewed.expectedCitationIds(), "expectedCitationIds", reviewed.id());
        if (!candidateIds.containsAll(relevantIds)) {
            throw new IllegalArgumentException(
                    "QA annotation relevant evidence is missing from candidates: " + reviewed.id());
        }
        if (reviewed.shouldRefuse()) {
            if (!relevantIds.isEmpty() || !expectedCitationIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "QA refusal annotation cannot select evidence or citations: " + reviewed.id());
            }
            return;
        }
        if (relevantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "QA non-refusal annotation requires relevant evidence: " + reviewed.id());
        }
        if (relevantIds.stream().map(candidateById::get)
                .anyMatch(candidate -> !candidate.withinAllowedScope())) {
            throw new IllegalArgumentException(
                    "QA annotation relevant evidence is outside allowed scope: " + reviewed.id());
        }
        Set<String> relevantCitationIds = new LinkedHashSet<>();
        relevantIds.stream().map(candidateById::get)
                .forEach(candidate -> relevantCitationIds.addAll(candidate.citationIds()));
        if (!expectedCitationIds.equals(relevantCitationIds)) {
            throw new IllegalArgumentException(
                    "QA annotation expected citations must exactly match relevant evidence: " + reviewed.id());
        }
    }

    private Set<String> uniqueLabels(List<String> values, String field, String caseId) {
        Set<String> labels = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !labels.add(value)) {
                throw new IllegalArgumentException(
                        "QA annotation " + field + " must be unique and non-blank: " + caseId);
            }
        }
        return labels;
    }

    private GoldCase toGoldCase(DraftCase reviewed, DraftCase current) {
        List<Candidate> candidates = current.candidates().stream()
                .map(candidate -> new Candidate(
                        candidate.evidenceId(),
                        candidate.sourceId(),
                        candidate.title(),
                        candidate.content(),
                        candidate.sourceType(),
                        candidate.citationIds()
                ))
                .toList();
        return new GoldCase(
                current.id(),
                "QA",
                current.workspaceId(),
                current.query(),
                current.allowedSourceIds(),
                candidates,
                reviewed.relevantEvidenceIds(),
                reviewed.expectedCitationIds(),
                reviewed.shouldRefuse(),
                current.topK()
        );
    }

    private CaseRanking toShadowCase(
            DraftCase current,
            List<String> selectedEvidenceIds
    ) {
        Map<String, DraftCandidate> scopedCandidateById = new LinkedHashMap<>();
        current.candidates().stream()
                .filter(DraftCandidate::withinAllowedScope)
                .forEach(candidate -> scopedCandidateById.put(candidate.evidenceId(), candidate));
        Set<String> uniqueSelectedIds = new LinkedHashSet<>(selectedEvidenceIds);
        if (uniqueSelectedIds.size() != selectedEvidenceIds.size()
                || !scopedCandidateById.keySet().containsAll(uniqueSelectedIds)) {
            throw new IllegalStateException(
                    "QA annotation raw selection sidecar does not match scoped candidates: "
                            + current.id());
        }
        List<RankedEvidence> ranking = selectedEvidenceIds.stream()
                .map(scopedCandidateById::get)
                .map(candidate -> new RankedEvidence(
                        candidate.evidenceId(),
                        candidate.sourceId(),
                        candidate.score(),
                        candidate.citationIds()
                ))
                .toList();
        return new CaseRanking(
                current.id(),
                current.latencyMicros(),
                current.candidateCount(),
                ranking
        );
    }

    private void createParent(Path output) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public record CompilationResult(
            RetrievalGoldSet sanitizedGoldSet,
            RetrievalShadowSnapshot shadowSnapshot,
            RetrievalSnapshotSanitizer.RedactionStats redactionStats
    ) {
    }
}
