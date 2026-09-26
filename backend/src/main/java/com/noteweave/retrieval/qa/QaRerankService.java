package com.noteweave.retrieval.qa;

import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaRrfFusionService.FusedHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QaRerankService {
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern RAW_URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKERS = Pattern.compile("(?m)^(?:#{1,6}|>|[-*+] |\\d+\\. )\\s*");
    private final RerankClient rerankClient;

    public QaRerankService(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    public RerankOutcome rerank(String query, List<FusedHit> candidates, int topN) {
        List<FusedHit> safeCandidates = candidates == null ? List.of() : candidates;
        if (safeCandidates.isEmpty() || topN <= 0) {
            return new RerankOutcome(List.of(), false, List.of(), "");
        }
        if (!rerankClient.isEnabled()) {
            return degraded(safeCandidates, topN, "QA_RERANK_UNAVAILABLE");
        }
        try {
            RerankClient.RerankResult result = rerankClient.rerank(
                    query,
                    safeCandidates.stream().map(this::document).toList(),
                    Math.min(topN, safeCandidates.size()));
            if (result == null || result.hits() == null || result.hits().isEmpty()) {
                return degraded(safeCandidates, topN, "QA_RERANK_UNAVAILABLE");
            }
            Set<Integer> returnedIndexes = new HashSet<>();
            for (RerankClient.Hit hit : result.hits()) {
                if (hit == null || hit.documentIndex() < 0 || hit.documentIndex() >= safeCandidates.size()
                        || !Double.isFinite(hit.score()) || !returnedIndexes.add(hit.documentIndex())) {
                    return degraded(safeCandidates, topN, "QA_RERANK_UNAVAILABLE");
                }
            }
            Map<Integer, RerankClient.Hit> byIndex = new HashMap<>();
            result.hits().forEach(hit -> byIndex.put(hit.documentIndex(), hit));
            List<RankedHit> ranked = new ArrayList<>();
            for (RerankClient.Hit rerankHit : result.hits()) {
                if (rerankHit.documentIndex() < 0 || rerankHit.documentIndex() >= safeCandidates.size()) {
                    continue;
                }
                FusedHit candidate = safeCandidates.get(rerankHit.documentIndex());
                ranked.add(new RankedHit(candidate, rerankHit.score(), rerankHit.rank(), false));
            }
            for (int index = 0; index < safeCandidates.size(); index++) {
                if (!byIndex.containsKey(index)) {
                    FusedHit candidate = safeCandidates.get(index);
                    ranked.add(new RankedHit(candidate, candidate.rrfScore(), ranked.size() + 1, true));
                }
            }
            return new RerankOutcome(ranked, false, List.of(), result.model());
        } catch (RuntimeException ex) {
            return degraded(safeCandidates, topN, "QA_RERANK_UNAVAILABLE");
        }
    }

    private RerankOutcome degraded(List<FusedHit> candidates, int topN, String reason) {
        List<RankedHit> ranked = java.util.stream.IntStream.range(0, Math.min(topN, candidates.size()))
                .mapToObj(index -> new RankedHit(
                        candidates.get(index), candidates.get(index).rrfScore(), index + 1, true))
                .toList();
        return new RerankOutcome(ranked, true, List.of(reason), "");
    }

    private String document(FusedHit candidate) {
        return String.join("\n",
                text(candidate.title()),
                text(candidate.heading()),
                text(candidate.sourceType()),
                clean(candidate.content()));
    }

    private String clean(String value) {
        String text = value == null ? "" : value;
        text = CODE_BLOCK.matcher(text).replaceAll(" ");
        text = MARKDOWN_IMAGE.matcher(text).replaceAll(" ");
        text = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        text = RAW_URL.matcher(text).replaceAll(" ");
        text = MARKERS.matcher(text).replaceAll("");
        return text.replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record RankedHit(
            FusedHit hit,
            double rerankScore,
            int rerankRank,
            boolean fallbackScore
    ) {
    }

    public record RerankOutcome(
            List<RankedHit> hits,
            boolean degraded,
            List<String> degradationReasons,
            String model
    ) {
        public RerankOutcome {
            hits = hits == null ? List.of() : List.copyOf(hits);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            model = model == null ? "" : model;
        }
    }
}
