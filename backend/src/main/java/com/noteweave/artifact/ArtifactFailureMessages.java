package com.noteweave.artifact;

import java.util.List;
import java.util.Locale;

/**
 * 把产物 Worker 的失败原因改写成用户能看懂的说法。
 * Worker 的原始报错面向排障，常带契约、骨架、关键帧等内部用语；
 * 原文仍通过 failureDetail 返回，供悬停查看和排查使用。
 */
final class ArtifactFailureMessages {

    static final String FALLBACK = "产物生成失败，请稍后重试。";
    static final String NO_VIDEO_FRAMES =
            "所选资料里没有可用的视频画面，这类产物需要带画面的视频资料。可以换一份视频资料，或改用不需要画面的产物类型。";
    static final String TIMEOUT = "生成用时过长，已自动停止。请稍后重试，或减少所选资料后再试。";
    static final String MODEL_BUSY = "模型服务当前繁忙，请稍后重试。";
    static final String FORMAT_CHECK =
            "生成的内容没有通过格式检查，已停止。请重试；多次失败时可以减少资料或简化要求。";

    /** 出现这些词说明是写给开发者看的内部报错，不能直接展示给用户。 */
    private static final List<String> INTERNAL_TERMS = List.of(
            "契约", "骨架", "关键帧", "不合规", "交付", "校验", "单元", "schema", "json", "skill",
            "payload", "exception", "traceback");

    private ArtifactFailureMessages() {
    }

    static String forUser(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.contains("关键帧") && containsAny(text, "没有", "缺少", "无法取得", "不足", "不可用")) {
            return NO_VIDEO_FRAMES;
        }
        if (containsAny(lower, "超时", "timed out", "timeout")) {
            return TIMEOUT;
        }
        if (containsAny(lower, "429", "限流", "rate limit", "too many requests")) {
            return MODEL_BUSY;
        }
        if (containsAny(lower, "契约", "骨架", "不合规", "不满足交付", "校验")) {
            return FORMAT_CHECK;
        }
        if (containsAny(lower, INTERNAL_TERMS.toArray(String[]::new))) {
            return FALLBACK;
        }
        return text;
    }

    /** 原文和改写后不同时才回传原文，避免重复展示。 */
    static String detail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return text.equals(forUser(text)) ? null : text;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
