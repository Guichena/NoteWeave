package com.noteweave.knowledge;

/**
 * Wiki 页面的内容来源状态（WIKI-03）。
 *
 * <p>{@code AUTO} 由工作台级 ingest 维护，可以随资料变化自动更新；
 * {@code MANUAL} 表示用户改过正文，自动更新必须让路；
 * {@code NEEDS_REVIEW} 表示用户改过之后来源又有变化，自动内容被跳过，等待人工确认。</p>
 */
public enum WikiPageState {
    AUTO,
    MANUAL,
    NEEDS_REVIEW;

    /** 该状态下自动 ingest 是否可以直接覆盖最新版本。 */
    public boolean autoUpdatable() {
        return this == AUTO;
    }

    /** 存量行的状态回填：由系统写入且之后没有人改过的页面按自动页面处理。 */
    static WikiPageState fromStored(String state, String updatedBy) {
        if ("AUTO".equals(state)) {
            return AUTO;
        }
        if ("MANUAL".equals(state)) {
            return MANUAL;
        }
        if ("NEEDS_REVIEW".equals(state)) {
            return NEEDS_REVIEW;
        }
        // 没有显式状态（迁移前的旧行）：只有系统写入的记录才允许自动更新，
        // 其余一律按人工页面保护，宁可少更新也不覆盖用户内容。
        return updatedBy != null && updatedBy.startsWith("SYSTEM:") ? AUTO : MANUAL;
    }
}
