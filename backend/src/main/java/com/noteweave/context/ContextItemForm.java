package com.noteweave.context;

/**
 * 上下文条目的材料形态。
 *
 * <p>形态必须随条目一起落到视图、快照和提示词里。把摘要渲染成原文、把句柄渲染成已展开内容，
 * 都会让模型以为看到了账本里并不存在的信息，因此形态是契约字段而不是渲染细节。</p>
 */
public enum ContextItemForm {

    /** 账本原文，正文可用。 */
    RAW("raw"),
    /** 由原文确定性生成的消息摘要，不能替代原文。 */
    MESSAGE_SUMMARY("message_summary"),
    /** 异步生成的话题摘要，覆盖一个话题段。 */
    TOPIC_SUMMARY("topic_summary"),
    /** 句柄：只给位置、范围与身份，需要显式回忆才展开。 */
    HANDLE("handle"),
    /** 结构化材料，例如前提、工作集条目、编号证据。 */
    STRUCTURED("structured"),
    /** 占位：账本位置保留，正文不可用（删除、失败、取消、进行中）。 */
    PLACEHOLDER("placeholder");

    private final String wire;

    ContextItemForm(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
