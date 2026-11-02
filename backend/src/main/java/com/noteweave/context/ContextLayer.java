package com.noteweave.context;

/**
 * 上下文视图的分层标识。
 *
 * <p>每个调用点只允许读取自己声明过的层，渲染顺序由 {@link ContextContract#ordering()} 固定，
 * 不依赖 Map 或数据库返回顺序。层本身描述的是材料形态，不描述信任等级：原文、摘要和句柄
 * 必须能被下游区分，摘要永远不能冒充原文已恢复。</p>
 */
public enum ContextLayer {

    /** 公共规则与模式要求，属于系统提示，不参与用户提示的预算竞争。 */
    SYSTEM_RULES("system_rules", "公共规则与模式要求"),
    /** 当前用户问题原文。 */
    CURRENT_INPUT("current_input", "当前问题"),
    /** 话题摘要，覆盖一个较早话题段。 */
    TOPIC_SUMMARY("topic_summary", "话题摘要"),
    /** 消息摘要，覆盖少量较早回合。 */
    MESSAGE_SUMMARY("message_summary", "消息摘要"),
    /** 原文，最近的回合优先使用。 */
    RAW_MESSAGE("raw_message", "原文"),
    /** 可回忆句柄，只给出位置与范围，需要显式回忆才展开正文。 */
    MESSAGE_HANDLE("message_handle", "可回忆句柄"),
    /** 已生效的用户要求。 */
    USER_CONSTRAINT("user_constraint", "生效要求"),
    /** 用户陈述但未经资料核实的前提。 */
    CONVERSATION_PREMISE("conversation_premise", "未经核实前提"),
    /** 长期记忆引用，只给受控编译后的版本引用。 */
    MEMORY_REFERENCE("memory_reference", "长期记忆引用"),
    /** 受控编译后的表达参数，不允许把原始记忆粘进系统提示。 */
    EXPRESSION_PARAM("expression_param", "受控表达参数"),
    /** 最终编号证据。 */
    EVIDENCE("evidence", "最终编号证据"),
    /** 会话工作集，只给选择线索，不给可绕开资格的事实。 */
    WORKING_SET("working_set", "会话工作集"),
    /** 研究投影，P0/P1/P2 分级后的有界视图。 */
    RESEARCH_PROJECTION("research_projection", "研究投影");

    private final String wire;
    private final String label;

    ContextLayer(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** 落库、快照与提示词里使用的稳定字面量。 */
    public String wire() {
        return wire;
    }

    /** 渲染用中文标题。 */
    public String label() {
        return label;
    }
}
