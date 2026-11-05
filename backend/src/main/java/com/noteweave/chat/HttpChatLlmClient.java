package com.noteweave.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.OpenAiSdkClients;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenAI 官方 Java SDK 的 chat.completions 流式调用实现，兼容任意 OpenAI 协议的服务。
 * <p>
 * SSE 解析、错误映射和超时由 SDK 处理；本类负责组装请求、逐段回调 token，
 * 并要求流以带 {@code finish_reason} 的分片正常结束且有可见内容。
 * 配置缺失时 {@link #isEnabled()} 返回 {@code false}，由 ChatService.stream 走模板 chunk 回退分支。
 */
@Component
public class HttpChatLlmClient implements ChatLlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final boolean enabled;
    private final String model;
    private final OpenAIClient client;
    private final com.noteweave.conversation.ActualUsageRecorder usageRecorder;

    /** 改写、抽取、判定等短输出调用的思考策略，见 application.yml 的 noteweave.llm.auxiliary-thinking。 */
    @org.springframework.beans.factory.annotation.Value("${noteweave.llm.auxiliary-thinking:auto}")
    String auxiliaryThinking = "auto";

    /** 回答正文的思考策略，默认 auto：2026-11-05 对比测试中关闭思考后长回答首字快一半，质量无明显差异。 */
    @org.springframework.beans.factory.annotation.Value("${noteweave.llm.answer-thinking:auto}")
    String answerThinking = "auto";

    public HttpChatLlmClient(ObjectMapper objectMapper, com.noteweave.config.NoteWeaveProperties properties) {
        this(objectMapper, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpChatLlmClient(
            ObjectMapper objectMapper,
            com.noteweave.config.NoteWeaveProperties properties,
            com.noteweave.conversation.ActualUsageRecorder usageRecorder
    ) {
        this.usageRecorder = usageRecorder;
        com.noteweave.config.NoteWeaveProperties.Llm llm = properties.llm();
        this.enabled = llm != null && llm.enabled()
                && llm.endpoint() != null && !llm.endpoint().isBlank()
                && llm.model() != null && !llm.model().isBlank();
        this.model = enabled ? llm.model() : "";
        Duration timeout = Duration.ofSeconds(llm == null ? 60L : Math.max(10L, llm.timeoutSeconds()));
        this.client = enabled
                ? OpenAiSdkClients.create(llm.endpoint(), CHAT_COMPLETIONS_PATH, llm.apiKey(), timeout, 0)
                : null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        return streamChat(systemPrompt, userPrompt, 1200, onToken);
    }

    @Override
    public String streamChat(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        return streamChatInSession(null, systemPrompt, userPrompt, maximumOutputTokens, onToken);
    }

    @Override
    public String streamChatInSession(
            String sessionId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        return streamChatInSession(sessionId, null, systemPrompt, userPrompt, maximumOutputTokens, onToken);
    }

    /**
     * USAGE-01：带上工作区归属的会话调用。sessionId 是 SHA-256 摘要，反推不出工作区，
     * 必须显式传入，否则真实 usage 会以空 workspace 落库，对账时看不到。
     */
    @Override
    public String streamChatInSession(
            String sessionId,
            String workspaceId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        return stream(sessionId, workspaceId, systemPrompt, userPrompt, maximumOutputTokens, onToken,
                resolveThinking(auxiliaryThinking));
    }

    @Override
    public String streamAnswerInSession(
            String sessionId,
            String workspaceId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        return stream(sessionId, workspaceId, systemPrompt, userPrompt, maximumOutputTokens, onToken,
                resolveThinking(answerThinking));
    }

    /**
     * 把配置的思考策略换成请求里的 thinking.type；返回 null 表示不发该参数。
     * deepseek-v4 默认开启思考，实测同一提示首个正文字符从 7.1 秒降到 2.7 秒。
     */
    String resolveThinking(String policy) {
        String normalized = policy == null ? "auto" : policy.trim().toLowerCase(Locale.ROOT);
        boolean thinkingModel = isThinkingModel(model);
        return switch (normalized) {
            case "enabled", "disabled" -> normalized;
            // glm-5 历来一律关闭思考，沿用旧行为
            case "model-default" -> model.toLowerCase(Locale.ROOT).startsWith("glm-5") ? "disabled" : null;
            default -> thinkingModel ? "disabled" : null;
        };
    }

    static boolean isThinkingModel(String model) {
        String lower = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return lower.startsWith("glm-5") || lower.startsWith("deepseek-v4") || lower.equals("deepseek-flash");
    }

    private String stream(
            String sessionId,
            String workspaceId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken,
            String thinking
    ) {
        if (!enabled) {
            throw new IllegalStateException("HttpChatLlmClient is not enabled; check noteweave.llm.* configuration");
        }
        ChatCompletionCreateParams params = buildRequest(sessionId, systemPrompt, userPrompt, maximumOutputTokens, thinking);
        // attemptId 在尝试开始时生成并稳定复用；一次 streamChat 调用就是一次尝试
        String attemptId = sessionId == null || sessionId.isBlank()
                ? "chat-" + java.util.UUID.randomUUID()
                : "chat-" + sessionId + "-" + java.util.UUID.randomUUID();
        // 已收到的 usage 事实：断流/异常路径也要保留，不能把已有事实丢成 UNKNOWN
        java.util.concurrent.atomic.AtomicReference<CompletionUsage> receivedUsage =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean usageRecorded = new java.util.concurrent.atomic.AtomicBoolean();
        try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(params)) {
            StreamResult result = consumeStreamWithUsage(
                    response.stream().iterator(), onToken, receivedUsage::set);
            recordUsageOnce(workspaceId, attemptId, result.usage(),
                    com.noteweave.conversation.ActualUsage.Completeness.COMPLETE, usageRecorded);
            return result.content();
        } catch (IOException | OpenAIException ex) {
            // 记录失败不掩盖原始 LLM 异常；收到过 usage 但未正常结束按 PARTIAL
            CompletionUsage usage = receivedUsage.get();
            recordUsageOnce(workspaceId, attemptId, usage,
                    usage != null
                            ? com.noteweave.conversation.ActualUsage.Completeness.PARTIAL
                            : com.noteweave.conversation.ActualUsage.Completeness.UNKNOWN,
                    usageRecorded);
            throw new IllegalStateException("LLM stream call failed: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            // 资源 close 异常或回调取消：同样保留 usage 事实后原样上抛
            CompletionUsage usage = receivedUsage.get();
            recordUsageOnce(workspaceId, attemptId, usage,
                    usage != null
                            ? com.noteweave.conversation.ActualUsage.Completeness.PARTIAL
                            : com.noteweave.conversation.ActualUsage.Completeness.UNKNOWN,
                    usageRecorded);
            throw ex;
        }
    }

    /** 同一尝试只记录一次 usage；避免 close 异常与主路径重复记录。 */
    private void recordUsageOnce(
            String workspaceId,
            String attemptId,
            CompletionUsage usage,
            com.noteweave.conversation.ActualUsage.Completeness completeness,
            java.util.concurrent.atomic.AtomicBoolean recorded
    ) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        recordUsage(workspaceId, attemptId, usage, completeness);
    }

    private void recordUsage(
            String workspaceId,
            String requestIdentity,
            CompletionUsage usage,
            com.noteweave.conversation.ActualUsage.Completeness completeness
    ) {
        if (usageRecorder == null) {
            return;
        }
        com.noteweave.conversation.ActualUsage actual = usage == null
                ? com.noteweave.conversation.ActualUsage.unreported(model, completeness)
                : com.noteweave.conversation.ActualUsage.reported(model,
                        usage.promptTokens(),
                        usage.completionTokens());
        usageRecorder.record(requestIdentity, workspaceId, actual);
    }

    ChatCompletionCreateParams buildRequest(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens
    ) {
        return buildRequest(null, systemPrompt, userPrompt, maximumOutputTokens);
    }

    ChatCompletionCreateParams buildRequest(
            String sessionId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens
    ) {
        return buildRequest(sessionId, systemPrompt, userPrompt, maximumOutputTokens, resolveThinking(auxiliaryThinking));
    }

    ChatCompletionCreateParams buildRequest(
            String sessionId,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            String thinking
    ) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                // 兼容服务普遍只认 max_tokens，不用 SDK 推荐的 max_completion_tokens。
                .putAdditionalBodyProperty("max_tokens", JsonValue.from(Math.max(1, maximumOutputTokens)))
                // 显式请求流式 usage 帧；供应商不支持时按未上报处理，不伪造真实用量
                .putAdditionalBodyProperty("stream_options", JsonValue.from(Map.of("include_usage", true)));
        if (thinking != null) {
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(Map.of("type", thinking)));
        }
        // 会话标识只随本次请求发送，单例客户端不保存，避免串到其他对话
        if (sessionId != null && !sessionId.isBlank()) {
            builder.putAdditionalHeader("X-Session-ID", sessionId);
        }
        return builder.build();
    }

    String consumeStream(Iterator<ChatCompletionChunk> chunks, Consumer<String> onToken) throws IOException {
        return consumeStreamWithUsage(chunks, onToken).content();
    }

    StreamResult consumeStreamWithUsage(Iterator<ChatCompletionChunk> chunks, Consumer<String> onToken)
            throws IOException {
        return consumeStreamWithUsage(chunks, onToken, received -> { });
    }

    /**
     * 消费流并在结束时返回 usage；usage-only 帧的 choices 为空，不会当作异常内容丢弃。
     * usage 到达即通过 usageSink 暴露，保证异常路径也能保留已收到的事实。
     */
    StreamResult consumeStreamWithUsage(
            Iterator<ChatCompletionChunk> chunks,
            Consumer<String> onToken,
            java.util.function.Consumer<CompletionUsage> usageSink
    ) throws IOException {
        StringBuilder accumulated = new StringBuilder();
        boolean finished = false;
        CompletionUsage usage = null;
        while (chunks.hasNext()) {
            ChatCompletionChunk chunk = chunks.next();
            for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                String token = choice.delta().content().orElse("");
                if (!token.isEmpty()) {
                    accumulated.append(token);
                    onToken.accept(token);
                }
                if (choice.finishReason().isPresent()) {
                    finished = true;
                }
            }
            if (chunk.usage().isPresent()) {
                usage = chunk.usage().get();
                usageSink.accept(usage);
            }
        }
        if (!finished) {
            throw new IOException("LLM stream completed without finish_reason");
        }
        if (accumulated.toString().isBlank()) {
            throw new IOException("LLM stream completed without content");
        }
        return new StreamResult(accumulated.toString(), usage, finished);
    }

    record StreamResult(
            String content,
            CompletionUsage usage,
            boolean finished
    ) {
    }
}
