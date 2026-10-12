package com.noteweave.conversation;

import com.noteweave.common.text.LexicalTerms;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按话题切分一段对话，每条消息的归属只依赖它之前的内容，可以随消息追加逐条重算。
 * <p>
 * 每个话题维护一份词项画像，收录话题内用户消息和助手回答里出现过的词项。新的用户消息对每个话题计算覆盖度：
 * 消息中能在话题画像里找到的词项按 IDF 加权，占消息全部词项的比例。IDF 只用已经出现过的消息统计，
 * 在对话里反复出现的词权重低；"解释""介绍"这类请求用语作为停用词不计入，不会仅凭一个常用词就把两个无关的话题连上。
 * 覆盖度达到阈值时归入覆盖度最高的话题；当前话题与最高者相差不大时留在当前话题，避免来回跳。"回到""改聊""更正"等显式说法优先于相似度。内容词很少的短追问（例如"住两晚。"）
 * 不足以开启新话题，留在当前话题。
 * <p>
 * 语义路径在词项覆盖度之外，再用话题内用户消息向量的指数滑动平均做余弦，并看相邻用户消息余弦的落差。
 * 句首延续只在余弦足够时加分。向量缺失时退回上面的词项路径。
 * <p>
 * 已经持久化的切分结果作为固定前缀传入：前缀覆盖的消息沿用原来的话题，只用来更新画像；新规则只作用于之后的消息，
 * 规则升级不会改写已有会话的话题划分。
 */
public final class TopicSegmenterV2 {
    public static final String RULE_VERSION = "topic-segmenter-v2-a2";
    public static final String SEMANTIC_RULE_VERSION = "topic-segmenter-v2-a3";
    public static final String VECTOR_UNAVAILABLE = "TOPIC_SEGMENT_VECTOR_UNAVAILABLE";
    private static final Pattern RETURN = Pattern.compile("^(回到|再谈|接着谈|继续讨论)");
    private static final Pattern SWITCH = Pattern.compile("^(改聊|换个话题|另外聊|现在解释|现在谈)");
    private static final Pattern CONTINUE = Pattern.compile("^(更正|补充|继续|那|这|第二|第三|上一)");
    private static final Pattern FOLLOW_UP_QUESTION = Pattern.compile("什么时候|为什么|为何");
    /**
     * 归入已有话题需要的最低覆盖度。中文二元组里有不少跨词的无意义组合（例如"败以"），几乎不会出现在任何话题里，
     * 会同样压低所有话题的覆盖度，所以阈值取得较低，主要靠话题之间的相对高低做判断。
     */
    static final double MATCH_THRESHOLD = 0.12d;
    /** 用户明确说"回到……"时，找回旧话题需要的相似度可以低一些。 */
    static final double RETURN_THRESHOLD = 0.06d;
    /** 当前话题的覆盖度达到最高者的这个比例时，留在当前话题，避免在相近的话题之间来回跳。 */
    static final double STICKY_RATIO = 0.85d;
    /** 助手回答计入话题画像的权重。 */
    static final double ASSISTANT_WEIGHT = 0.3d;
    /** 内容词项不超过这个数的消息视为省略了主语的短追问，不足以开启新话题。 */
    static final int SHORT_FOLLOW_UP_TERMS = 6;
    /** 词项覆盖度与余弦各占一半。没有标注集，不另做拟合。 */
    static final double FUSION_WEIGHT = 0.5d;
    /** 话题向量的指数滑动平均系数。 */
    static final double EMA_ALPHA = 0.5d;
    static final double FUSED_MATCH = 0.40d;
    static final double RETURN_FUSED = 0.30d;
    /** 句首延续和相邻延续生效所需的最低余弦。 */
    static final double CONTINUATION_COSINE = 0.55d;
    /** 短追问留在当前话题所需的最低余弦。 */
    static final double SHORT_FOLLOW_UP_COSINE = 0.45d;
    /** 相邻余弦相对前一间隙的落差达到这个值，视为话题边界。 */
    static final double DEPTH_DROP = 0.15d;
    /** 与上一条用户消息的余弦低于这个值，直接视为新边界。 */
    static final double LOW_ADJACENT = 0.35d;

    public Projection segment(List<Message> messages) {
        return segment(messages, List.of());
    }

    /**
     * @param fixedPrefix 已经持久化的切分结果，按起始位置排序；覆盖范围内的消息沿用原话题
     */
    public Projection segment(List<Message> messages, List<Segment> fixedPrefix) {
        return run(messages, fixedPrefix, false);
    }

    /** 用消息上的向量做语义切分。非空用户消息缺少向量时退回词项路径，并记下降级原因。 */
    public Projection segmentSemantic(List<Message> messages) {
        return segmentSemantic(messages, List.of());
    }

    public Projection segmentSemantic(List<Message> messages, List<Segment> fixedPrefix) {
        if (vectorsMissing(messages)) {
            Projection lexical = run(messages, fixedPrefix, false);
            return new Projection(lexical.segments(), List.of(VECTOR_UNAVAILABLE));
        }
        return run(messages, fixedPrefix, true);
    }

    private Projection run(List<Message> messages, List<Segment> fixedPrefix, boolean semantic) {
        List<Segment> segments = new ArrayList<>();
        Profiles profiles = new Profiles();
        SemanticState vectors = semantic ? new SemanticState() : null;
        int prefixEnd = fixedPrefix.isEmpty() ? 0 : fixedPrefix.get(fixedPrefix.size() - 1).endSeq();
        int previousSeq = 0;
        for (Message message : messages) {
            if (message.seq() <= previousSeq || message.messageId().isBlank()
                    || !Set.of("USER", "ASSISTANT").contains(message.role())) {
                throw new IllegalArgumentException("topic messages must be ordered and identified");
            }
            previousSeq = message.seq();
            Map<String, Integer> terms = termFrequencies(message.text());
            if (message.seq() <= prefixEnd) {
                Segment fixed = fixedPrefix.stream()
                        .filter(segment -> segment.startSeq() <= message.seq() && message.seq() <= segment.endSeq())
                        .findFirst().orElse(null);
                if (fixed != null) {
                    appendFixed(segments, fixed, message.seq());
                    profiles.add(fixed.topicId(), message.role(), terms);
                    if (vectors != null && userContent(message)) {
                        vectors.noteGap(message.vector());
                        vectors.commit(fixed.topicId(), message.vector());
                    }
                    continue;
                }
            }
            Segment active = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            boolean semanticUser = vectors != null && userContent(message);
            if (semanticUser) {
                vectors.noteGap(message.vector());
            }
            Choice choice = semanticUser
                    ? chooseSemantic(message, terms, active, profiles, vectors)
                    : choose(message, terms, active, profiles);
            if (active != null && active.topicId().equals(choice.topicId())) {
                segments.set(segments.size() - 1, new Segment(active.segmentId(), active.topicId(),
                        active.startSeq(), message.seq(),
                        "UNCERTAIN".equals(choice.status()) ? "UNCERTAIN" : active.status(),
                        choice.reason(), active.ruleVersion()));
            } else {
                String segmentId = "segment-" + digest(choice.topicId() + ":" + message.seq()).substring(0, 24);
                segments.add(new Segment(segmentId, choice.topicId(), message.seq(), message.seq(),
                        choice.status(), choice.reason(),
                        semantic ? SEMANTIC_RULE_VERSION : RULE_VERSION));
            }
            if (semanticUser) {
                vectors.commit(choice.topicId(), message.vector());
            }
            profiles.add(choice.topicId(), message.role(), terms);
        }
        return new Projection(List.copyOf(segments));
    }

    private static boolean vectorsMissing(List<Message> messages) {
        for (Message message : messages) {
            if (userContent(message) && (message.vector() == null || message.vector().isEmpty())) {
                return true;
            }
        }
        return false;
    }

    private static boolean userContent(Message message) {
        return "USER".equals(message.role()) && message.text() != null && !message.text().isBlank();
    }

    private void appendFixed(List<Segment> segments, Segment fixed, int seq) {
        Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (last != null && last.segmentId().equals(fixed.segmentId())) {
            segments.set(segments.size() - 1, new Segment(last.segmentId(), last.topicId(), last.startSeq(),
                    seq, fixed.status(), fixed.reason(), fixed.ruleVersion()));
        } else {
            segments.add(new Segment(fixed.segmentId(), fixed.topicId(), seq, seq,
                    fixed.status(), fixed.reason(), fixed.ruleVersion()));
        }
    }

    private Choice choose(Message message, Map<String, Integer> terms, Segment active, Profiles profiles) {
        if (active == null) return fresh(message, "FIRST_MESSAGE", "CONFIDENT");
        if (!"USER".equals(message.role()) || message.text().isBlank()) {
            return new Choice(active.topicId(), "ASSISTANT_OR_EMPTY", active.status());
        }
        String text = message.text().trim();
        Map<String, Double> similarity = profiles.similarities(terms);
        if (RETURN.matcher(text).find()) {
            Map.Entry<String, Double> best = best(similarity);
            if (best != null && best.getValue() >= RETURN_THRESHOLD) {
                return new Choice(best.getKey(), "EXPLICIT_TOPIC_RETURN", "CONFIDENT");
            }
            return fresh(message, "UNRESOLVED_TOPIC_RETURN", "UNCERTAIN");
        }
        if (SWITCH.matcher(text).find()) return fresh(message, "EXPLICIT_TOPIC_SWITCH", "CONFIDENT");
        if (CONTINUE.matcher(text).find() || FOLLOW_UP_QUESTION.matcher(text).find()) {
            return new Choice(active.topicId(), "EXPLICIT_CONTINUATION", active.status());
        }
        Map.Entry<String, Double> best = best(similarity);
        double activeScore = similarity.getOrDefault(active.topicId(), 0.0d);
        if (best != null && best.getValue() >= MATCH_THRESHOLD) {
            if (activeScore >= MATCH_THRESHOLD && activeScore >= best.getValue() * STICKY_RATIO) {
                return new Choice(active.topicId(), "ACTIVE_TOPIC_SIMILAR", "CONFIDENT");
            }
            return new Choice(best.getKey(), best.getKey().equals(active.topicId())
                    ? "ACTIVE_TOPIC_SIMILAR" : "PRIOR_TOPIC_SIMILAR", "CONFIDENT");
        }
        if (terms.size() <= SHORT_FOLLOW_UP_TERMS) {
            return new Choice(active.topicId(), "SHORT_FOLLOW_UP", active.status());
        }
        return fresh(message, "NO_SIMILAR_TOPIC", "UNCERTAIN");
    }

    private Choice chooseSemantic(Message message, Map<String, Integer> terms, Segment active,
                                  Profiles profiles, SemanticState vectors) {
        if (active == null) return fresh(message, "FIRST_MESSAGE", "CONFIDENT");
        String text = message.text().trim();
        Map<String, Double> lexical = profiles.similarities(terms);
        Map<String, Double> cosine = vectors.cosines(message.vector());
        if (RETURN.matcher(text).find()) {
            String best = bestTopic(lexical, cosine);
            if (best != null) {
                double fused = fuse(lexical, cosine, best);
                double topicCosine = Math.max(0.0d, cosine.getOrDefault(best, 0.0d));
                if (fused >= RETURN_FUSED || topicCosine >= CONTINUATION_COSINE) {
                    return new Choice(best, "EXPLICIT_TOPIC_RETURN", "CONFIDENT");
                }
            }
            return fresh(message, "UNRESOLVED_TOPIC_RETURN", "UNCERTAIN");
        }
        if (SWITCH.matcher(text).find()) return fresh(message, "EXPLICIT_TOPIC_SWITCH", "CONFIDENT");
        double activeCosine = Math.max(0.0d, cosine.getOrDefault(active.topicId(), 0.0d));
        if ((CONTINUE.matcher(text).find() || FOLLOW_UP_QUESTION.matcher(text).find())
                && activeCosine >= CONTINUATION_COSINE) {
            return new Choice(active.topicId(), "CONTINUATION_WITH_SEMANTICS", active.status());
        }
        String best = bestTopic(lexical, cosine);
        double activeFused = fuse(lexical, cosine, active.topicId());
        if (best != null) {
            double bestFused = fuse(lexical, cosine, best);
            if (bestFused >= FUSED_MATCH) {
                if (activeFused >= FUSED_MATCH && activeFused >= bestFused * STICKY_RATIO) {
                    return new Choice(active.topicId(), "ACTIVE_TOPIC_SIMILAR", "CONFIDENT");
                }
                return new Choice(best, best.equals(active.topicId())
                        ? "ACTIVE_TOPIC_SIMILAR" : "PRIOR_TOPIC_SIMILAR", "CONFIDENT");
            }
        }
        if (terms.size() <= SHORT_FOLLOW_UP_TERMS && activeCosine >= SHORT_FOLLOW_UP_COSINE) {
            return new Choice(active.topicId(), "SHORT_FOLLOW_UP", active.status());
        }
        boolean lowAdjacent = vectors.hasPreviousUser() && vectors.currentAdjacent() < LOW_ADJACENT;
        boolean depthDrop = vectors.hasPreviousGap()
                && vectors.depthDrop() >= DEPTH_DROP
                && vectors.currentAdjacent() < CONTINUATION_COSINE;
        if (lowAdjacent || depthDrop) {
            return fresh(message, "DEPTH_BOUNDARY", "CONFIDENT");
        }
        if (vectors.hasPreviousUser() && vectors.currentAdjacent() >= CONTINUATION_COSINE) {
            return new Choice(active.topicId(), "ACTIVE_TOPIC_SIMILAR", "CONFIDENT");
        }
        return fresh(message, "NO_SIMILAR_TOPIC", "UNCERTAIN");
    }

    private static String bestTopic(Map<String, Double> lexical, Map<String, Double> cosine) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(lexical.keySet());
        ids.addAll(cosine.keySet());
        String best = null;
        double bestFused = -1.0d;
        double bestCosine = -1.0d;
        for (String id : ids) {
            double fused = fuse(lexical, cosine, id);
            double topicCosine = Math.max(0.0d, cosine.getOrDefault(id, 0.0d));
            if (best == null || fused > bestFused || (fused == bestFused && topicCosine > bestCosine)) {
                best = id;
                bestFused = fused;
                bestCosine = topicCosine;
            }
        }
        return best;
    }

    private static double fuse(Map<String, Double> lexical, Map<String, Double> cosine, String topicId) {
        double covered = lexical.getOrDefault(topicId, 0.0d);
        double topicCosine = Math.max(0.0d, cosine.getOrDefault(topicId, 0.0d));
        return FUSION_WEIGHT * covered + FUSION_WEIGHT * topicCosine;
    }

    static double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static Map.Entry<String, Double> best(Map<String, Double> similarity) {
        Map.Entry<String, Double> best = null;
        for (Map.Entry<String, Double> entry : similarity.entrySet()) {
            if (best == null || entry.getValue() > best.getValue()) best = entry;
        }
        return best;
    }

    private static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        for (String term : LexicalTerms.tokens(text)) {
            if (!LexicalTerms.isStopTerm(term)) frequencies.merge(term, 1, Integer::sum);
        }
        return frequencies;
    }

    private Choice fresh(Message message, String reason, String status) {
        return new Choice("topic-" + digest(message.messageId()).substring(0, 24), reason, status);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 各话题的词项画像，以及到目前为止每个词出现在多少条消息里。 */
    private static final class Profiles {
        private final Map<String, Map<String, Double>> byTopic = new LinkedHashMap<>();
        private final Map<String, Integer> documentFrequency = new HashMap<>();
        private int documents;

        void add(String topicId, String role, Map<String, Integer> terms) {
            if (terms.isEmpty()) return;
            double weight = "ASSISTANT".equals(role) ? ASSISTANT_WEIGHT : 1.0d;
            Map<String, Double> profile = byTopic.computeIfAbsent(topicId, ignored -> new HashMap<>());
            terms.forEach((term, count) -> {
                // 取最大值而不是累加：长回答里反复出现的词不会累积成"用户说过"的权重
                profile.merge(term, weight, Math::max);
                documentFrequency.merge(term, 1, Integer::sum);
            });
            documents++;
        }

        /**
         * 每个话题对这条消息的覆盖度：消息里能在话题画像中找到的词项，按 IDF 加权后占消息全部词项 IDF 之和的比例。
         * 用户说过的词算满，只在助手回答里出现过的词按 ASSISTANT_WEIGHT 打折，
         * 助手跑题的回答不会把别的话题的词借给当前话题。
         * 用覆盖度而不用余弦，是因为话题画像会随对话越来越长，余弦会因此偏低；覆盖度只看新消息的信息有多少落在话题里。
         */
        Map<String, Double> similarities(Map<String, Integer> query) {
            Map<String, Double> result = new LinkedHashMap<>();
            if (query.isEmpty()) return result;
            double total = 0.0d;
            for (String term : query.keySet()) total += idf(term);
            for (Map.Entry<String, Map<String, Double>> topic : byTopic.entrySet()) {
                double covered = 0.0d;
                for (String term : query.keySet()) {
                    Double weight = topic.getValue().get(term);
                    if (weight != null) covered += idf(term) * weight;
                }
                result.put(topic.getKey(), total == 0.0d ? 0.0d : covered / total);
            }
            return result;
        }

        /** 平滑的 IDF：没见过的词也有正权重，出现得越频繁权重越低。 */
        private double idf(String term) {
            return Math.log(1.0d + (documents + 1.0d) / (documentFrequency.getOrDefault(term, 0) + 1.0d));
        }
    }

    /** 话题向量和相邻用户消息的间隙。选择话题之后才把当前向量写入滑动平均。 */
    private static final class SemanticState {
        private final Map<String, List<Float>> ema = new LinkedHashMap<>();
        private List<Float> previousUser;
        private double previousGap;
        private double currentAdjacent = 1.0d;
        private boolean hasPreviousUser;
        private boolean hasPreviousGap;

        void noteGap(List<Float> vector) {
            if (!hasPreviousUser || previousUser == null) {
                currentAdjacent = 1.0d;
                return;
            }
            currentAdjacent = cosine(previousUser, vector);
        }

        boolean hasPreviousUser() {
            return hasPreviousUser;
        }

        boolean hasPreviousGap() {
            return hasPreviousGap;
        }

        double currentAdjacent() {
            return currentAdjacent;
        }

        double depthDrop() {
            return previousGap - currentAdjacent;
        }

        Map<String, Double> cosines(List<Float> vector) {
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<Float>> entry : ema.entrySet()) {
                result.put(entry.getKey(), cosine(entry.getValue(), vector));
            }
            return result;
        }

        void commit(String topicId, List<Float> vector) {
            if (hasPreviousUser) {
                previousGap = currentAdjacent;
                hasPreviousGap = true;
            }
            previousUser = List.copyOf(vector);
            hasPreviousUser = true;
            List<Float> current = ema.get(topicId);
            ema.put(topicId, current == null ? List.copyOf(vector) : mix(current, vector));
        }

        private static List<Float> mix(List<Float> previous, List<Float> current) {
            if (previous.size() != current.size()) {
                return List.copyOf(current);
            }
            Float[] mixed = new Float[current.size()];
            for (int index = 0; index < current.size(); index++) {
                mixed[index] = (float) (EMA_ALPHA * current.get(index) + (1.0d - EMA_ALPHA) * previous.get(index));
            }
            return List.of(mixed);
        }
    }

    public record Message(String messageId, int seq, String role, String text, List<Float> vector) {
        public Message(String messageId, int seq, String role, String text) {
            this(messageId, seq, role, text, null);
        }

        public Message {
            vector = vector == null || vector.isEmpty() ? null : List.copyOf(vector);
        }
    }

    public record Segment(String segmentId, String topicId, int startSeq, int endSeq,
                          String status, String reason, String ruleVersion) {}

    public record Projection(List<Segment> segments, List<String> degradationReasons) {
        public Projection(List<Segment> segments) {
            this(segments, List.of());
        }

        public Projection {
            segments = List.copyOf(segments);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }

    private record Choice(String topicId, String reason, String status) {}
}
