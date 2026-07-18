package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NoteReadingRetriever {
    private final RetrievalHydrator hydrator;
    private final NoteReadingPlanner planner;

    public NoteReadingRetriever(RetrievalHydrator hydrator, NoteReadingPlanner planner) {
        this.hydrator = hydrator;
        this.planner = planner;
    }

    public List<ReadingWindow> retrieve(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) return List.of();
        Map<String, List<ReadingWindow>> windows = hydrator.hydrateNoteWindows(
                workspaceId, sources.stream().map(CandidateSource::sourceId).toList());
        List<ReadingWindow> selected = new ArrayList<>();
        for (CandidateSource source : sources) {
            selected.addAll(planner.plan(windows.getOrDefault(source.sourceId(), List.of()), query, 3));
        }
        return selected.stream().sorted(Comparator
                        .comparingInt((ReadingWindow window) -> planner.rolePriority(window.readRole()))
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed())
                        .thenComparing(ReadingWindow::title).thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo)).limit(8).toList();
    }
}
