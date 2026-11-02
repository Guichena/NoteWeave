package com.noteweave.context;

/**
 * 上下文条目引用的对象类型。
 *
 * <p>类型决定撤销与脱敏怎么定位：消息按 messageId 撤销，摘要按 revisionId 失效，
 * 前提按 premiseId 撤回。下游不得只凭展示文本判断条目来源。</p>
 */
public enum ContextRefType {

    /** 会话消息。 */
    MESSAGE("message"),
    /** 消息摘要版本。 */
    MESSAGE_SUMMARY("message_summary"),
    /** 话题摘要版本。 */
    TOPIC_SUMMARY("topic_summary"),
    /** 用户要求行。 */
    CONSTRAINT("constraint"),
    /** 会话前提行。 */
    PREMISE("premise"),
    /** 长期记忆版本。 */
    MEMORY_REVISION("memory_revision"),
    /** 最终编号证据。 */
    EVIDENCE("evidence"),
    /** 工作集条目。 */
    WORKING_SET_ENTRY("working_set_entry"),
    /** 研究投影。 */
    RESEARCH_PROJECTION("research_projection"),
    /** 渲染分段，例如原问题与独立问题。 */
    SECTION("section"),
    /** 只保留位置的账本占位。 */
    LEDGER_PLACEHOLDER("ledger_placeholder");

    private final String wire;

    ContextRefType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
