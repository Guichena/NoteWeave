package com.noteweave.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import java.util.Locale;

/**
 * live 评测共用的模型客户端装配：只读环境变量/系统属性，绝不把密钥写进日志或断言消息。
 * <p>
 * 触发条件由测试类上的注解负责：{@code DEEPSEEK_API_KEY} 非空，且 {@code -Dnoteweave.live.llm=true}。
 * <p>
 * 配置来源（前者优先，均可用系统属性覆盖）：
 * <ul>
 *   <li>接口地址：{@code -Dnoteweave.live.llm.endpoint} &gt; {@code NOTEWEAVE_LLM_ENDPOINT} &gt;
 *       默认 {@code https://api.deepseek.com/v1/chat/completions}；这是完整接口地址，
 *       {@code HttpChatLlmClient} 会按 {@code /chat/completions} 剥出 base URL。</li>
 *   <li>模型名：{@code -Dnoteweave.live.llm.model} &gt; {@code NOTEWEAVE_LLM_MODEL} &gt; 默认 {@code deepseek-chat}。</li>
 *   <li>密钥：{@code -Dnoteweave.live.llm.api-key} &gt; {@code NOTEWEAVE_LLM_API_KEY} &gt; {@code DEEPSEEK_API_KEY}；
 *       走 relay 时用前两者注入上游 key。</li>
 *   <li>超时秒数：{@code -Dnoteweave.live.llm.timeout-seconds} &gt; {@code NOTEWEAVE_LLM_TIMEOUT_SECONDS} &gt;
 *       默认 {@code 60}，且不小于 10。</li>
 *   <li>及格线：{@code -Dnoteweave.live.llm.min-score}，取值 0..1；不设置时只报告分数、不让测试失败。</li>
 * </ul>
 * 这里只返回已经装配好的 {@link ChatLlmClient}，真正的网络调用发生在评测类里。
 */
public final class LiveLlmEvalSupport {

    /** 触发 live 评测的环境变量名。 */
    public static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    /** relay 场景优先使用的密钥环境变量。 */
    public static final String RELAY_API_KEY_ENV = "NOTEWEAVE_LLM_API_KEY";
    /** 默认接口地址（DeepSeek 官方，完整 chat/completions 路径）。 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    /** 默认模型名。 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final String ENDPOINT_PROPERTY = "noteweave.live.llm.endpoint";
    private static final String MODEL_PROPERTY = "noteweave.live.llm.model";
    private static final String API_KEY_PROPERTY = "noteweave.live.llm.api-key";
    private static final String TIMEOUT_PROPERTY = "noteweave.live.llm.timeout-seconds";
    private static final String MIN_SCORE_PROPERTY = "noteweave.live.llm.min-score";
    private static final String ENDPOINT_ENV = "NOTEWEAVE_LLM_ENDPOINT";
    private static final String MODEL_ENV = "NOTEWEAVE_LLM_MODEL";
    private static final String TIMEOUT_ENV = "NOTEWEAVE_LLM_TIMEOUT_SECONDS";
    private static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    private LiveLlmEvalSupport() {
    }

    /** 用现有 {@code HttpChatLlmClient} 装配真实模型客户端；密钥只在内存里。 */
    public static ChatLlmClient client() {
        String apiKey = firstNonBlank(
                System.getProperty(API_KEY_PROPERTY),
                System.getenv(RELAY_API_KEY_ENV),
                System.getenv(API_KEY_ENV));
        if (apiKey == null) {
            throw new IllegalStateException("live 评测需要 " + API_KEY_ENV + " 或 " + RELAY_API_KEY_ENV);
        }
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null, null, null,
                new NoteWeaveProperties.Llm(true, endpoint(), model(), apiKey, timeoutSeconds()));
        return new HttpChatLlmClient(new ObjectMapper(), properties);
    }

    /** 及格线；未配置时返回 {@link Double#NaN}，表示只报告、不因分数低失败。 */
    public static double minScoreOrNaN() {
        String value = System.getProperty(MIN_SCORE_PROPERTY);
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        try {
            double score = Double.parseDouble(value.trim());
            if (!(score >= 0.0 && score <= 1.0)) {
                throw new IllegalStateException(MIN_SCORE_PROPERTY + " 必须在 0..1 之间");
            }
            return score;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(MIN_SCORE_PROPERTY + " 必须是 0..1 之间的小数");
        }
    }

    /** 可安全打印的目标描述：只有模型名与是否覆盖了接口地址，不含密钥。 */
    public static String describeTarget() {
        boolean endpointOverridden = firstNonBlank(System.getProperty(ENDPOINT_PROPERTY),
                System.getenv(ENDPOINT_ENV)) != null;
        return "model=" + model() + ", endpoint=" + (endpointOverridden ? "override" : "default");
    }

    /** 接口地址，默认 DeepSeek 官方。 */
    public static String endpoint() {
        String value = firstNonBlank(System.getProperty(ENDPOINT_PROPERTY), System.getenv(ENDPOINT_ENV));
        return value == null ? DEFAULT_ENDPOINT : value;
    }

    /** 模型名，默认 {@code deepseek-chat}。 */
    public static String model() {
        String value = firstNonBlank(System.getProperty(MODEL_PROPERTY), System.getenv(MODEL_ENV));
        return value == null ? DEFAULT_MODEL : value;
    }

    private static long timeoutSeconds() {
        // 与 application.yml 的 noteweave.llm.timeout-seconds 同一个环境变量，便于复用现有配置。
        String value = firstNonBlank(System.getProperty(TIMEOUT_PROPERTY), System.getenv(TIMEOUT_ENV));
        if (value == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            return Math.max(10L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(TIMEOUT_PROPERTY + " / " + TIMEOUT_ENV + " 必须是整数秒数");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** 统一的分数输出格式，避免不同评测类各写一套。 */
    public static String scoreLine(String evalName, int passed, int total) {
        double score = total == 0 ? 0.0 : (double) passed / total;
        return String.format(Locale.ROOT, "%s: %d/%d passed, 总分=%.3f", evalName, passed, total, score);
    }
}
