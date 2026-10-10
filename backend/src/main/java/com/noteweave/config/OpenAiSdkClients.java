package com.noteweave.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;

/**
 * 统一创建 OpenAI 官方 Java SDK 客户端。
 * <p>
 * 配置里保存的是完整接口地址（如 {@code .../v1/chat/completions}），SDK 需要的是 base URL，
 * 这里去掉接口路径后交给 SDK。不跟随重定向：Provider 地址失效时常被重定向到登录或错误页，
 * 直接按失败处理，也避免把请求和密钥带到别的主机。
 */
public final class OpenAiSdkClients {

    /** 本地或无需鉴权的服务不配置密钥时，SDK 仍要求提供一个凭据，用占位值代替。 */
    static final String NO_API_KEY_PLACEHOLDER = "noteweave-no-api-key";

    private OpenAiSdkClients() {
    }

    public static OpenAIClient create(
            String endpoint,
            String operationPath,
            String apiKey,
            Duration timeout,
            int maxRetries
    ) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl(endpoint, operationPath))
                .apiKey(apiKey == null || apiKey.isBlank() ? NO_API_KEY_PLACEHOLDER : apiKey.trim())
                .timeout(timeout)
                .maxRetries(Math.max(0, maxRetries))
                .followRedirects(false)
                .build();
    }

    static String baseUrl(String endpoint, String operationPath) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(operationPath)) {
            normalized = normalized.substring(0, normalized.length() - operationPath.length());
        }
        return normalized;
    }
}
