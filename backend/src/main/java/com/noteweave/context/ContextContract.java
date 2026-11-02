package com.noteweave.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 上下文契约：一次调用允许读哪些层、按什么顺序、在什么预算内、冻结了哪些版本。
 *
 * <p>契约只描述渲染与选择规则，不携带正文。冻结状态里不重复保存一份全量记忆，
 * 运行审计记录的是本次实际使用的版本号与编译参数，正文按引用在需要时读取。
 * 契约一旦构造完成即不可变：同一份契约加同一份冻结输入，渲染文本、摘要与 token 估算必须一致。</p>
 *
 * @param schemaVersion       契约结构版本
 * @param compilerVersion     编译实现版本，改动选择规则时必须递增
 * @param callSite            调用点
 * @param workspaceId         工作台身份
 * @param actorId             发起用户身份
 * @param conversationId      会话身份，无会话时为空串
 * @param cutoffSeq           冻结的消息截止序号，无会话时为 0
 * @param allowedLayers       允许读取的层
 * @param ordering            渲染顺序
 * @param targetTokens        目标预算，软目标
 * @param hardLimitTokens     硬上限，等于模型窗口扣除输出预留与安全余量
 * @param outputReserveTokens 输出预留
 * @param safetyMarginTokens  估算误差与包装的安全余量
 * @param estimatorVersion    token 估算方式版本
 * @param frozenVersions      本次实际使用的记忆、摘要、前提与工作集版本
 */
public record ContextContract(
        String schemaVersion,
        String compilerVersion,
        ContextCallSite callSite,
        String workspaceId,
        String actorId,
        String conversationId,
        int cutoffSeq,
        List<ContextLayer> allowedLayers,
        List<ContextLayer> ordering,
        int targetTokens,
        int hardLimitTokens,
        int outputReserveTokens,
        int safetyMarginTokens,
        String estimatorVersion,
        FrozenVersions frozenVersions
) {

    /** 契约结构版本。 */
    public static final String SCHEMA_VERSION = "context-contract-v1";

    public ContextContract {
        allowedLayers = allowedLayers == null ? List.of() : List.copyOf(allowedLayers);
        ordering = ordering == null ? List.of() : List.copyOf(ordering);
        conversationId = conversationId == null ? "" : conversationId;
        workspaceId = workspaceId == null ? "" : workspaceId;
        actorId = actorId == null ? "" : actorId;
        frozenVersions = frozenVersions == null ? FrozenVersions.none() : frozenVersions;
        if (!SCHEMA_VERSION.equals(schemaVersion) || callSite == null
                || blank(compilerVersion) || blank(workspaceId) || blank(actorId)
                || blank(estimatorVersion)) {
            throw new IllegalArgumentException("context contract requires identity and versions");
        }
        if (cutoffSeq < 0 || targetTokens < 1 || hardLimitTokens < 1
                || outputReserveTokens < 0 || safetyMarginTokens < 0) {
            throw new IllegalArgumentException("context contract budget or cutoff is invalid");
        }
        if (targetTokens > hardLimitTokens) {
            throw new IllegalArgumentException("context target must not exceed the hard limit");
        }
        if (!ordering.equals(callSite.ordering())) {
            throw new IllegalArgumentException("context ordering must equal the call site ordering");
        }
        if (!Set.copyOf(allowedLayers).equals(callSite.allowedLayers())) {
            throw new IllegalArgumentException("context allowed layers must equal the call site layers");
        }
        if (conversationId.isBlank() && cutoffSeq != 0) {
            throw new IllegalArgumentException("a conversation cutoff requires a conversation identity");
        }
    }

    /** 按调用点构造契约，渲染顺序与允许层直接取自调用点。 */
    public static ContextContract create(
            ContextCallSite callSite,
            String compilerVersion,
            String workspaceId,
            String actorId,
            String conversationId,
            int cutoffSeq,
            int targetTokens,
            int hardLimitTokens,
            int outputReserveTokens,
            int safetyMarginTokens,
            String estimatorVersion,
            FrozenVersions frozenVersions
    ) {
        return new ContextContract(SCHEMA_VERSION, compilerVersion, callSite, workspaceId, actorId,
                conversationId, cutoffSeq, List.copyOf(callSite.ordering()), callSite.ordering(),
                targetTokens, hardLimitTokens, outputReserveTokens, safetyMarginTokens,
                estimatorVersion, frozenVersions);
    }

    /** 真正可用于输入的 token：硬上限本身就是扣除输出预留与安全余量之后的值。 */
    public int availableTokens() {
        return hardLimitTokens;
    }

    /** 该层是否允许出现在本契约的视图里。 */
    public boolean allows(ContextLayer layer) {
        return allowedLayers.contains(layer);
    }

    /** 越权读取必须显式失败，不能靠下游静默忽略。 */
    public void requireAllows(ContextLayer layer) {
        if (!allows(layer)) {
            throw new IllegalArgumentException(
                    "call site " + callSite.code() + " is not allowed to read layer " + layer.wire());
        }
    }

    /** 该层是否必带。 */
    public boolean isMandatory(ContextLayer layer) {
        return callSite.mandatoryLayers().contains(layer);
    }

    /** 契约身份的稳定摘要，用于快照与审计比对。 */
    public String identitySha256() {
        return sha256(String.join("|",
                schemaVersion, compilerVersion, callSite.code(), workspaceId, actorId,
                conversationId, Integer.toString(cutoffSeq), ordering.toString(),
                Integer.toString(targetTokens), Integer.toString(hardLimitTokens),
                Integer.toString(outputReserveTokens), Integer.toString(safetyMarginTokens),
                estimatorVersion, frozenVersions.toString()));
    }

    /**
     * 冻结版本。字段为空串表示该来源本次不参与，不表示版本未知。
     *
     * @param memoryVersion             实际使用的记忆版本
     * @param summaryVersion            实际使用的摘要版本
     * @param premiseVersion            实际使用的前提抽取版本
     * @param workingSetId              工作集身份
     * @param researchProjectionVersion 研究投影版本
     */
    public record FrozenVersions(
            String memoryVersion,
            String summaryVersion,
            String premiseVersion,
            String workingSetId,
            String researchProjectionVersion
    ) {
        public FrozenVersions {
            memoryVersion = memoryVersion == null ? "" : memoryVersion;
            summaryVersion = summaryVersion == null ? "" : summaryVersion;
            premiseVersion = premiseVersion == null ? "" : premiseVersion;
            workingSetId = workingSetId == null ? "" : workingSetId;
            researchProjectionVersion = researchProjectionVersion == null
                    ? "" : researchProjectionVersion;
        }

        /** 没有任何来源参与的冻结版本。 */
        public static FrozenVersions none() {
            return new FrozenVersions("", "", "", "", "");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
