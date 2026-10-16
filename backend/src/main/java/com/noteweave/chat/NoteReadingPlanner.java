package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.chat.NoteRetrievalService.WindowLocator;
import com.noteweave.common.text.LexicalTerms;
import com.noteweave.retrieval.NoteRetrievalStrategyProfile;
import com.noteweave.retrieval.lexical.Bm25fScorer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NoteReadingPlanner {

    public List<ReadingWindow> plan(List<ReadingWindow> windows, String query, int limit) {
        return select(score(windows, query), limit);
    }

    /** 使用调用方已经写好的融合分，只做主窗口、延续窗口和次要窗口的选择。 */
    public List<ReadingWindow> planPrescored(List<ReadingWindow> windows, int limit) {
        return select(windows == null ? List.of() : windows, limit);
    }

    public List<WindowLocator> locators(List<ReadingWindow> windows, String query) {
        return plan(windows, query, 4).stream()
                .map(window -> new WindowLocator(
                        window.chunkNo(), window.heading(), window.windowNo(),
                        window.locationInfo(), window.score(), window.readRole(),
                        "continuation-window".equals(window.readRole()) ? window.anchorWindowNo() : null,
                        window.readObjective()))
                .toList();
    }

    public int rolePriority(String role) {
        return switch (role == null ? "" : role) {
            case "primary-window" -> 3;
            case "continuation-window" -> 2;
            case "secondary-window" -> 1;
            default -> 0;
        };
    }

    private List<ReadingWindow> score(List<ReadingWindow> windows, String query) {
        if (windows == null || windows.isEmpty()) {
            return List.of();
        }
        Set<String> terms = LexicalTerms.queryTerms(query);
        if (terms.isEmpty()) {
            return windows.stream().map(window -> window.withScore(Math.max(window.score(), 1))).toList();
        }
        List<Bm25fScorer.Document> documents = new ArrayList<>();
        for (ReadingWindow window : windows) {
            documents.add(new Bm25fScorer.Document(key(window), Map.of("window", windowText(window))));
        }
        Map<String, Double> scores = new HashMap<>();
        for (Bm25fScorer.Hit hit : new Bm25fScorer().score(
                Bm25fScorer.DEFAULT_K1,
                NoteRetrievalStrategyProfile.windowFields(),
                documents,
                terms,
                Map.of(),
                Map.of())) {
            scores.put(hit.id(), hit.score());
        }
        return windows.stream().map(window -> {
            double value = scores.getOrDefault(key(window), 0.0d);
            int score = value > 0.0d ? Math.max(1, (int) Math.round(value * 1_000d)) : 0;
            return window.withScore(score);
        }).toList();
    }

    private String windowText(ReadingWindow window) {
        return text(window.heading()) + "\n" + text(window.content()) + "\n" + text(window.locationInfo());
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private List<ReadingWindow> select(List<ReadingWindow> windows, int limit) {
        if (windows.isEmpty()) return List.of();
        List<ReadingWindow> ranked = windows.stream()
                .sorted(Comparator.comparingInt(ReadingWindow::score).reversed()
                        .thenComparingInt(ReadingWindow::chunkNo).thenComparingInt(ReadingWindow::windowNo)).toList();
        ReadingWindow primary = ranked.get(0).withReadRole("primary-window")
                .withAnchorWindowNo(ranked.get(0).windowNo()).withReadObjective("best-evidence");
        List<ReadingWindow> plan = new ArrayList<>();
        plan.add(primary);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(key(primary));
        windows.stream().filter(window -> ((window.chunkNo() == primary.chunkNo()
                        && Math.abs(window.windowNo() - primary.windowNo()) == 1)
                        || (Math.abs(window.chunkNo() - primary.chunkNo()) == 1
                        && window.windowNo() == primary.windowNo())) && seen.add(key(window)))
                .sorted(Comparator.comparingInt((ReadingWindow window) -> Math.abs(window.chunkNo() - primary.chunkNo()) * 10
                                + Math.abs(window.windowNo() - primary.windowNo()))
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed()))
                .limit(Math.max(0, limit - 1))
                .map(window -> window.withReadRole("continuation-window")
                        .withAnchorWindowNo(primary.windowNo()).withReadObjective("adjacent-context"))
                .forEach(plan::add);
        for (ReadingWindow window : ranked) {
            if (plan.size() >= limit) break;
            if (seen.add(key(window))) plan.add(window.withReadRole("secondary-window")
                    .withAnchorWindowNo(window.windowNo()).withReadObjective("secondary-evidence"));
        }
        return plan;
    }

    private String key(ReadingWindow window) {
        return window.sourceId() + ":" + window.chunkNo() + ":" + window.windowNo();
    }
}
