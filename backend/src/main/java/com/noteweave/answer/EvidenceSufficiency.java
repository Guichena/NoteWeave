package com.noteweave.answer;

/** 生成前的证据充分性。历史行没有这一列时接口省略字段，不显示成 NOT_RUN。 */
public enum EvidenceSufficiency {
    SUFFICIENT,
    PARTIAL,
    INSUFFICIENT,
    NOT_RUN;

    /** 空白或无法识别的库存值返回 null，调用方据此省略字段。 */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvidenceSufficiency.valueOf(raw.trim()).name();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
