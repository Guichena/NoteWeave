package com.noteweave.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回忆：在冻结边界内展开旧回合。
 *
 * <p>显式指代只允许指向本次运行暴露过的句柄。无效句柄记录原因后丢弃，不做猜测性替换；
 * 存在的句柄仍要经过会话、工作台与当前可读资格检查，候选集里没有对应行就是不可读。</p>
 *
 * <p>没有明确指代时，在有界候选上用 BM25 检索旧回合。相关性使用绝对最低阈值，并且至少命中一个
 * 查询词；不使用最高分折半这类自适应阈值，那会把噪声当成命中。候选上限、命中上限、结果预算与
 * 遗漏原因全部记录。</p>
 */
public final class ConversationRecallPlanner {

    /** 回忆实现版本。 */
    public static final String PLANNER_VERSION = "conversation-recall-v1";
    /** 无效句柄：不在本次允许清单里。 */
    public static final String REASON_INVALID_HANDLE = "INVALID_HANDLE";
    /** 句柄存在但当前不可读。 */
    public static final String REASON_HANDLE_NOT_READABLE = "HANDLE_NOT_READABLE";
    /** 句柄超出冻结截止。 */
    public static final String REASON_BEYOND_CUTOFF = "BEYOND_CUTOFF";
    /** 没有候选。 */
    public static final String REASON_NO_CANDIDATES = "NO_CANDIDATES";
    /** 相关性低于最低阈值。 */
    public static final String REASON_BELOW_MIN_RELEVANCE = "BELOW_MIN_RELEVANCE";
    /** 结果预算不足。 */
    public static final String REASON_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    /** 命中上限已满。 */
    public static final String REASON_HIT_LIMIT_REACHED = "HIT_LIMIT_REACHED";

    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;

    /** 执行回忆。 */
    public RecallResult recall(RecallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("recall request is required");
        }
        List<Miss> misses = new ArrayList<>();
        Set<String> hitIds = new HashSet<>();
        List<Hit> hits = new ArrayList<>();
        int budget = request.resultReserveTokens();
        Set<String> requested = new LinkedHashSet<>(request.refersToHandles());
        for (String handle : requested) {
            if (!request.allowedHandles().containsKey(handle)) {
                misses.add(new Miss(handle, REASON_INVALID_HANDLE, "not exposed by the frozen view"));
                continue;
            }
            HandleRange range = request.allowedHandles().get(handle);
            if (range.endSeq() > request.cutoffSeq()) {
                misses.add(new Miss(handle, REASON_BEYOND_CUTOFF, "range=" + range.startSeq()
                        + "-" + range.endSeq()));
                continue;
            }
            Candidate candidate = request.candidateOf(handle);
            if (candidate == null) {
                misses.add(new Miss(handle, REASON_HANDLE_NOT_READABLE, "no readable ledger row"));
                continue;
            }
            if (!admit(candidate, budget, request.maxHits(), hits)) {
                misses.add(new Miss(handle, REASON_BUDGET_EXHAUSTED, "result reserve exhausted"));
                continue;
            }
            budget -= candidate.tokens();
            hitIds.add(handle);
            hits.add(new Hit(candidate.messageId(), candidate.seq(), candidate.role(), candidate.text(),
                    candidate.tokens(), "HANDLE_RESOLVED"));
        }
        if (hits.isEmpty()) {
            bm25(request, hits, misses, hitIds, budget);
        }
        return new RecallResult(PLANNER_VERSION, request.independentQuestion(), List.copyOf(hits),
                List.copyOf(misses), request.candidates().size(), request.maxCandidates(),
                request.minRelevance());
    }

    private void bm25(RecallRequest request, List<Hit> hits, List<Miss> misses,
                      Set<String> hitIds, int budget) {
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate candidate : request.candidates()) {
            if (candidate.seq() <= request.cutoffSeq() && !hitIds.contains(candidate.messageId())) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            misses.add(new Miss("", REASON_NO_CANDIDATES, "frozen cutoff has no readable rows"));
            return;
        }
        candidates.sort(Comparator.comparingInt(Candidate::seq));
        int considered = Math.min(candidates.size(), Math.max(0, request.maxCandidates()));
        List<Candidate> bounded = candidates.subList(Math.max(0, candidates.size() - considered),
                candidates.size());
        List<String> queryTerms = terms(request.independentQuestion());
        if (queryTerms.isEmpty()) {
            misses.add(new Miss("", REASON_NO_CANDIDATES, "empty independent question"));
            return;
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        List<Map<String, Integer>> frequencies = new ArrayList<>();
        for (Candidate candidate : bounded) {
            Map<String, Integer> counts = termCounts(candidate.text());
            frequencies.add(counts);
            totalLength += Math.max(1, candidate.text().length());
            for (String term : counts.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = totalLength / bounded.size();
        List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < bounded.size(); index++) {
            Candidate candidate = bounded.get(index);
            Map<String, Integer> counts = frequencies.get(index);
            double score = 0;
            int matched = 0;
            for (String term : queryTerms) {
                Integer frequency = counts.get(term);
                if (frequency == null) {
                    continue;
                }
                matched++;
                int documents = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0d + (bounded.size() - documents + 0.5d) / (documents + 0.5d));
                double length = Math.max(1, candidate.text().length());
                double denominator = frequency + BM25_K1 * (1 - BM25_B + BM25_B * length / averageLength);
                score += idf * frequency * (BM25_K1 + 1) / denominator;
            }
            if (matched == 0 || score < request.minRelevance()) {
                misses.add(new Miss(candidate.messageId(), REASON_BELOW_MIN_RELEVANCE,
                        "score=" + String.format(java.util.Locale.ROOT, "%.4f", score)));
                continue;
            }
            scored.add(new Scored(candidate, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingInt(scoredCandidate -> scoredCandidate.candidate().seq()));
        for (Scored entry : scored) {
            if (hits.size() >= request.maxHits()) {
                misses.add(new Miss(entry.candidate().messageId(), REASON_HIT_LIMIT_REACHED,
                        "maxHits=" + request.maxHits()));
                continue;
            }
            if (!admit(entry.candidate(), budget, request.maxHits(), hits)) {
                misses.add(new Miss(entry.candidate().messageId(), REASON_BUDGET_EXHAUSTED,
                        "result reserve exhausted"));
                continue;
            }
            budget -= entry.candidate().tokens();
            hitIds.add(entry.candidate().messageId());
            hits.add(new Hit(entry.candidate().messageId(), entry.candidate().seq(),
                    entry.candidate().role(), entry.candidate().text(), entry.candidate().tokens(),
                    "BM25_SCORE_" + String.format(java.util.Locale.ROOT, "%.4f", entry.score())));
        }
    }

    private boolean admit(Candidate candidate, int budget, int maxHits, List<Hit> hits) {
        return hits.size() < maxHits && candidate.tokens() <= budget;
    }

    /** 中日韩按二字切分，其他按字母数字词切分；查询与文档使用同一口径。 */
    static List<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        StringBuilder han = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(word, terms);
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHan(han, terms);
                word.appendCodePoint(codePoint);
            } else {
                flushHan(han, terms);
                flushWord(word, terms);
            }
        }
        flushHan(han, terms);
        flushWord(word, terms);
        return List.copyOf(terms);
    }

    private static void flushHan(StringBuilder han, Set<String> terms) {
        if (han.length() == 0) {
            return;
        }
        String value = han.toString();
        if (value.codePointCount(0, value.length()) == 1) {
            terms.add(value);
        } else {
            int[] codePoints = value.codePoints().toArray();
            for (int index = 0; index + 1 < codePoints.length; index++) {
                terms.add(new String(codePoints, index, 2));
            }
        }
        han.setLength(0);
    }

    private static void flushWord(StringBuilder word, Set<String> terms) {
        if (word.length() == 0) {
            return;
        }
        String value = word.toString();
        if (value.length() >= 2) {
            terms.add(value);
        }
        word.setLength(0);
    }

    private static Map<String, Integer> termCounts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : terms(text)) {
            counts.merge(term, 1, Integer::sum);
        }
        return counts;
    }

    /** 句柄覆盖的序号范围。 */
    public record HandleRange(int startSeq, int endSeq) {
        public HandleRange {
            if (startSeq < 1 || endSeq < startSeq) {
                throw new IllegalArgumentException("handle range is invalid");
            }
        }
    }

    /**
     * 回忆候选。
     *
     * @param messageId 消息身份
     * @param seq       序号
     * @param role      角色
     * @param text      正文
     * @param tokens    正文估算 token
     */
    public record Candidate(String messageId, int seq, String role, String text, int tokens) {
        public Candidate {
            text = text == null ? "" : text;
            if (messageId == null || messageId.isBlank() || seq < 1 || role == null || tokens < 0) {
                throw new IllegalArgumentException("recall candidate is invalid");
            }
        }
    }

    /**
     * 回忆请求。
     *
     * @param independentQuestion 独立问题
     * @param refersToHandles     模型给出的显式指代
     * @param allowedHandles      本次允许的句柄，键是句柄身份
     * @param cutoffSeq           冻结截止序号
     * @param candidates          冻结边界内可读候选
     * @param maxCandidates       候选上限
     * @param maxHits             命中上限
     * @param minRelevance        绝对最低相关性
     * @param resultReserveTokens 结果预留预算
     */
    public record RecallRequest(
            String independentQuestion,
            List<String> refersToHandles,
            Map<String, HandleRange> allowedHandles,
            int cutoffSeq,
            List<Candidate> candidates,
            int maxCandidates,
            int maxHits,
            double minRelevance,
            int resultReserveTokens
    ) {
        public RecallRequest {
            independentQuestion = independentQuestion == null ? "" : independentQuestion;
            refersToHandles = refersToHandles == null ? List.of() : List.copyOf(refersToHandles);
            allowedHandles = allowedHandles == null ? Map.of() : Map.copyOf(allowedHandles);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            if (cutoffSeq < 0 || maxCandidates < 0 || maxHits < 0
                    || minRelevance < 0 || resultReserveTokens < 0) {
                throw new IllegalArgumentException("recall request bounds are invalid");
            }
        }

        Candidate candidateOf(String messageId) {
            return candidates.stream()
                    .filter(candidate -> candidate.messageId().equals(messageId))
                    .filter(candidate -> candidate.seq() <= cutoffSeq)
                    .findFirst().orElse(null);
        }
    }

    /**
     * 命中。
     *
     * @param messageId 消息身份
     * @param seq       序号
     * @param role      角色
     * @param text      展开的正文
     * @param tokens    估算 token
     * @param reason    命中方式
     */
    public record Hit(String messageId, int seq, String role, String text, int tokens, String reason) {
        public Hit {
            text = text == null ? "" : text;
        }
    }

    /**
     * 遗漏。
     *
     * @param refId  句柄或消息身份，没有具体对象时为空串
     * @param reason 稳定原因码
     * @param detail 可读补充
     */
    public record Miss(String refId, String reason, String detail) {
        public Miss {
            refId = refId == null ? "" : refId;
            detail = detail == null ? "" : detail;
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("recall miss requires a reason");
            }
        }
    }

    /**
     * 回忆结果。
     *
     * @param plannerVersion      实现版本
     * @param independentQuestion 独立问题
     * @param hits                命中
     * @param misses              遗漏及原因
     * @param candidatesAvailable 候选总数
     * @param candidateLimit      候选上限
     * @param minRelevance        绝对最低相关性
     */
    public record RecallResult(String plannerVersion, String independentQuestion, List<Hit> hits,
                               List<Miss> misses, int candidatesAvailable, int candidateLimit,
                               double minRelevance) {
        public RecallResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
            misses = misses == null ? List.of() : List.copyOf(misses);
        }
    }

    private record Scored(Candidate candidate, double score) {
    }
}
