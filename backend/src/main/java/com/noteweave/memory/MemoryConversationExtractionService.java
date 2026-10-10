package com.noteweave.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.task.TaskService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 从对话中自动提取长期记忆候选。
 * <p>
 * 用户消息提交时先用规则做初筛，只有带有长期要求措辞（以后、每次、记住等）的消息才会进入异步任务。
 * 任务中优先让大模型把消息整理成结构化的表达偏好，模型不可用时按规则提取。提取结果写成记忆信号，
 * 再交给现有的候选门控：用户明确要求长期遵守的偏好按对话反馈计分，通常可以直接生效；
 * 模型推断出的偏好按模型推断计分，可信度低于门槛，进入待确认。
 * 只提取回答方式上的偏好，资料中的事实不会写入记忆。
 */
@Service
public class MemoryConversationExtractionService {

    static final String TASK_TYPE = "MEMORY_EXTRACTION";
    static final String SOURCE_EXPLICIT = "CONVERSATION_FEEDBACK";
    static final String SOURCE_INFERRED = "MODEL_INFERENCE";
    private static final String SOURCE_REF_PREFIX = "conversation-message:";
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int MAX_STATEMENTS = 3;
    private static final int MAX_STATEMENT_CHARS = 200;
    private static final int EXTRACTION_OUTPUT_TOKENS = 600;

    /** 初筛：带有长期要求措辞的消息才值得提取。 */
    static final Pattern DURABLE_CUE = Pattern.compile(
            "以后|今后|往后|之后都|从现在起|从今以后|每次|每一次|一律|总是|始终|一直都?要|默认(用|使用|给|按)"
                    + "|记住|请记得|不要再|别再|统一(用|使用|称|叫|写)|我(比较|更|很|最|一般|通常)?(喜欢|偏好|习惯)|我倾向于?|我希望你"
                    + "|\\balways\\b|\\bnever\\b|from now on|\\bremember\\b|\\bprefer\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTIVE = Pattern.compile(
            "用|使用|回答|回复|写|给出?|保持|先|不要|别|避免|放在|列出|控制|称|叫|加上|附上|标注|输出|解释"
                    + "|\\buse\\b|\\banswer\\b|\\breply\\b|\\bwrite\\b|\\bkeep\\b|\\bavoid\\b|\\bput\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[。！？!?\\n；;]+");
    private static final Pattern LEADING_CUE = Pattern.compile("^(请)?(记住|请记得)[：:，,\\s]*");
    private static final Set<String> CATEGORIES = Set.of("style", "structure", "terminology", "forbidden", "interaction");

    private static final String SYSTEM_PROMPT = """
            你从用户的一条消息中找出用户希望助手长期遵守的表达偏好。
            只提取关于回答方式的偏好：语言、语气、长度、结构、格式、术语叫法、需要避免的写法、交互方式。
            不要提取事实、资料内容、一次性的任务要求，或只针对当前这个问题的要求。
            用户用喜欢、习惯、倾向、希望等说法表达的回答方式偏好（例如：我比较喜欢用表格对比），即使同一条消息里还提了别的问题，
            也视为长期偏好，explicit 记为 false。
            输出 JSON 数组，每项包含：
            - statement：用一句祈使句复述这条偏好，不超过 60 字，使用用户的语言；
            - category：style、structure、terminology、forbidden、interaction 之一；
            - explicit：用户是否明确要求长期遵守（例如使用了以后、每次、记住、总是等说法），true 或 false。
            最多 3 项。没有符合条件的偏好时输出 []。只输出 JSON，不要解释。""";

    private static final Logger log = LoggerFactory.getLogger(MemoryConversationExtractionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final MemoryCandidatePolicy candidatePolicy;
    private final MemoryCandidateService candidateService;
    private final CanonicalMemoryReviewService reviewService;
    private final MemoryStatementMatcher statementMatcher;
    private final ChatLlmClient llmClient;
    private final TransactionTemplate transactionTemplate;
    private final String topic;
    private final boolean enabled;

    @Autowired
    public MemoryConversationExtractionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskService taskService,
            MemoryCandidatePolicy candidatePolicy,
            MemoryCandidateService candidateService,
            CanonicalMemoryReviewService reviewService,
            MemoryStatementMatcher statementMatcher,
            ChatLlmClient llmClient,
            PlatformTransactionManager transactionManager,
            @Value("${noteweave.kafka.topics.memory-extraction:noteweave.memory.extraction}") String topic,
            @Value("${noteweave.memory.conversation-extraction-enabled:true}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.candidatePolicy = candidatePolicy;
        this.candidateService = candidateService;
        this.reviewService = reviewService;
        this.statementMatcher = statementMatcher;
        this.llmClient = llmClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.enabled = enabled;
    }

    /** 在对话轮次提交的事务里调用：初筛通过时写入提取任务和 outbox 消息，返回是否入队。 */
    public boolean queueForUserMessage(String workspaceId, String conversationId, String messageId,
                                       String actorUserId) {
        // 系统发起的轮次（例如恢复任务）没有对应的用户，不提取个人偏好
        if (!enabled || actorUserId == null || actorUserId.isBlank() || actorUserId.startsWith("SYSTEM:")) {
            return false;
        }
        List<String> contents = jdbcTemplate.queryForList("""
                select content from conversation_message
                where id = ? and workspace_id = ? and conversation_id = ? and role = 'USER'
                """, String.class, messageId, workspaceId, conversationId);
        if (contents.isEmpty() || !shouldExtract(contents.get(0))) return false;
        String content = contents.get(0);
        Integer queued = jdbcTemplate.queryForObject("""
                select count(*) from task where task_type = ? and target_type = 'CONVERSATION_MESSAGE' and target_id = ?
                """, Integer.class, TASK_TYPE, messageId);
        if (queued != null && queued > 0) return false;
        String taskId = taskService.createTask(workspaceId, TASK_TYPE, "CONVERSATION_MESSAGE", messageId,
                "QUEUED", "从对话中提取记忆候选");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("workspace_id", workspaceId);
        payload.put("conversation_id", conversationId);
        payload.put("message_id", messageId);
        payload.put("user_id", actorUserId);
        payload.put("content", bound(content, MAX_MESSAGE_CHARS));
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, Ids.newId(), taskId, topic, messageId, Json.write(objectMapper, payload));
        return true;
    }

    static boolean shouldExtract(String content) {
        return content != null && !content.isBlank() && content.length() <= MAX_MESSAGE_CHARS * 2
                && DURABLE_CUE.matcher(content).find();
    }

    /**
     * Kafka 消费者调用：提取偏好、写入信号并交给候选门控。重复投递时直接返回。
     * 调用大模型不占用数据库事务，写入部分在单独的事务里完成。
     */
    public ExtractionResult extract(String taskId, String workspaceId, String messageId, String userId,
                                    String content) {
        if (alreadyExtracted(workspaceId, messageId)) {
            transactionTemplate.executeWithoutResult(status -> completeTask(taskId, "记忆候选已提取", messageId));
            return new ExtractionResult(List.of(), "DUPLICATE_DELIVERY");
        }
        Extraction extraction = extractPreferences(content);
        return persistExtraction(taskId, workspaceId, messageId, userId, extraction);
    }

    /** 把提取结果写成信号并交给候选门控，在单独的事务里完成。 */
    ExtractionResult persistExtraction(String taskId, String workspaceId, String messageId, String userId,
                                       Extraction extraction) {
        return transactionTemplate.execute(status -> persist(taskId, workspaceId, messageId, userId, extraction));
    }

    private boolean alreadyExtracted(String workspaceId, String messageId) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from memory_signal where workspace_id = ? and source_id like ?
                """, Integer.class, workspaceId, SOURCE_REF_PREFIX + messageId + ":%");
        return existing != null && existing > 0;
    }

    private ExtractionResult persist(String taskId, String workspaceId, String messageId, String userId,
                                     Extraction extraction) {
        if (alreadyExtracted(workspaceId, messageId)) {
            completeTask(taskId, "记忆候选已提取", messageId);
            return new ExtractionResult(List.of(), "DUPLICATE_DELIVERY");
        }
        List<String> known = knownStatements(workspaceId);
        List<ExtractedCandidate> produced = new ArrayList<>();
        int index = 0;
        for (Preference preference : extraction.preferences()) {
            if (known.stream().anyMatch(statement -> statementMatcher.equivalent(statement, preference.statement()))) {
                continue;
            }
            known.add(preference.statement());
            String signalId = insertSignal(workspaceId, userId, messageId, index++, preference);
            MemoryCandidateResponse candidate = candidateService.buildCandidates(workspaceId, List.of(signalId)).get(0);
            reviewService.projectCandidate(candidateService.findCandidate(workspaceId, candidate.candidateId()), userId);
            produced.add(new ExtractedCandidate(candidate.candidateId(), preference.statement(),
                    preference.explicit() ? SOURCE_EXPLICIT : SOURCE_INFERRED, candidate.reviewStatus()));
        }
        completeTask(taskId, produced.isEmpty() ? "没有发现需要长期记住的偏好"
                : "提取到 " + produced.size() + " 条记忆候选", messageId);
        return new ExtractionResult(List.copyOf(produced), extraction.method());
    }

    Extraction extractPreferences(String content) {
        String message = bound(content == null ? "" : content.trim(), MAX_MESSAGE_CHARS);
        if (llmClient != null && llmClient.isEnabled()) {
            try {
                String output = llmClient.streamChat(SYSTEM_PROMPT, "用户消息：\n" + message,
                        EXTRACTION_OUTPUT_TOKENS, token -> { });
                List<Preference> parsed = parse(output);
                if (parsed != null) return new Extraction(parsed, "LLM");
                log.warn("Memory extraction output was not a JSON array; falling back to rules");
            } catch (RuntimeException ex) {
                log.warn("Memory extraction via LLM failed; falling back to rules: {}", ex.getMessage());
            }
        }
        return new Extraction(ruleBased(message), "RULE");
    }

    /** 规则提取：保留带有长期要求措辞和指令动词的句子，统一视为用户明确提出的偏好。 */
    static List<Preference> ruleBased(String message) {
        List<Preference> preferences = new ArrayList<>();
        for (String sentence : SENTENCE_BREAK.split(message)) {
            String trimmed = LEADING_CUE.matcher(sentence.trim()).replaceFirst("").trim();
            if (trimmed.length() < 4 || !DURABLE_CUE.matcher(sentence).find()
                    || !DIRECTIVE.matcher(trimmed).find()) continue;
            preferences.add(new Preference(bound(trimmed, MAX_STATEMENT_CHARS), categorize(trimmed), true));
            if (preferences.size() == MAX_STATEMENTS) break;
        }
        return preferences;
    }

    static String categorize(String statement) {
        String value = statement.toLowerCase(Locale.ROOT);
        if (value.matches(".*(不要|别|避免|禁止|不许|never|avoid|don't|do not).*")) return "forbidden";
        if (value.matches(".*(术语|叫做|称为|统一称|统一叫|译为|写作).*")) return "terminology";
        if (value.matches(".*(结构|先.*再|分点|列表|表格|标题|段落|结论|步骤|bullet|table|heading).*")) return "structure";
        if (value.matches(".*(追问|确认|反问|提问|澄清|ask|confirm).*")) return "interaction";
        return "style";
    }

    private List<Preference> parse(String output) {
        if (output == null) return null;
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end < start) return null;
        try {
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            if (!root.isArray()) return null;
            List<Preference> preferences = new ArrayList<>();
            for (JsonNode node : root) {
                String statement = node.path("statement").asText("").trim();
                if (statement.isBlank()) continue;
                String category = node.path("category").asText("style").trim().toLowerCase(Locale.ROOT);
                preferences.add(new Preference(bound(statement, MAX_STATEMENT_CHARS),
                        CATEGORIES.contains(category) ? category : "style",
                        node.path("explicit").asBoolean(false)));
                if (preferences.size() == MAX_STATEMENTS) break;
            }
            return preferences;
        } catch (Exception ex) {
            return null;
        }
    }

    private String insertSignal(String workspaceId, String userId, String messageId, int index,
                                Preference preference) {
        String sourceType = preference.explicit() ? SOURCE_EXPLICIT : SOURCE_INFERRED;
        List<String> statement = List.of(preference.statement());
        MemorySignalService.MemoryCompileHints hints = new MemorySignalService.MemoryCompileHints(
                "style".equals(preference.category()) ? statement : List.of(),
                "structure".equals(preference.category()) ? statement : List.of(),
                "terminology".equals(preference.category()) ? statement : List.of(),
                "forbidden".equals(preference.category()) ? statement : List.of(),
                "interaction".equals(preference.category()) ? statement : List.of(),
                List.of());
        String signalId = Ids.newId();
        jdbcTemplate.update("""
                insert into memory_signal(
                    id, workspace_id, user_id, source_type, source_id, signal_type, signal_text,
                    task_neighborhood, compile_hints_json, confidence_score, policy_version
                ) values (?, ?, ?, ?, ?, 'PREFERENCE', ?, 'COMMON', ?, ?, ?)
                """, signalId, workspaceId, userId, sourceType, SOURCE_REF_PREFIX + messageId + ":" + index,
                preference.statement(), Json.write(objectMapper, hints),
                candidatePolicy.confidenceForSource(sourceType), candidatePolicy.version());
        return signalId;
    }

    /** 已生效或待确认的记忆文本，用来跳过重复的偏好。 */
    private List<String> knownStatements(String workspaceId) {
        return new ArrayList<>(jdbcTemplate.queryForList("""
                select r.display_text from memory_item i
                join memory_runtime_revision r on r.memory_item_id = i.id
                where i.workspace_id = ? and i.status <> 'DELETED' and r.status in ('ACTIVE', 'PROPOSED')
                """, String.class, workspaceId));
    }

    private void completeTask(String taskId, String message, String resultRef) {
        if (taskId == null || taskId.isBlank()) return;
        String status = jdbcTemplate.query("select task_status from task where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (status == null || !Set.of("PENDING", "RUNNING", "WAITING").contains(status)) return;
        if ("PENDING".equals(status)) taskService.startTask(taskId);
        taskService.completeTask(taskId, "COMPLETED", message, resultRef);
    }

    private static String bound(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    record Preference(String statement, String category, boolean explicit) { }

    record Extraction(List<Preference> preferences, String method) { }

    public record ExtractedCandidate(String candidateId, String statement, String sourceType, String reviewStatus) { }

    public record ExtractionResult(List<ExtractedCandidate> candidates, String method) { }
}
