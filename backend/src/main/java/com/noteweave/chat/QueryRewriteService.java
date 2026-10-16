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
            字段：is_standalone, standalone_query, keyword_query, alternatives, question_type, must_terms, low_level_keywords, high_level_keywords, language。
            """;

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ConversationRetrievalContextAssembler contextAssembler;
    private final boolean enabled;

    public QueryRewriteService(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            ConversationRetrievalContextAssembler contextAssembler,
            @Value("${noteweave.retrieval.query-rewrite.enabled:true}") boolean enabled
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
        this.enabled = enabled;
    }

    public QueryRewrite rewrite(ConversationRetrievalContextAssembler.Context context) {
        String current = context.currentQuestion() == null ? "" : context.currentQuestion().trim();
        if (!context.contextApplied()) {
            return firstTurn(current);
        }
        if (!enabled) {
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_DISABLED"));
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return rule(current, context.topicAnchor(), List.of("QUERY_REWRITE_LLM_UNAVAILABLE"));
        }
        String prompt = userPrompt(current, context);
        try {
            String first = llmClient.streamChat(SYSTEM_PROMPT, prompt, OUTPUT_TOKENS, token -> { });
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
