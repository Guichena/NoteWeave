package com.noteweave.context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话工作集：冻结时从最近回答清单、显式来源选择和研究运行派生出的有界引用集合。
 *
 * <p>工作集不是新的权威表，也不携带正文。它只给选择线索：显式资料范围、通道关闭与当前权限
 * 优先于任何会话信号；研究对象与维度定位只提供选择线索，不能把相关网页解释成该格已核实。</p>
 *
 * <p>条目记录贡献回合、身份、版本与权重。删除回合只去掉它的贡献，同一资料仍被其他有效回合
 * 使用时可以保留。</p>
 */
public record ConversationWorkingSet(
        String schemaVersion,
        String workspaceId,
        String conversationId,
        int cutoffSeq,
        Limits limits,
        List<Entry> entries,
        List<Exclusion> exclusions,
        String derivationVersion
) {

    /** 工作集结构版本。 */
    public static final String SCHEMA_VERSION = "conversation-working-set-v1";

    public ConversationWorkingSet {
        limits = limits == null ? Limits.defaults() : limits;
        entries = entries == null ? List.of() : List.copyOf(entries);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        conversationId = conversationId == null ? "" : conversationId;
        if (!SCHEMA_VERSION.equals(schemaVersion) || blank(workspaceId) || cutoffSeq < 0
                || blank(derivationVersion)) {
            throw new IllegalArgumentException("working set envelope is invalid");
        }
        Set<String> keys = new HashSet<>();
        int sources = 0;
        int knowledgePages = 0;
        int researchRuns = 0;
        int citedEvidence = 0;
        for (Entry entry : entries) {
            if (!keys.add(entry.kind() + ":" + entry.refId())) {
                throw new IllegalArgumentException("working set entry is duplicated");
            }
            switch (entry.kind()) {
                case SOURCE -> sources++;
                case KNOWLEDGE_PAGE -> knowledgePages++;
                case RESEARCH_RUN -> researchRuns++;
                case CITED_EVIDENCE -> citedEvidence++;
            }
        }
        if (sources > limits.sources() || knowledgePages > limits.knowledgePages()
                || researchRuns > limits.researchRuns() || citedEvidence > limits.citedEvidence()) {
            throw new IllegalArgumentException("working set exceeds its limits");
        }
    }

    /** 某类条目的数量。 */
    public int count(EntryKind kind) {
        return (int) entries.stream().filter(entry -> entry.kind() == kind).count();
    }

    /** 条目类型。 */
    public enum EntryKind {
        /** 工作台资料。 */
        SOURCE("source"),
        /** 知识页。 */
        KNOWLEDGE_PAGE("knowledge_page"),
        /** 研究运行。 */
        RESEARCH_RUN("research_run"),
        /** 最近引用证据。 */
        CITED_EVIDENCE("cited_evidence");

        private final String wire;

        EntryKind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * 工作集条目。
     *
     * @param kind                    条目类型
     * @param refId                   引用身份
     * @param versionId               引用版本，可为空串
     * @param weight                  会话信号权重，取值 0 到 1
     * @param contributingMessageIds  贡献该条目的消息身份
     * @param firstSeq                最早贡献序号
     * @param lastSeq                 最晚贡献序号
     */
    public record Entry(EntryKind kind, String refId, String versionId, double weight,
                        List<String> contributingMessageIds, int firstSeq, int lastSeq) {
        public Entry {
            contributingMessageIds = contributingMessageIds == null
                    ? List.of() : List.copyOf(contributingMessageIds);
            versionId = versionId == null ? "" : versionId;
            if (kind == null || blank(refId) || !(weight >= 0.0d && weight <= 1.0d)
                    || firstSeq < 0 || lastSeq < 0 || (lastSeq != 0 && lastSeq < firstSeq)) {
                throw new IllegalArgumentException("working set entry is invalid");
            }
        }
    }

    /**
     * 未进入工作集的候选及原因。
     *
     * @param kind   条目类型
     * @param refId  引用身份
     * @param reason 稳定原因码
     */
    public record Exclusion(EntryKind kind, String refId, String reason) {
        public Exclusion {
            if (kind == null || blank(refId) || blank(reason)) {
                throw new IllegalArgumentException("working set exclusion requires a reason");
            }
        }
    }

    /**
     * 初始上限，沿用架构设计第 9 节的默认值。渲染与检索还要受各自总预算限制。
     *
     * @param sources        资料上限
     * @param knowledgePages 知识页上限
     * @param researchRuns   研究运行上限
     * @param citedEvidence  最近引用证据上限
     */
    public record Limits(int sources, int knowledgePages, int researchRuns, int citedEvidence) {
        public Limits {
            if (sources < 0 || knowledgePages < 0 || researchRuns < 0 || citedEvidence < 0) {
                throw new IllegalArgumentException("working set limits cannot be negative");
            }
        }

        /** 默认上限：资料 20、知识页 20、研究 3、最近引用证据 30。 */
        public static Limits defaults() {
            return new Limits(20, 20, 3, 30);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
