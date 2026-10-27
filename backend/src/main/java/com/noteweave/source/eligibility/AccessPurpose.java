package com.noteweave.source.eligibility;

/** 来源访问用途；同一来源在不同用途下的可读性可能不同。 */
public enum AccessPurpose {
    /** 历史 Note 与历史版本展示：允许读取历史正文，但失效引用不可点击原文。 */
    HISTORY_DISPLAY,
    /** 当前回答证据与引用：要求来源当前可用。 */
    CURRENT_EVIDENCE,
    /** 再发布、导出、另存：要求包括生成来源依赖闭包在内的全部依赖可用。 */
    REPUBLISH
}
