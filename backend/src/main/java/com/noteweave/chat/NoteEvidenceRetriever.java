package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.NoteRetrievalService.NoteEntryMetadata;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NoteEvidenceRetriever implements EvidenceRetriever {

    public static final String CHANNEL = "NOTE_MARGINALIA";

    private final NoteRecallRetriever recallRetriever;
    private final NoteRetrievalService retrievalService;
    private final NoteReadingRetriever readingRetriever;
    private final NoteRetrievalSnapshotCodec snapshotCodec;

    public NoteEvidenceRetriever(
            NoteRecallRetriever recallRetriever,
            NoteRetrievalService retrievalService,
            NoteReadingRetriever readingRetriever,
            NoteRetrievalSnapshotCodec snapshotCodec
    ) {
        this.recallRetriever = recallRetriever;
        this.retrievalService = retrievalService;
        this.readingRetriever = readingRetriever;
        this.snapshotCodec = snapshotCodec;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(
            AnswerContext context,
            RetrievalPlan plan,
            RetrievalPlan.Step step
    ) {
        NoteRecallPlan recallPlan = recallRetriever.retrieve(context.workspaceId(), context.query());
        List<NoteEntryMetadata> metadataEntries = retrievalService.readEntriesMetadataForNote(
                context.workspaceId(), recallPlan.verifySources(), context.query());
        List<ReadingWindow> windows = readingRetriever.retrieve(
                        context.workspaceId(), recallPlan.verifySources(), context.query()).stream()
                .limit(Math.max(0, step.candidateLimit()))
                .toList();
        NoteRetrievalSnapshot snapshot = new NoteRetrievalSnapshot(recallPlan, metadataEntries, windows);
        List<EvidenceBundle.Evidence> evidence = java.util.stream.IntStream.range(0, windows.size())
                .mapToObj(index -> toEvidence(windows.get(index), index, windows.size(), step.weight()))
                .toList();
        return new EvidenceRetrievalResult(
                evidence,
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, snapshotCodec.encode(snapshot)),
                false,
                List.of()
        );
    }

    private EvidenceBundle.Evidence toEvidence(
            ReadingWindow window,
            int index,
            int total,
            double weight
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("raw_title", text(window.title()));
        metadata.put("source_type", "SOURCE");
        metadata.put("generated_by", text(window.generatedBy()));
        metadata.put("generated_ref_id", text(window.generatedRefId()));
        metadata.put("chunk_no", Integer.toString(window.chunkNo()));
        metadata.put("window_no", Integer.toString(window.windowNo()));
        metadata.put("heading", text(window.heading()));
        metadata.put("read_role", text(window.readRole()));
        metadata.put("anchor_window_no", Integer.toString(window.anchorWindowNo()));
        metadata.put("read_objective", text(window.readObjective()));
        metadata.put("freshness_status", "CURRENT_AT_RETRIEVAL");
        double orderingScore = (total - index) * weight;
        return new EvidenceBundle.Evidence(
                "note-window:" + window.chunkId() + ":" + window.windowNo(),
                "PASSAGE",
                window.sourceId(),
                window.sourceSnapshotId(),
                window.chunkId(),
                "",
                "",
                displayTitle(window.title(), window.generatedBy(), window.generatedRefId()),
                window.content(),
                window.locationInfo(),
                window.score(),
                orderingScore,
                orderingScore,
                "workspace-source:" + window.sourceId(),
                java.time.Instant.now(),
                text(window.readRole()).isBlank() ? "source-window" : window.readRole(),
                window.content() == null ? 0 : window.content().length(),
                metadata
        );
    }

    private String displayTitle(String title, String generatedBy, String generatedRefId) {
        if ("research_agent".equals(generatedBy)) {
            return title + " · Research Report(" + (
                    generatedRefId == null || generatedRefId.isBlank() ? "unknown run" : generatedRefId) + ")";
        }
        return title;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
