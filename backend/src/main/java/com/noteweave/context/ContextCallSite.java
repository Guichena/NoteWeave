package com.noteweave.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文调用点。编号沿用架构设计第 6.1 节的 C1 到 C8 与 R0 到 R4。
 *
 * <p>每个调用点声明四件事：允许读取哪些层、渲染顺序、目标预算、必带层。目标预算是软目标，
 * 硬上限由 {@link ContextBudgetPlanner} 按模型窗口扣除输出预留与安全余量后算出，两者不能混用：
 * 各层目标相加不等于可以发送的总量。</p>
 */
public enum ContextCallSite {

    /** 要求与前提抽取：只读当前消息、上一条助手摘要与生效要求，不读证据和长期记忆。 */
    C1("C1", "要求与前提抽取", 1_500, 0, 200),
    /** 问题改写：当前问题、最近回合原文、较早摘要、话题句柄与任务前提，不注入长期记忆或证据正文。 */
    C2("C2", "问题改写", 3_000, 0, 200),
    /** 回忆：独立问题与已验证指代，在冻结边界内读取旧回合，预算按结果预留。 */
    C3("C3", "回忆", 2_000, 0, 200),
    /** 检索参数：独立问题、关键词、显式过滤与合格工作集信号，不拼接对话全文。 */
    C4("C4", "检索参数", 1_200, 0, 100),
    /** 充分性判定：独立问题与同一份最终证据，前提以条件而非已证实事实表达。 */
    C5("C5", "充分性判定", 4_000, 0, 200),
    /** 回答生成：上下文、未经核实前提、最终编号证据、原问题与独立问题分段组装。 */
    C6("C6", "回答生成", 12_000, 0, 200),
    /** 话题摘要：旧摘要与新增可用消息，研究回合使用投影而不是完整报告。 */
    C7("C7", "话题摘要", 2_000, 0, 200),
    /** 记忆抽取：用户消息与上一条助手摘要，不把证据或模型回答转成用户偏好。 */
    C8("C8", "记忆抽取", 1_000, 0, 100),
    /** 研究问题编译：与 C2 同源的上下文、任务前提与已有研究投影，不读原始记忆。 */
    R0("R0", "研究问题编译", 4_000, 0, 200),
    /** 研究规划：冻结简报与编译后的研究参数、探测材料。 */
    R1("R1", "研究规划", 1_500, 0, 200),
    /** 单元格任务：独立研究问题、对象、维度、需求与必要条件，超额需要明确策略。 */
    R2("R2", "单元格任务", 200, 200, 50),
    /** 审核与反证：待核结论与对应证据。 */
    R3("R3", "审核与反证", 1_500, 0, 200),
    /** 报告成文：独立问题、原话、结构化输出要求与已核实单元格。 */
    R4("R4", "报告成文", 500, 500, 100);

    private final String code;
    private final String label;
    private final int targetTokens;
    private final int outputReserveTokens;
    private final int safetyMarginTokens;

    ContextCallSite(String code, String label, int targetTokens, int outputReserveTokens,
                    int safetyMarginTokens) {
        this.code = code;
        this.label = label;
        this.targetTokens = targetTokens;
        this.outputReserveTokens = outputReserveTokens;
        this.safetyMarginTokens = safetyMarginTokens;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 该调用点的目标预算，软目标，不是可发送上限。 */
    public int targetTokens() {
        return targetTokens;
    }

    /** 该调用点默认的输出预留。调用方按实际最大输出覆盖，只能调大不能调小。 */
    public int outputReserveTokens() {
        return outputReserveTokens;
    }

    /** 估算误差与协议包装的安全余量。 */
    public int safetyMarginTokens() {
        return safetyMarginTokens;
    }

    /** 渲染顺序，同时是允许层清单：没列进来的层不允许读取。 */
    public List<ContextLayer> ordering() {
        return switch (this) {
            case C1 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.MESSAGE_SUMMARY,
                    ContextLayer.USER_CONSTRAINT, ContextLayer.CONVERSATION_PREMISE);
            case C2 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.TOPIC_SUMMARY,
                    ContextLayer.MESSAGE_SUMMARY, ContextLayer.RAW_MESSAGE, ContextLayer.MESSAGE_HANDLE,
                    ContextLayer.USER_CONSTRAINT, ContextLayer.CONVERSATION_PREMISE,
                    ContextLayer.WORKING_SET);
            case C3 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.TOPIC_SUMMARY,
                    ContextLayer.MESSAGE_SUMMARY, ContextLayer.RAW_MESSAGE, ContextLayer.MESSAGE_HANDLE);
            case C4 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.WORKING_SET,
                    ContextLayer.USER_CONSTRAINT, ContextLayer.CONVERSATION_PREMISE);
            case C5 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.CONVERSATION_PREMISE,
                    ContextLayer.EVIDENCE);
            case C6 -> List.of(ContextLayer.TOPIC_SUMMARY, ContextLayer.MESSAGE_SUMMARY,
                    ContextLayer.RAW_MESSAGE, ContextLayer.MESSAGE_HANDLE, ContextLayer.USER_CONSTRAINT,
                    ContextLayer.CONVERSATION_PREMISE, ContextLayer.MEMORY_REFERENCE,
                    ContextLayer.EXPRESSION_PARAM, ContextLayer.EVIDENCE);
            case C7 -> List.of(ContextLayer.TOPIC_SUMMARY, ContextLayer.MESSAGE_SUMMARY,
                    ContextLayer.RAW_MESSAGE, ContextLayer.RESEARCH_PROJECTION);
            case C8 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.MESSAGE_SUMMARY,
                    ContextLayer.USER_CONSTRAINT);
            case R0 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.TOPIC_SUMMARY,
                    ContextLayer.MESSAGE_SUMMARY, ContextLayer.MESSAGE_HANDLE, ContextLayer.USER_CONSTRAINT,
                    ContextLayer.CONVERSATION_PREMISE, ContextLayer.RESEARCH_PROJECTION);
            case R1 -> List.of(ContextLayer.RESEARCH_PROJECTION, ContextLayer.EXPRESSION_PARAM,
                    ContextLayer.WORKING_SET);
            case R2 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.RESEARCH_PROJECTION,
                    ContextLayer.CONVERSATION_PREMISE);
            case R3 -> List.of(ContextLayer.RESEARCH_PROJECTION, ContextLayer.EVIDENCE);
            case R4 -> List.of(ContextLayer.CURRENT_INPUT, ContextLayer.RESEARCH_PROJECTION,
                    ContextLayer.EXPRESSION_PARAM, ContextLayer.EVIDENCE);
        };
    }

    /** 允许读取的层，等于渲染顺序的集合。 */
    public Set<ContextLayer> allowedLayers() {
        return Set.copyOf(ordering());
    }

    /** 各层的目标预算。没列出的层目标为 0，只能占用剩余预算。 */
    public Map<ContextLayer, Integer> layerTargets() {
        return switch (this) {
            case C1 -> targets(ContextLayer.CURRENT_INPUT, 300, ContextLayer.MESSAGE_SUMMARY, 600,
                    ContextLayer.USER_CONSTRAINT, 300, ContextLayer.CONVERSATION_PREMISE, 300);
            case C2 -> targets(ContextLayer.CURRENT_INPUT, 400, ContextLayer.TOPIC_SUMMARY, 800,
                    ContextLayer.MESSAGE_SUMMARY, 600, ContextLayer.RAW_MESSAGE, 800,
                    ContextLayer.MESSAGE_HANDLE, 100, ContextLayer.USER_CONSTRAINT, 200,
                    ContextLayer.CONVERSATION_PREMISE, 200, ContextLayer.WORKING_SET, 100);
            case C3 -> targets(ContextLayer.CURRENT_INPUT, 200, ContextLayer.TOPIC_SUMMARY, 400,
                    ContextLayer.MESSAGE_SUMMARY, 400, ContextLayer.RAW_MESSAGE, 800,
                    ContextLayer.MESSAGE_HANDLE, 200);
            case C4 -> targets(ContextLayer.CURRENT_INPUT, 400, ContextLayer.WORKING_SET, 400,
                    ContextLayer.USER_CONSTRAINT, 200, ContextLayer.CONVERSATION_PREMISE, 200);
            case C5 -> targets(ContextLayer.CURRENT_INPUT, 400, ContextLayer.CONVERSATION_PREMISE, 400,
                    ContextLayer.EVIDENCE, 3_000);
            case C6 -> targets(ContextLayer.TOPIC_SUMMARY, 2_000, ContextLayer.MESSAGE_SUMMARY, 2_000,
                    ContextLayer.RAW_MESSAGE, 6_000, ContextLayer.MESSAGE_HANDLE, 400,
                    ContextLayer.USER_CONSTRAINT, 800, ContextLayer.CONVERSATION_PREMISE, 800,
                    ContextLayer.MEMORY_REFERENCE, 600, ContextLayer.EXPRESSION_PARAM, 400,
                    ContextLayer.EVIDENCE, 6_000);
            case C7 -> targets(ContextLayer.TOPIC_SUMMARY, 800, ContextLayer.MESSAGE_SUMMARY, 800,
                    ContextLayer.RAW_MESSAGE, 400, ContextLayer.RESEARCH_PROJECTION, 400);
            case C8 -> targets(ContextLayer.CURRENT_INPUT, 300, ContextLayer.MESSAGE_SUMMARY, 400,
                    ContextLayer.USER_CONSTRAINT, 300);
            case R0 -> targets(ContextLayer.CURRENT_INPUT, 500, ContextLayer.TOPIC_SUMMARY, 800,
                    ContextLayer.MESSAGE_SUMMARY, 600, ContextLayer.MESSAGE_HANDLE, 200,
                    ContextLayer.USER_CONSTRAINT, 400, ContextLayer.CONVERSATION_PREMISE, 400,
                    ContextLayer.RESEARCH_PROJECTION, 1_100);
            case R1 -> targets(ContextLayer.RESEARCH_PROJECTION, 1_500, ContextLayer.EXPRESSION_PARAM, 300,
                    ContextLayer.WORKING_SET, 200);
            case R2 -> targets(ContextLayer.CURRENT_INPUT, 100, ContextLayer.RESEARCH_PROJECTION, 100,
                    ContextLayer.CONVERSATION_PREMISE, 100);
            case R3 -> targets(ContextLayer.RESEARCH_PROJECTION, 500, ContextLayer.EVIDENCE, 1_500);
            case R4 -> targets(ContextLayer.CURRENT_INPUT, 500, ContextLayer.RESEARCH_PROJECTION, 1_000,
                    ContextLayer.EXPRESSION_PARAM, 300, ContextLayer.EVIDENCE, 2_000);
        };
    }

    /** 必带层：预算再紧也不能静默丢弃，丢了要给出明确结果。 */
    public Set<ContextLayer> mandatoryLayers() {
        return switch (this) {
            case C5, C6, R3 -> Set.of(ContextLayer.EVIDENCE);
            case R1, R2 -> Set.of(ContextLayer.RESEARCH_PROJECTION);
            case C1, C2, C3, C4, C7, C8, R0, R4 -> Set.of();
        };
    }

    /** 按编号解析调用点，不认识的值返回 null。 */
    public static ContextCallSite fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        for (ContextCallSite site : values()) {
            if (site.code.equals(normalized)) {
                return site;
            }
        }
        return null;
    }

    private static Map<ContextLayer, Integer> targets(Object... pairs) {
        Map<ContextLayer, Integer> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((ContextLayer) pairs[index], (Integer) pairs[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
