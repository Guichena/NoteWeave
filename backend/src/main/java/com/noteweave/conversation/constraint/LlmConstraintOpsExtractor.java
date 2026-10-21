package com.noteweave.conversation.constraint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于现有 {@link ChatLlmClient} 的约束抽取实现。
 * <p>
 * 四条硬约束：
 * <ul>
 *   <li>失败必须显式：非法 JSON / 超时 / 调用异常都返回 {@link ConstraintOpsExtractor.Result.Unusable}，
 *       由调用方跳过这条消息（本轮不产出约束）；不抛异常、不返回空 ops 冒充「模型认为没有约束」。</li>
 *   <li>重试有且只有一次：超时、调用失败、输出不合规都再试一次，仍失败就返回 {@link #TIMEOUT} /
 *       {@link #CALL_FAILED} / {@link #MALFORMED}。超时重试会把单条消息的墙钟上限翻倍
 *       （默认 8s → 16s），调用方在事务里，代价是已知的。</li>
 *   <li>超时自己管：{@code ChatLlmClient} 只有连接级超时，所以这里把一次调用丢到线程池上，
 *       {@code future.get(timeoutMs)} 到点就取消。底层 socket 仍可能拖到连接超时，属于已知限制。</li>
 *   <li>日志只留原因与条数：提示词里有用户原文，绝不打日志。</li>
 * </ul>
 * 本类只做「拿到 ops」，不合并、不写库；置信度过滤也留给合并器，保证冲突判定只有一处。
 */
@Component
public class LlmConstraintOpsExtractor implements ConstraintOpsExtractor {

    /** 模型未启用（{@code noteweave.llm.*} 缺失或开关关闭）。 */
    public static final String DISABLED = "DISABLED";
    /** 单次调用超过 {@code constraint-extraction-timeout-ms}。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 调用抛异常（网络、鉴权、空流等）。 */
    public static final String CALL_FAILED = "CALL_FAILED";
    /** 首次输出与重试输出都不合规。 */
    public static final String MALFORMED = "MALFORMED";
    /** 空消息无需调用模型。 */
    public static final String EMPTY_MESSAGE = "EMPTY_MESSAGE";

    private static final Logger log = LoggerFactory.getLogger(LlmConstraintOpsExtractor.class);
    /** 一条消息最多两次模型调用：超时/调用失败/输出不合规都只重试一次。 */
    private static final int MAX_ATTEMPTS = 2;
    /** 超时后线程仍可能在等 socket；留两个线程，避免一次超时把后续消息全部堵住。 */
    private static final int WORKER_THREADS = 2;

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final long timeoutMs;
    private final double minConfidence;

    @Autowired
    public LlmConstraintOpsExtractor(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            @Value("${noteweave.context.v2.constraint-extraction-timeout-ms:8000}") long timeoutMs,
            @Value("${noteweave.context.v2.constraint-op-min-confidence:0.55}") double minConfidence
    ) {
        this(llmClient, objectMapper, timeoutMs, minConfidence, defaultExecutor());
    }

    /** 便于测试注入受控执行器；执行器的生命周期由调用方负责。 */
    LlmConstraintOpsExtractor(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            long timeoutMs,
            double minConfidence,
            ExecutorService executor
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.minConfidence = Math.min(1.0, Math.max(0.0, minConfidence));
    }

    @Override
    public Result extract(Request request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return new Result.Unusable(EMPTY_MESSAGE);
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return new Result.Unusable(DISABLED);
        }
        String userPrompt = ConstraintOpsPrompt.userPrompt(request);
        String lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            boolean lastAttempt = attempt + 1 >= MAX_ATTEMPTS;
            String output;
            try {
                output = call(userPrompt);
            } catch (TimeoutException ex) {
                log.warn("约束抽取超时: reason={}, timeoutMs={}, attempt={}", TIMEOUT, timeoutMs, attempt + 1);
                if (!lastAttempt) {
                    // 同一提示词重试：超时与提示词无关，没必要追加格式要求。
                    continue;
                }
                return new Result.Unusable(TIMEOUT);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("约束抽取被中断: reason={}", CALL_FAILED);
                return new Result.Unusable(CALL_FAILED);
            } catch (RuntimeException ex) {
                // 只打异常类型：异常消息里可能带响应体或请求地址。
                log.warn("约束抽取调用失败: reason={}, type={}, attempt={}",
                        CALL_FAILED, ex.getClass().getSimpleName(), attempt + 1);
                if (!lastAttempt) {
                    continue;
                }
                return new Result.Unusable(CALL_FAILED);
            }
            ConstraintOpSchema.ParseResult parsed = ConstraintOpSchema.parse(objectMapper, output);
            if (parsed.ok()) {
                log.debug("约束抽取成功: ops={}", parsed.ops().size());
                return new Result.Ok(parsed.ops(), ConstraintOpsPrompt.PROMPT_VERSION);
            }
            log.info("约束抽取输出不合规: reason={}, attempt={}", parsed.failure(), attempt + 1);
            lastFailure = parsed.failure();
            userPrompt = ConstraintOpsPrompt.retryPrompt(userPrompt);
        }
        // detail 只带校验器的原因常量（例如 MISSING_FORBID_VALUE）：输出正文可能含用户原文，不能进日志。
        log.warn("约束抽取输出始终不合规: reason={}, detail={}", MALFORMED, lastFailure);
        return new Result.Unusable(MALFORMED);
    }

    /** 合并器用的置信度门槛；抽取器本身不过滤，避免同一规则出现两份实现。 */
    public double minConfidence() {
        return minConfidence;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    private String call(String userPrompt) throws TimeoutException, InterruptedException {
        // 显式声明为 Callable：submit 的 Runnable/Callable 重载在 lambda 上都可推断，写清楚避免歧义。
        Callable<String> task = () -> llmClient.streamChat(
                ConstraintOpsPrompt.SYSTEM_PROMPT, userPrompt, ConstraintOpsPrompt.MAX_OUTPUT_TOKENS, token -> { });
        Future<String> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private static ExecutorService defaultExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "constraint-ops-extractor");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(WORKER_THREADS, factory);
    }
}
