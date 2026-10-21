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
 * 用户消息在长度范围内都会进入异步任务。任务里先做一次短分类，只有包含回答方式要求或反馈的消息才完整抽取。
 * 一条偏好是否算显式记忆（explicit）、是否算对上一条回答的负面反馈（feedback），只由模型输出字段决定，
 * 不再有关键词正则兜底：模型调用失败、超时或输出不是合法 JSON 时重试一次，仍失败则本轮不抽取、记日志，
 * 并把任务标成可重试，等既有的重驱入口再跑一次。
 * 与已有记忆等价的信号不再新建一条，而是按会话累加证据。
 * 只提取回答方式上的偏好，资料中的事实不会写入记忆。
 */
@Service
public class MemoryConversationExtractionService {

    static final String TASK_TYPE = "MEMORY_EXTRACTION";
    static final String SOURCE_EXPLICIT = "CONVERSATION_FEEDBACK";
    static final String SOURCE_INFERRED = "MODEL_INFERENCE";
    private static final String SOURCE_REF_PREFIX = "conversation-message:";
    /** 入队 payload 保留的消息上限，与 {@link #shouldExtract(String)} 的上限一致。 */
    static final int MAX_QUEUED_MESSAGE_CHARS = 4_000;
    /**
     * 拼接提示词时的消息上限，与入队上限一致：入队保留的偏好不能在这里又被裁掉，
     * 否则写在长消息后半段的长期偏好永远不会进入候选门控。
     */
    private static final int MAX_PROMPT_MESSAGE_CHARS = MAX_QUEUED_MESSAGE_CHARS;
    private static final int MAX_STATEMENTS = 3;
    private static final int MAX_STATEMENT_CHARS = 200;
    private static final int EXTRACTION_OUTPUT_TOKENS = 900;
    /** 模型抽取的最多尝试次数：首次失败后只重试一次，仍失败就放弃本轮。 */
    private static final int MAX_LLM_ATTEMPTS = 2;
    private static final String RETRY_SUFFIX = "\n上次输出不是合法的 JSON 数组，只输出 JSON 数组，不要解释。";

    static final String METHOD_LLM = "LLM";
    static final String METHOD_CLASSIFIED_NO = "CLASSIFIED_NO";
    static final String METHOD_LLM_UNAVAILABLE = "LLM_UNAVAILABLE";
    static final String METHOD_LLM_FAILED = "LLM_FAILED";
    static final String RETRY_PHASE = "MEMORY_EXTRACTION_LLM_UNAVAILABLE";
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
            - explicit：用户是否明确要求长期遵守（例如使用了以后、每次、记住、总是等说法），true 或 false；
            - slot：槽位，形如 answer.language、answer.length、answer.style、answer.constraint、format.structure、format.table、terminology.term、interaction.style，无法归类时留空；
            - value：槽位取值，不超过 40 字；
            - polarity：REQUIRE 或 FORBID，用户要求避免或禁止时用 FORBID，否则 REQUIRE；
            - feedback：用户是在评价已有回答（例如太长了、看不懂、别用英文）时为 true，否则 false。
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
    private final MemoryEvidenceService evidenceService;
    private final MemoryWordingJudge wordingJudge;
    private final TransactionTemplate transactionTemplate;
    private final String topic;
    private final boolean enabled;
    private MemoryApplicationFeedbackService applicationFeedback;

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
            MemoryEvidenceService evidenceService,
            MemoryWordingJudge wordingJudge,
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
        this.evidenceService = evidenceService;
        this.wordingJudge = wordingJudge;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.enabled = enabled;
    }

    @Autowired(required = false)
    void setApplicationFeedback(MemoryApplicationFeedbackService applicationFeedback) {
        this.applicationFeedback = applicationFeedback;
    }

    /** 在对话轮次提交的事务里调用：消息长度合适时写入提取任务和 outbox 消息，返回是否入队。 */
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
        payload.put("content", queuedContent(content));
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, Ids.newId(), taskId, topic, messageId, Json.write(objectMapper, payload));
        return true;
    }

    static boolean shouldExtract(String content) {
        return content != null && !content.isBlank() && content.length() <= MAX_QUEUED_MESSAGE_CHARS;
    }

    /** 入队 payload 里的消息内容：完整保留到 {@link #MAX_QUEUED_MESSAGE_CHARS}。 */
    static String queuedContent(String content) {
        return content == null ? "" : bound(content, MAX_QUEUED_MESSAGE_CHARS);
    }

    /** 拼接提示词时使用的消息内容：按 {@link #MAX_PROMPT_MESSAGE_CHARS} 截断。 */
    static String promptContent(String content) {
        return content == null ? "" : bound(content.trim(), MAX_PROMPT_MESSAGE_CHARS);
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
        Extraction extraction = extractPreferences(content, previousAssistant(workspaceId, messageId));
        return persistExtraction(taskId, workspaceId, messageId, userId, extraction, content);
    }

    /** 把提取结果写成信号并交给候选门控，在单独的事务里完成。 */
    ExtractionResult persistExtraction(String taskId, String workspaceId, String messageId, String userId,
                                       Extraction extraction) {
        return persistExtraction(taskId, workspaceId, messageId, userId, extraction, null);
    }

    ExtractionResult persistExtraction(String taskId, String workspaceId, String messageId, String userId,
                                       Extraction extraction, String userContent) {
        return transactionTemplate.execute(status -> persist(
                taskId, workspaceId, messageId, userId, extraction, userContent));
    }

    private boolean alreadyExtracted(String workspaceId, String messageId) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from memory_signal where workspace_id = ? and source_id like ?
                """, Integer.class, workspaceId, SOURCE_REF_PREFIX + messageId + ":%");
        return existing != null && existing > 0;
    }

    private ExtractionResult persist(String taskId, String workspaceId, String messageId, String userId,
                                     Extraction extraction, String userContent) {
        if (alreadyExtracted(workspaceId, messageId)) {
            completeTask(taskId, "记忆候选已提取", messageId);
            return new ExtractionResult(List.of(), "DUPLICATE_DELIVERY");
        }
        List<KnownMemory> known = knownMemories(workspaceId);
        String sessionKey = sessionKey(workspaceId, messageId);
        List<ExtractedCandidate> produced = new ArrayList<>();
        int index = 0;
        for (Preference preference : extraction.preferences()) {
            MemorySlots.Assignment assignment = preference.assignment();
            KnownMemory equivalent = equivalentOf(known, preference, assignment);
            MemoryEvidence.Band band = evidenceBand(preference);
            if (equivalent != null) {
                evidenceService.observe(equivalent.id(), sessionKey, "SUPPORT", band);
                if (!equivalent.text().equals(preference.statement())
                        && wordingJudge.moreSpecific(equivalent.text(), preference.statement())) {
                    reviewService.reviseEquivalentWording(workspaceId, equivalent.id(), preference.statement());
                }
                continue;
            }
            KnownMemory contradicting = contradictingOf(known, assignment);
            if (contradicting != null) {
                evidenceService.observe(contradicting.id(), sessionKey, "CONTRADICT", band);
            }
            String signalId = insertSignal(workspaceId, userId, messageId, index++, preference);
            MemoryCandidateResponse candidate = candidateService.buildCandidates(workspaceId, List.of(signalId)).get(0);
            reviewService.projectCandidate(candidateService.findCandidate(workspaceId, candidate.candidateId()), userId);
            evidenceService.recordOrigin(candidate.candidateId(), sessionKey);
            known.add(new KnownMemory(candidate.candidateId(), preference.statement(),
                    assignment.slot(), assignment.value(), assignment.polarity()));
            produced.add(new ExtractedCandidate(candidate.candidateId(), preference.statement(),
                    sourceType(preference), candidate.reviewStatus()));
        }
        // 模型没给出结论时判不了“这一轮是不是不满”，不写跟进反馈，避免把沉默记成接受
        if (!extraction.retryable() && applicationFeedback != null) {
            applicationFeedback.recordFollowUp(workspaceId, messageId, userContent, dissatisfied(extraction));
        }
        if (extraction.retryable()) {
            markTaskForRetry(taskId);
        } else {
            completeTask(taskId, produced.isEmpty() ? "没有发现需要长期记住的偏好"
                    : "提取到 " + produced.size() + " 条记忆候选", messageId);
        }
        return new ExtractionResult(List.copyOf(produced), extraction.method());
    }

    /**
     * 只由模型决定抽取结果。分类器判 NO、或模型给出空数组，都视为“这条消息没有偏好”，不再回落规则；
     * 调用失败或输出不是合法 JSON 时重试一次，仍失败则本轮不抽取，由 {@link Extraction#retryable()} 标出待重抽。
     */
    Extraction extractPreferences(String content, String previousAnswer) {
        String message = promptContent(content);
        if (llmClient == null || !llmClient.isEnabled()) {
            log.warn("Memory extraction skipped: LLM client is not configured");
            return new Extraction(List.of(), METHOD_LLM_UNAVAILABLE, false);
        }
        String decision = MemoryExtractionClassifier.decide(llmClient, message);
        if ("NO".equals(decision)) {
            return new Extraction(List.of(), METHOD_CLASSIFIED_NO, false);
        }
        if (!"YES".equals(decision)) {
            log.warn("Memory extraction skipped: classifier is unavailable");
            return new Extraction(List.of(), METHOD_LLM_UNAVAILABLE, false);
        }
        String prompt = extractionPrompt(message, previousAnswer);
        for (int attempt = 0; attempt < MAX_LLM_ATTEMPTS; attempt++) {
            try {
                String output = llmClient.streamChat(SYSTEM_PROMPT, prompt, EXTRACTION_OUTPUT_TOKENS, token -> { });
                List<Preference> parsed = parse(output);
                // 空数组是模型的终局回答（“没有符合条件的偏好”），不是失败
                if (parsed != null) return new Extraction(parsed, METHOD_LLM, false);
                log.warn("Memory extraction output was not a JSON array (attempt {}/{}); retrying",
                        attempt + 1, MAX_LLM_ATTEMPTS);
            } catch (RuntimeException ex) {
                log.warn("Memory extraction call failed (attempt {}/{}): {}",
                        attempt + 1, MAX_LLM_ATTEMPTS, ex.getMessage());
            }
            prompt = prompt + RETRY_SUFFIX;
        }
        log.warn("Memory extraction gave up after {} attempts; no preference is extracted for this round",
                MAX_LLM_ATTEMPTS);
        return new Extraction(List.of(), METHOD_LLM_FAILED, true);
    }

    /**
     * 是否算作对上一条回答的负面反馈：只看模型有没有把抽到的偏好标成对已有回答的评价（feedback 字段）。
     * 不再用关键词判断，避免把「变量名太长」这类描述记成对回答的不满。
     */
    static boolean dissatisfied(Extraction extraction) {
        return extraction.preferences().stream().anyMatch(Preference::feedback);
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
                        node.path("explicit").asBoolean(false),
                        node.path("slot").asText(null),
                        node.path("value").asText(null),
                        node.path("polarity").asText(null),
                        node.path("feedback").asBoolean(false)));
                if (preferences.size() == MAX_STATEMENTS) break;
            }
            return preferences;
        } catch (Exception ex) {
            return null;
        }
    }

    private String insertSignal(String workspaceId, String userId, String messageId, int index,
                                Preference preference) {
        String sourceType = sourceType(preference);
        MemoryEvidence.Band band = evidenceBand(preference);
        List<String> statement = List.of(preference.statement());
        MemorySignalService.MemoryCompileHints hints = new MemorySignalService.MemoryCompileHints(
                "style".equals(preference.category()) ? statement : List.of(),
                "structure".equals(preference.category()) ? statement : List.of(),
                "terminology".equals(preference.category()) ? statement : List.of(),
                "forbidden".equals(preference.category()) ? statement : List.of(),
                "interaction".equals(preference.category()) ? statement : List.of(),
                List.of());
        MemorySlots.Assignment assignment = preference.assignment();
        String signalId = Ids.newId();
        jdbcTemplate.update("""
                insert into memory_signal(
                    id, workspace_id, user_id, source_type, source_id, signal_type, signal_text,
                    task_neighborhood, compile_hints_json, confidence_score, policy_version,
                    slot_key, slot_value, polarity, evidence_band
                ) values (?, ?, ?, ?, ?, 'PREFERENCE', ?, 'COMMON', ?, ?, ?, ?, ?, ?, ?)
                """, signalId, workspaceId, userId, sourceType, SOURCE_REF_PREFIX + messageId + ":" + index,
                preference.statement(), Json.write(objectMapper, hints),
                MemoryEvidence.prior(band).mean(), candidatePolicy.version(),
                assignment.slot(), assignment.value(), assignment.polarity(), band.name());
        return signalId;
    }

    private List<KnownMemory> knownMemories(String workspaceId) {
        return new ArrayList<>(jdbcTemplate.query("""
                select i.id, r.display_text, i.semantic_slot_key, r.normalized_value_json
                from memory_item i
                join memory_runtime_revision r on r.memory_item_id = i.id
                where i.workspace_id = ? and i.status not in ('DELETED', 'SUPERSEDED')
                  and r.status in ('ACTIVE', 'PROPOSED')
                  and (r.status = 'PROPOSED' or r.id = i.current_revision_id)
                """, (rs, rowNum) -> {
            JsonNode payload = readJson(rs.getString("normalized_value_json"));
            String slot = MemorySlots.publicSlot(rs.getString("semantic_slot_key"));
            if ("unknown".equals(slot)) {
                slot = MemorySlots.publicSlot(text(payload, "slot"));
            }
            return new KnownMemory(rs.getString("id"), rs.getString("display_text"), slot,
                    text(payload, "slot_value"), text(payload, "polarity"));
        }, workspaceId));
    }

    private KnownMemory equivalentOf(List<KnownMemory> known, Preference preference, MemorySlots.Assignment assignment) {
        for (KnownMemory item : known) {
            boolean sameSlot = !"unknown".equals(assignment.slot()) && assignment.slot().equals(item.slot());
            boolean sameValue = assignment.value().equalsIgnoreCase(item.value());
            boolean samePolarity = assignment.polarity().equals(item.polarity());
            if (statementMatcher.equivalent(item.text(), preference.statement())
                    || (sameSlot && sameValue && samePolarity)) {
                return item;
            }
        }
        return null;
    }

    private KnownMemory contradictingOf(List<KnownMemory> known, MemorySlots.Assignment assignment) {
        if ("unknown".equals(assignment.slot())) {
            return null;
        }
        for (KnownMemory item : known) {
            if (!assignment.slot().equals(item.slot()) || item.polarity().isBlank()) {
                continue;
            }
            if (!assignment.value().equalsIgnoreCase(item.value()) || !assignment.polarity().equals(item.polarity())) {
                return item;
            }
        }
        return null;
    }

    private static String sourceType(Preference preference) {
        return preference.explicit() || preference.feedback() ? SOURCE_EXPLICIT : SOURCE_INFERRED;
    }

    private static MemoryEvidence.Band evidenceBand(Preference preference) {
        return MemoryEvidence.bandOf(sourceType(preference), preference.feedback() && !preference.explicit());
    }

    private String sessionKey(String workspaceId, String messageId) {
        String conversationId = jdbcTemplate.query("""
                select conversation_id from conversation_message where id = ? and workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, messageId, workspaceId);
        return conversationId == null || conversationId.isBlank()
                ? "message:" + messageId : "conversation:" + conversationId;
    }

    private String previousAssistant(String workspaceId, String messageId) {
        String content = jdbcTemplate.query("""
                select earlier.content
                from conversation_message current_message
                join conversation_message earlier
                  on earlier.conversation_id = current_message.conversation_id
                 and earlier.workspace_id = current_message.workspace_id
                where current_message.id = ? and current_message.workspace_id = ?
                  and earlier.role = 'ASSISTANT'
                  and earlier.created_at <= current_message.created_at
                order by earlier.created_at desc
                limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, messageId, workspaceId);
        return content == null ? "" : bound(content, MAX_PROMPT_MESSAGE_CHARS);
    }

    private static String extractionPrompt(String message, String previousAnswer) {
        if (previousAnswer == null || previousAnswer.isBlank()) {
            return "用户消息：\n" + message;
        }
        return "上一条助手回答：\n" + previousAnswer + "\n用户消息：\n" + message;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private void completeTask(String taskId, String message, String resultRef) {
        if (taskId == null || taskId.isBlank()) return;
        String status = jdbcTemplate.query("select task_status from task where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (status == null || !Set.of("PENDING", "RUNNING", "WAITING").contains(status)) return;
        if ("PENDING".equals(status)) taskService.startTask(taskId);
        taskService.completeTask(taskId, "COMPLETED", message, resultRef);
    }

    /**
     * 本轮没抽到东西是因为模型不可用：不把任务当成功，标成可重试，
     * 之后可以走既有的重驱入口（{@code TaskService#redriveTask}）再跑一次。
     */
    private void markTaskForRetry(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        String status = jdbcTemplate.query("select task_status from task where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (status == null || !Set.of("PENDING", "RUNNING", "WAITING").contains(status)) return;
        taskService.failTask(taskId, RETRY_PHASE, "记忆抽取模型不可用，本轮未抽取", RETRY_PHASE, true);
    }

    private static String bound(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    record Preference(
            String statement,
            String category,
            boolean explicit,
            String slot,
            String value,
            String polarity,
            boolean feedback
    ) {
        Preference(String statement, String category, boolean explicit) {
            this(statement, category, explicit, null, null, null, false);
        }

        Preference(String statement, String category, boolean explicit, String slot, String value, String polarity) {
            this(statement, category, explicit, slot, value, polarity, false);
        }

        MemorySlots.Assignment assignment() {
            return MemorySlots.complete(category, statement, "PREFERENCE", slot, value, polarity);
        }
    }

    private record KnownMemory(String id, String text, String slot, String value, String polarity) {
    }

    /**
     * 抽取结果。
     *
     * @param method    结果来源：LLM、CLASSIFIED_NO、LLM_UNAVAILABLE、LLM_FAILED
     * @param retryable 本轮因为模型失败没抽成，任务应标为可重试
     */
    record Extraction(List<Preference> preferences, String method, boolean retryable) {

        Extraction(List<Preference> preferences, String method) {
            this(preferences, method, false);
        }
    }

    public record ExtractedCandidate(String candidateId, String statement, String sourceType, String reviewStatus) { }

    public record ExtractionResult(List<ExtractedCandidate> candidates, String method) { }
}
