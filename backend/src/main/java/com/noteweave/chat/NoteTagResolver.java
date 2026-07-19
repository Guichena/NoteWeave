package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.source.SourceTagCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves query tag hints against the current workspace source vocabulary. */
@Component
public class NoteTagResolver {
    private final SourceTagCodec sourceTagCodec;

    public NoteTagResolver(SourceTagCodec sourceTagCodec) {
        this.sourceTagCodec = sourceTagCodec;
    }

    public Resolution resolve(Set<String> queryTerms, List<CandidateSource> sources) {
        Set<String> safeTerms = queryTerms == null ? Set.of() : queryTerms;
        if (safeTerms.isEmpty() || sources == null || sources.isEmpty()) {
            return new Resolution(List.of(), List.copyOf(safeTerms), Map.of());
        }
        Map<String, Set<String>> tagsBySource = new LinkedHashMap<>();
        Map<String, String> vocabulary = new LinkedHashMap<>();
        for (CandidateSource source : sources) {
            Set<String> tags = new LinkedHashSet<>(sourceTagCodec.decode(source.tagsJson()));
            tagsBySource.put(source.sourceId(), tags);
            for (String tag : tags) {
                vocabulary.putIfAbsent(normalize(tag), tag);
                int separator = tag.indexOf(':');
                if (separator >= 0 && separator + 1 < tag.length()) {
                    vocabulary.putIfAbsent(normalize(tag.substring(separator + 1)), tag);
                }
            }
        }
        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String term : safeTerms) {
            String tag = vocabulary.get(normalize(term));
            if (tag == null) unresolved.add(term);
            else if (!resolved.contains(tag)) resolved.add(tag);
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : tagsBySource.entrySet()) {
            int score = 0;
            for (String resolvedTag : resolved) {
                if (entry.getValue().stream().anyMatch(tag -> normalize(tag).equals(normalize(resolvedTag)))) {
                    score += facetWeight(resolvedTag);
                }
            }
            if (score > 0) scores.put(entry.getKey(), score);
        }
        return new Resolution(List.copyOf(resolved), List.copyOf(unresolved), Map.copyOf(scores));
    }

    private int facetWeight(String tag) {
        String facet = tag.contains(":") ? tag.substring(0, tag.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        return switch (facet) {
            case "topic" -> 12;
            case "source" -> 9;
            case "time", "extra" -> 8;
            case "form" -> 5;
            case "language" -> 3;
            default -> 6;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^#", "").replaceAll("\\s+", " ");
    }

    public record Resolution(
            List<String> resolvedTags,
            List<String> unresolvedTerms,
            Map<String, Integer> sourceScores
    ) {
    }
}
