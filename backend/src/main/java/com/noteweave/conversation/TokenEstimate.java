package com.noteweave.conversation;

/**
 * C5 token 预估结果。
 *
 * <p>预估用于调用前的预算控制；不能把估计值当作供应商返回的真实用量。
 * method 说明估算方式：legacy-character 表示原有的字符折算规则，
 * 没有 provider 精确分词器时明确标记近似，不宣称为某模型的准确分词。</p>
 */
public record TokenEstimate(
        int count,
        String method,
        String tokenizerVersion,
        String modelProfile
) {
    public static final String METHOD_LEGACY_CHARACTER = "legacy-character";
    public static final String TOKENIZER_LEGACY_V1 = "legacy-cjk-aware-v1";
    public static final String PROFILE_UNSPECIFIED = "unspecified";

    public static TokenEstimate legacy(int count, String modelProfile) {
        return new TokenEstimate(count, METHOD_LEGACY_CHARACTER, TOKENIZER_LEGACY_V1,
                modelProfile == null || modelProfile.isBlank() ? PROFILE_UNSPECIFIED : modelProfile);
    }
}
