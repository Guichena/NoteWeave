package com.noteweave.retrieval.qa;

import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaRrfFusionService.FusedHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QaRerankService {
    private static final Pattern CODE_FENCE = Pattern.compile("```+[A-Za-z0-9_+-]*");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern MARKERS = Pattern.compile("(?m)^(?:#{1,6}|>|[-*+] |\\d+\\. )\\s*");
    private static final Pattern BARE_HTTP_URL = Pattern.compile("https?://[^\\s<>\"'`\\]\\)]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_QUERY_NAME = Pattern.compile(
            "(?i)^(x-amz-.+|x-goog-.+|awsaccesskeyid|signature|sig|token|access[_-]?token|security[_-]?token)$");
    private static final String URL_TRAILING_PUNCTUATION = ".,;:!?";
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
            boolean hasScored = !ranked.isEmpty();
            double minScored = hasScored
                    ? ranked.stream().mapToDouble(RankedHit::rerankScore).min().orElse(0.0d)
                    : 0.0d;
            int unscored = 0;
            for (int index = 0; index < safeCandidates.size(); index++) {
                if (byIndex.containsKey(index)) {
                    continue;
                }
                FusedHit candidate = safeCandidates.get(index);
                // Unscored hits stay behind every scored hit, in their original order.
                // An empty scored set keeps the previous RRF score.
                double score = hasScored
                        ? minScored - 1e-6d * (unscored + 1)
                        : candidate.rrfScore();
                unscored++;
                ranked.add(new RankedHit(candidate, score, ranked.size() + 1, true));
            }
            // 部分评分观测：记录供应商实际返回分数的数量与未评分数量，尾部候选保留
            return new RerankOutcome(ranked, false, List.of(), result.model(), ranked.size() - unscored, unscored);
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
        text = CODE_FENCE.matcher(text).replaceAll(" ");
        text = MARKDOWN_IMAGE.matcher(text).replaceAll(" ");
        text = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        text = MARKERS.matcher(text).replaceAll("");
        text = stripSensitiveUrlQueryParams(text);
        return text.replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String stripSensitiveUrlQueryParams(String text) {
        Matcher matcher = BARE_HTTP_URL.matcher(text);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group();
            String trailing = "";
            while (!raw.isEmpty() && URL_TRAILING_PUNCTUATION.indexOf(raw.charAt(raw.length() - 1)) >= 0) {
                trailing = raw.charAt(raw.length() - 1) + trailing;
                raw = raw.substring(0, raw.length() - 1);
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(stripSensitiveQuery(raw) + trailing));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String stripSensitiveQuery(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return url;
        }
        int fragmentStart = url.indexOf('#', queryStart + 1);
        String base = url.substring(0, queryStart);
        String query = fragmentStart < 0
                ? url.substring(queryStart + 1)
                : url.substring(queryStart + 1, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        List<String> kept = new ArrayList<>();
        for (String param : query.split("&", -1)) {
            if (param.isEmpty()) {
                continue;
            }
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            if (!SENSITIVE_QUERY_NAME.matcher(name).matches()) {
                kept.add(param);
            }
        }
        if (kept.isEmpty()) {
            return base + fragment;
        }
        return base + "?" + String.join("&", kept) + fragment;
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
            String model,
            int scoredCount,
            int unscoredCount
    ) {
        public RerankOutcome {
            hits = hits == null ? List.of() : List.copyOf(hits);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            model = model == null ? "" : model;
        }

        /** 兼容旧构造：不区分部分评分覆盖。 */
        public RerankOutcome(List<RankedHit> hits, boolean degraded,
                             List<String> degradationReasons, String model) {
            this(hits, degraded, degradationReasons, model, 0, 0);
        }
    }
}
