package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.chat.NoteRetrievalService.WindowLocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NoteReadingPlanner {

    public List<ReadingWindow> plan(List<ReadingWindow> windows, String query, int limit) {
        return select(score(windows, terms(query)), limit);
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

    private List<ReadingWindow> score(List<ReadingWindow> windows, Set<String> terms) {
        return windows.stream().map(window -> window.withScore(score(
                window.heading() + "\n" + window.content() + "\n" + window.locationInfo(), terms))).toList();
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

    private Set<String> terms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String part : normalized.split("[^\\p{IsHan}a-zA-Z0-9]+")) if (!part.isBlank()) terms.add(part);
        if (terms.isEmpty() && normalized.length() >= 2) terms.add(normalized);
        return terms;
    }

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) return 1;
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (lower.contains(term)) score += Math.max(1, term.length());
        return score;
    }

    private String key(ReadingWindow window) {
        return window.sourceId() + ":" + window.chunkNo() + ":" + window.windowNo();
    }
}
