package com.noteweave.source;

import com.noteweave.common.BusinessException;
import java.time.Duration;

/**
 * PARSE-01：文档解析服务的失败按「可重试性」分类，而不是所有非 2xx 都折成同一个终态错误。
 *
 * <p>429 限流、503/504 服务不可用、连接超时属于暂时性失败：并发上传 Office 资料时解析服务
 * 会短暂拒绝请求，重试通常能成功，直接判终态会让资料永久停在 FAILED。
 * 415/422 等表示文档本身不被接受，重试没有意义。</p>
 */
public class SourceParserUnavailableException extends BusinessException {

    private final boolean retryable;
    private final Duration retryAfter;

    public SourceParserUnavailableException(
            String code, String message, boolean retryable, Duration retryAfter) {
        super(code, message);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 服务端给出的 Retry-After 提示；没有该响应头时为 null。 */
    public Duration retryAfter() {
        return retryAfter;
    }

    public static SourceParserUnavailableException rateLimited(Duration retryAfter) {
        return new SourceParserUnavailableException(
                "SOURCE_PARSER_RATE_LIMITED", "文档解析服务限流，稍后重试", true, retryAfter);
    }

    public static SourceParserUnavailableException transientFailure(String message) {
        return new SourceParserUnavailableException("SOURCE_PARSER_UNAVAILABLE", message, true, null);
    }

    public static SourceParserUnavailableException rejected(int status) {
        return new SourceParserUnavailableException(
                "SOURCE_PARSER_REJECTED", "文档解析服务拒绝该资料（HTTP " + status + "）", false, null);
    }
}
