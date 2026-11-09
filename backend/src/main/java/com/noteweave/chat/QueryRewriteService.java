package com.noteweave.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.QueryRewrite;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 有对话上下文时，把当前问题改写成可独立检索的查询。
 * 首轮问题不调用模型。模型不可用或输出不合规时退回规则，并记录降级原因。
 */
@Service
public class QueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final int OUTPUT_TOKENS = 800;
    private static final int MAX_QUERY_CHARS = 400;
    private static final int MAX_ALTERNATIVES = 3;
    private static final int MAX_TERMS = 8;
    private static final int MAX_TERM_CHARS = 40;
    private static final Set<String> QUESTION_TYPES = Set.of(
            "comparison", "summary", "source_lookup", "reasoning", "definition");

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。只输出一个 JSON 对象，不要输出解释或 Markdown。
            规则：
            1. 先判断当前问题离开对话后是否已经能够独立检索。能够独立时 is_standalone 为 true，standalone_query 与 keyword_query 必须等于当前问题原文，不要添加原文里没有的词。
            2. 不能独立时尽量少改，只补上指代所缺的主题，不要编造对话里没有的事实，也不要加入新的限定。
            3. standalone_query 是一句完整的自然语言问题，供向量检索和重排使用。
            4. keyword_query 是去掉疑问套话后的关键词查询。
            5. alternatives 最多 3 条不同表述，供召回不足时追加检索；没有把握时给空数组。
            6. question_type 只能是 comparison、summary、source_lookup、reasoning、definition 之一。
            7. must_terms 是需要加重的专名或标识符。low_level_keywords 是具体实体，high_level_keywords 是主题词。
            8. language 只能是 zh 或 en。
            使用短字段名输出，含义和上述规则完全相同：s=is_standalone, q=standalone_query, k=keyword_query,
            t=question_type, m=must_terms, l=low_level_keywords, h=high_level_keywords, lang=language, a=alternatives。
            按 s、q、k、t、m、l、h、lang、a 的顺序输出，a 放在最后。
            输出紧凑 JSON，不缩减查询或遗漏指代、否定、比较对象与版本等限定。
            """;
    /** 流式输出里 alternatives 键的开始位置；它之前的字段已经足够发起检索。 */
    private static final java.util.regex.Pattern ALTERNATIVES_KEY =
            java.util.regex.Pattern.compile(",\\s*\"(?:a|alternatives)\"\\s*:");

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ConversationRetrievalContextAssembler contextAssembler;
    private final boolean enabled;
    /** 追问本身已经能独立检索时跳过模型改写，见 {@link #looksStandalone}。 */
    private final boolean skipStandalone;

    /** 判定为可独立检索的最短长度（按字符计）；更短的追问大多省略了主语。 */
    static final int STANDALONE_MIN_CHARS = 12;
    /** 出现这些词就认为问题依赖上文，仍交给模型改写。宁可多改写，也不要漏掉指代。 */
    private static final List<String> CONTEXT_DEPENDENT_HINTS = List.of(
            "这", "那", "它", "他", "她", "其", "该", "此", "上述", "以上", "前面", "上面", "之前", "刚才",
            "上一", "继续", "接着", "展开", "再", "还有", "呢", "同样", "对应", "相比", "比较", "对比",
            "区别", "差异", "哪个", "前者", "后者", "第一", "第二", "第三", "具体", "详细", "说说", "讲讲",
            "举例", "例子", "你说", "你提", "刚刚", "另外", "其他", "其余", "怎么样", "如何呢",
            " it ", " this", " that", " they", " them", " these", " those", "above", "previous", "more");

    public QueryRewriteService(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            ConversationRetrievalContextAssembler contextAssembler,
            boolean enabled
    ) {
        this(llmClient, objectMapper, contextAssembler, enabled, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QueryRewriteService(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            ConversationRetrievalContextAssembler contextAssembler,
            @Value("${noteweave.retrieval.query-rewrite.enabled:true}") boolean enabled,
            @Value("${noteweave.retrieval.query-rewrite.skip-standalone:true}") boolean skipStandalone
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
        this.enabled = enabled;
        this.skipStandalone = skipStandalone;
    }

    /**
     * 足够长、且不含任何指代或承接词的问题按可独立检索处理。规则只做保守的放行：
     * 判断错了的代价是检索只用原句，回答提示里仍有对话上下文。
     */
    static boolean looksStandalone(String question) {
        if (question == null) {
            return false;
        }
        String trimmed = question.trim();
        if (trimmed.codePointCount(0, trimmed.length()) < STANDALONE_MIN_CHARS) {
            return false;
        }
        String padded = " " + trimmed.toLowerCase(java.util.Locale.ROOT) + " ";
        for (String hint : CONTEXT_DEPENDENT_HINTS) {
            if (padded.contains(hint)) {
                return false;
            }
        }
        return true;
    }

    public QueryRewrite rewrite(ConversationRetrievalContextAssembler.Context context) {
        String current = context.currentQuestion() == null ? "" : context.currentQuestion().trim();
        if (!context.contextApplied()) {
            return firstTurn(current);
        }
        if (!enabled) {
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_DISABLED"));
        }
        if (skipStandalone && looksStandalone(current)) {
            // 与首轮相同：检索直接用原句，省掉一次模型调用（实测约 2.5 秒）
            QueryRewrite standalone = firstTurn(current);
            return new QueryRewrite(standalone.standalone(), standalone.standaloneQuery(),
                    standalone.keywordQuery(), standalone.alternatives(), standalone.questionType(),
                    standalone.mustTerms(), standalone.lowLevelKeywords(), standalone.highLevelKeywords(),
                    standalone.language(), "RULE_STANDALONE", List.of());
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_LLM_UNAVAILABLE"));
        }
        return llmRewrite(current, context, null);
    }

    /** 本轮是否会调用模型改写；只有这种情况才值得边输出边发起检索。 */
    boolean usesModel(ConversationRetrievalContextAssembler.Context context) {
        String current = context.currentQuestion() == null ? "" : context.currentQuestion().trim();
        return context.contextApplied() && enabled
                && !(skipStandalone && looksStandalone(current))
                && llmClient != null && llmClient.isEnabled();
    }

    /**
     * 渐进改写：模型输出到 alternatives 之前的字段都已完整时，先交出不含 alternatives 的结果供检索；
     * 完整结果在后台继续生成。调用方检索结束后用 {@link ProgressiveRewrite#awaitComplete()} 取完整结果，
     * 两者除 alternatives 外不一致、或召回不足需要 alternatives 时，必须用完整结果重新检索。
     * 线程池为空、被拒绝或本轮不调用模型时退回同步改写，结果与 {@link #rewrite} 完全相同。
     */
    public ProgressiveRewrite rewriteProgressive(
            ConversationRetrievalContextAssembler.Context context,
            java.util.concurrent.Executor executor
    ) {
        if (executor == null || !usesModel(context)) {
            return ProgressiveRewrite.complete(rewrite(context));
        }
        String current = context.currentQuestion() == null ? "" : context.currentQuestion().trim();
        java.util.concurrent.CompletableFuture<QueryRewrite> early = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<QueryRewrite> full;
        try {
            full = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> llmRewrite(current, context, draft -> early.complete(accept(current, draft))), executor);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return ProgressiveRewrite.complete(rewrite(context));
        }
        return new ProgressiveRewrite(early, full, () -> rule(current, context.topicAnchor(),
                List.of("QUERY_REWRITE_LLM_CALL_FAILED")));
    }

    /** 两次改写除 alternatives 外是否完全相同；相同则早先发起的检索与完整结果的检索输入一致。 */
    public static boolean sameExceptAlternatives(QueryRewrite left, QueryRewrite right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.standalone() == right.standalone()
                && java.util.Objects.equals(left.standaloneQuery(), right.standaloneQuery())
                && java.util.Objects.equals(left.keywordQuery(), right.keywordQuery())
                && java.util.Objects.equals(left.questionType(), right.questionType())
                && java.util.Objects.equals(left.mustTerms(), right.mustTerms())
                && java.util.Objects.equals(left.lowLevelKeywords(), right.lowLevelKeywords())
                && java.util.Objects.equals(left.highLevelKeywords(), right.highLevelKeywords())
                && java.util.Objects.equals(left.language(), right.language())
                && java.util.Objects.equals(left.method(), right.method())
                && java.util.Objects.equals(left.degradationReasons(), right.degradationReasons());
    }

    /** 渐进改写的两个阶段。 */
    public static final class ProgressiveRewrite {
        private final java.util.concurrent.CompletableFuture<QueryRewrite> early;
        private final java.util.concurrent.CompletableFuture<QueryRewrite> full;
        private final java.util.function.Supplier<QueryRewrite> failure;
        private QueryRewrite dispatched;

        private ProgressiveRewrite(
                java.util.concurrent.CompletableFuture<QueryRewrite> early,
                java.util.concurrent.CompletableFuture<QueryRewrite> full,
                java.util.function.Supplier<QueryRewrite> failure
        ) {
            this.early = early;
            this.full = full;
            this.failure = failure;
        }

        static ProgressiveRewrite complete(QueryRewrite rewrite) {
            var done = java.util.concurrent.CompletableFuture.completedFuture(rewrite);
            return new ProgressiveRewrite(done, done, () -> rewrite);
        }

        /** 等到可以发起检索：早先结果或完整结果，谁先到用谁。 */
        public QueryRewrite awaitDispatchable() {
            try {
                Object first = java.util.concurrent.CompletableFuture.anyOf(early, full).get();
                dispatched = (QueryRewrite) first;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                dispatched = failure.get();
            } catch (java.util.concurrent.ExecutionException ex) {
                dispatched = failure.get();
            }
            return dispatched;
        }

        /** 交给检索的是不是不含 alternatives 的早先结果。 */
        public boolean dispatchedPartial() {
            return early != full && dispatched != null && early.isDone() && early.getNow(null) == dispatched;
        }

        /** 完整改写结果；后台失败时退回规则改写。 */
        public QueryRewrite awaitComplete() {
            try {
                return full.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return failure.get();
            } catch (java.util.concurrent.ExecutionException ex) {
                return failure.get();
            }
        }
    }

    private QueryRewrite llmRewrite(
            String current,
            ConversationRetrievalContextAssembler.Context context,
            java.util.function.Consumer<Draft> onEarlyDraft
    ) {
        String prompt = userPrompt(current, context);
        try {
            StringBuilder streamed = new StringBuilder();
            boolean[] earlyTried = {onEarlyDraft == null};
            String first = llmClient.streamChat(SYSTEM_PROMPT, prompt, OUTPUT_TOKENS, token -> {
                if (earlyTried[0] || token == null) {
                    return;
                }
                streamed.append(token);
                java.util.regex.Matcher marker = ALTERNATIVES_KEY.matcher(streamed);
                if (!marker.find()) {
                    return;
                }
                // alternatives 之前的字段已经完整：补上右括号按同一套规则校验，通过才提前交出
                earlyTried[0] = true;
                Parsed prefix = parse(streamed.substring(0, marker.start()) + "}");
                if (prefix.draft() != null && prefix.draft().alternatives().isEmpty()) {
                    try {
                        onEarlyDraft.accept(prefix.draft());
                    } catch (RuntimeException ex) {
                        log.debug("提前交出改写结果失败：{}", ex.getMessage());
                    }
                }
            });
            Parsed parsed = parse(first);
            if (parsed.draft() != null) {
                return accept(current, parsed.draft());
            }
            String retryPrompt = prompt
                    + "\n\n上一次输出没有通过结构校验：" + parsed.error()
                    + "\n请只输出修正后的 JSON 对象。";
            String second = llmClient.streamChat(SYSTEM_PROMPT, retryPrompt, OUTPUT_TOKENS, token -> { });
            parsed = parse(second);
            if (parsed.draft() != null) {
                return accept(current, parsed.draft());
            }
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_OUTPUT_INVALID"));
        } catch (RuntimeException ex) {
            log.warn("查询改写调用失败，退回规则：{}", ex.getMessage());
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_LLM_CALL_FAILED"));
        }
    }

    private QueryRewrite firstTurn(String current) {
        return new QueryRewrite(
                true,
                current,
                current,
                List.of(),
                classify(current),
                List.of(),
                List.of(),
                List.of(),
                language(current),
                "RULE",
                List.of()
        );
    }

    private QueryRewrite rule(String current, String topicAnchor, List<String> reasons) {
        String anchor = topicAnchor == null ? "" : topicAnchor.trim();
        String keyword = current;
        if (!anchor.isBlank() && !current.contains(anchor)) {
            keyword = (current + " " + anchor).trim();
        }
        return new QueryRewrite(
                false,
                bound(current, MAX_QUERY_CHARS),
                bound(keyword, MAX_QUERY_CHARS),
                List.of(),
                classify(current),
                anchorKeywords(anchor),
                List.of(),
                List.of(),
                language(current),
                "RULE",
                reasons
        );
    }

    private QueryRewrite accept(String current, Draft draft) {
        String vector = draft.standalone() ? current : draft.standaloneQuery();
        String keyword;
        if (draft.standalone()) {
            keyword = current;
        } else if (draft.keywordQuery().contains(current)) {
            keyword = draft.keywordQuery();
        } else {
            keyword = (current + " " + draft.keywordQuery()).trim();
        }
        return new QueryRewrite(
                draft.standalone(),
                bound(vector, MAX_QUERY_CHARS),
                bound(keyword, MAX_QUERY_CHARS),
                draft.alternatives(),
                draft.questionType(),
                draft.mustTerms(),
                draft.lowLevelKeywords(),
                draft.highLevelKeywords(),
                draft.language(),
                "LLM",
                List.of()
        );
    }

    private String userPrompt(String current, ConversationRetrievalContextAssembler.Context context) {
        return "当前问题：\n" + current
                + "\n\n话题锚点：\n" + text(context.topicAnchor())
                + "\n\n话题摘要：\n" + text(context.topicSummary())
                + "\n\n最近原文窗口：\n" + text(context.retrievalQuestion());
    }

    private Parsed parse(String output) {
        if (output == null || output.isBlank()) {
            return Parsed.invalid("输出为空");
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Parsed.invalid("没有找到 JSON 对象");
        }
        try {
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            if (!root.isObject()) {
                return Parsed.invalid("根节点不是 JSON 对象");
            }
            // 紧凑输出减少模型生成的结构字符；下游契约与旧字段保持兼容。
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            var aliases = java.util.Map.of("s", "is_standalone", "q", "standalone_query", "k", "keyword_query",
                    "a", "alternatives", "t", "question_type", "m", "must_terms", "l", "low_level_keywords",
                    "h", "high_level_keywords", "lang", "language");
            for (var alias : aliases.entrySet()) {
                JsonNode compact = root.get(alias.getKey());
                if (compact == null) continue;
                JsonNode legacy = root.get(alias.getValue());
                if (legacy != null && !legacy.equals(compact)) return Parsed.invalid("长短字段值冲突");
                object.set(alias.getValue(), compact);
            }
            if (!root.path("is_standalone").isBoolean()) {
                return Parsed.invalid("is_standalone 必须是布尔值");
            }
            boolean standalone = root.path("is_standalone").asBoolean();
            String standaloneQuery = root.path("standalone_query").asText("").trim();
            String keywordQuery = root.path("keyword_query").asText("").trim();
            if (!standalone && (standaloneQuery.isBlank() || keywordQuery.isBlank())) {
                return Parsed.invalid("不能独立检索时 standalone_query 和 keyword_query 都不能为空");
            }
            String questionType = root.path("question_type").asText("").trim();
            if (!QUESTION_TYPES.contains(questionType)) {
                return Parsed.invalid("question_type 不在允许的取值内");
            }
            String language = root.path("language").asText("").trim();
            if (!"zh".equals(language) && !"en".equals(language)) {
                return Parsed.invalid("language 只能是 zh 或 en");
            }
            List<String> alternatives = stringList(root.path("alternatives"), "alternatives");
            List<String> mustTerms = stringList(root.path("must_terms"), "must_terms");
            List<String> lowLevel = stringList(root.path("low_level_keywords"), "low_level_keywords");
            List<String> highLevel = stringList(root.path("high_level_keywords"), "high_level_keywords");
            if (alternatives.size() > MAX_ALTERNATIVES) {
                alternatives = alternatives.subList(0, MAX_ALTERNATIVES);
            }
            return Parsed.ok(new Draft(
                    standalone,
                    bound(standaloneQuery, MAX_QUERY_CHARS),
                    bound(keywordQuery, MAX_QUERY_CHARS),
                    List.copyOf(alternatives),
                    questionType,
                    boundTerms(mustTerms),
                    boundTerms(lowLevel),
                    boundTerms(highLevel),
                    language
            ));
        } catch (IllegalArgumentException ex) {
            return Parsed.invalid(ex.getMessage());
        } catch (Exception ex) {
            return Parsed.invalid("JSON 无法解析");
        }
    }

    private List<String> stringList(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " 必须是字符串数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(field + " 的元素必须是字符串");
            }
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private List<String> boundTerms(List<String> terms) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String term : terms) {
            if (term.length() > MAX_TERM_CHARS) {
                continue;
            }
            unique.add(term);
            if (unique.size() == MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private List<String> anchorKeywords(String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return List.of();
        }
        String normalized = anchor.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 2 || terms.size() == MAX_TERMS) {
                continue;
            }
            terms.add(token);
        }
        return List.copyOf(terms);
    }

    private String classify(String question) {
        return contextAssembler.classifyQuestion(question);
    }

    private String language(String question) {
        if (question != null && question.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            return "zh";
        }
        return "en";
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }

    private String bound(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record Draft(
            boolean standalone,
            String standaloneQuery,
            String keywordQuery,
            List<String> alternatives,
            String questionType,
            List<String> mustTerms,
            List<String> lowLevelKeywords,
            List<String> highLevelKeywords,
            String language
    ) {
    }

    private record Parsed(Draft draft, String error) {
        static Parsed ok(Draft draft) {
            return new Parsed(draft, "");
        }

        static Parsed invalid(String error) {
            return new Parsed(null, error);
        }
    }
}
