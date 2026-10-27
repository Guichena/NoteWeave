package com.noteweave.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Memory 读路径的来源可读性校验。
 *
 * <p>条目本身不直接保存 source_id，来源绑定沿着
 * memory_item -> memory_runtime_revision -> memory_candidate -> memory_signal 查找。
 * 只有 source_type 表示来源资料的信号才需要回查 source 表；对话反馈、模型推断和产物反馈
 * 的 source_id 不是 source 主键，不能拿来做来源可读性判断。
 *
 * <p>查询全部按批处理：revision、candidate、signal、source 各一次，不按条目逐条查询。
 * 读路径只排除不可读条目，不修改数据库记录。
 */
@org.springframework.stereotype.Component
class MemorySourceReadGate {

    private static final Logger log = LoggerFactory.getLogger(MemorySourceReadGate.class);

    /**
     * 表示来源资料的 source_type。
     * CONVERSATION、USER_FEEDBACK、MODEL_INFERENCE、CONVERSATION_FEEDBACK、
     * ARTIFACT_FEEDBACK、PROJECT_DECISION 不属于来源资料，不做 source 表校验。
     */
    private static final Set<String> MATERIAL_SOURCE_TYPES = Set.of("SOURCE", "WIKI");

    /** MEM-06：单批判定的 revision id 上限，避免超长 IN 列表。 */
    private static final int REVISION_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<
            com.noteweave.source.eligibility.MaterialEligibilityPort> eligibilityPort;

    /** 直连构造（测试/过渡）：无 C1 端口时使用本类内置的来源状态查询。 */
    MemorySourceReadGate(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    /** 生产装配：C1 端口由 A 的评估器实现；判据以端口为准，不再各查一套。 */
    @org.springframework.beans.factory.annotation.Autowired
    MemorySourceReadGate(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<
                    com.noteweave.source.eligibility.MaterialEligibilityPort> eligibilityPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eligibilityPort = eligibilityPort;
    }

    /**
     * MEM-05：排除原因码。把「暂不支持」与「不可读」分开，用户/管理端能看出为什么召回不到：
     * Wiki 引用是功能尚未接通（会随 C1 端口补齐而消失），来源未就绪/不可作依据是真实状态。
     */
    static final String REASON_WIKI_NOT_SUPPORTED = "WIKI_SOURCE_NOT_SUPPORTED";
    static final String REASON_SOURCE_NOT_READY = "SOURCE_NOT_READY";
    static final String REASON_SOURCE_NOT_ELIGIBLE = "SOURCE_NOT_ELIGIBLE";

    static String reasonText(String reasonCode) {
        if (reasonCode == null) {
            return "记忆暂不可召回";
        }
        return switch (reasonCode) {
            case REASON_WIKI_NOT_SUPPORTED -> "记忆引用的是 Wiki 页面，知识项读取语义尚未接通，暂不支持召回";
            case REASON_SOURCE_NOT_READY -> "记忆引用的资料已删除或尚未解析就绪，暂不可读";
            case REASON_SOURCE_NOT_ELIGIBLE -> "记忆引用的资料当前不可作为事实依据，暂不可读";
            default -> "记忆暂不可召回";
        };
    }

    /** 返回因为来源不可读而需要从读路径排除的 revision id。 */
    Set<String> staleRevisionIds(String workspaceId, Collection<String> revisionIds) {
        return exclusionReasons(workspaceId, revisionIds).keySet();
    }

    /**
     * 返回「需要从读路径排除的 revision id → 原因码」。没有来源绑定的 revision 不在结果里。
     *
     * <p>B-06：删除了「一次 SQL 语法异常后全实例永久放行」的绕过。查询失败按
     * 明确的服务不可用向上抛出，由调用方决定降级展示；纯用户偏好（无来源绑定）
     * 不经过这里，不受影响。测试库缺少来源绑定表时应使用专用 fixture/stub。
     */
    Map<String, String> exclusionReasons(String workspaceId, Collection<String> revisionIds) {
        if (revisionIds == null || revisionIds.isEmpty()) {
            return Map.of();
        }
        // MEM-06：分批判定，避免一次传入上万 revision id 生成超长 IN 列表。
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(revisionIds));
        Map<String, String> reasons = new LinkedHashMap<>();
        try {
            for (int start = 0; start < ids.size(); start += REVISION_BATCH_SIZE) {
                List<String> chunk = ids.subList(start, Math.min(ids.size(), start + REVISION_BATCH_SIZE));
                reasons.putAll(loadExclusionReasons(workspaceId, new LinkedHashSet<>(chunk)));
            }
            return reasons;
        } catch (BadSqlGrammarException | IllegalStateException exception) {
            // 一次失败不能让整个实例永久跳过来源校验；明确失败并保留原始异常。
            // C1 端口故障同样按服务不可用上抛，不降级放行（FB-05）。
            throw new IllegalStateException("MEMORY_SOURCE_GATE_UNAVAILABLE", exception);
        }
    }

    private Map<String, String> loadExclusionReasons(String workspaceId, Set<String> revisionIds) {
        List<RevisionRef> revisions = jdbcTemplate.query("""
                select r.id as revision_id, r.memory_item_id,
                       r.provenance_type, r.provenance_ref
                from memory_runtime_revision r
                join memory_item i on i.id = r.memory_item_id
                where i.workspace_id = ? and r.id in (%s)
                """.formatted(placeholders(revisionIds)),
                (rs, rowNum) -> new RevisionRef(
                        rs.getString("revision_id"),
                        rs.getString("memory_item_id"),
                        rs.getString("provenance_type"),
                        rs.getString("provenance_ref")),
                parameters(workspaceId, revisionIds));

        Map<String, Set<String>> sourceIdsByRevision = new LinkedHashMap<>();
        Map<String, String> sourceKinds = new HashMap<>();
        Set<String> candidateIds = new LinkedHashSet<>();
        Set<String> legacyVersionIds = new LinkedHashSet<>();
        for (RevisionRef revision : revisions) {
            String provenanceType = MemorySignalService.normalizeToken(revision.provenanceType());
            String reference = revision.provenanceRef();
            // 兼容直接以来源类型和来源 id 记录 provenance 的 Revision。
            if (MATERIAL_SOURCE_TYPES.contains(provenanceType)
                    && reference != null && !reference.isBlank()) {
                sourceIdsByRevision
                        .computeIfAbsent(revision.revisionId(), ignored -> new LinkedHashSet<>())
                        .add(reference);
                sourceKinds.putIfAbsent(reference, provenanceType);
            }
            if (reference == null || reference.isBlank()) {
                continue;
            }
            if ("MEMORY_CANDIDATE".equals(provenanceType)) {
                candidateIds.add(reference);
            } else if ("LEGACY_MEMORY_VERSION".equals(provenanceType)) {
                legacyVersionIds.add(reference);
            }
        }
        Map<String, String> legacyCandidateByVersion = legacyVersionIds.isEmpty()
                ? Map.of()
                : loadLegacyCandidateIds(workspaceId, legacyVersionIds);
        candidateIds.addAll(legacyCandidateByVersion.values());
        if (!candidateIds.isEmpty()) {
            Map<String, List<String>> signalIdsByCandidate = loadCandidateSignalIds(workspaceId, candidateIds);
            Set<String> signalIds = new LinkedHashSet<>();
            signalIdsByCandidate.values().forEach(signalIds::addAll);
            if (!signalIds.isEmpty()) {
                Map<String, SignalSource> sourceBySignal = loadSignalSources(workspaceId, signalIds);
                for (RevisionRef revision : revisions) {
                    Set<String> revisionCandidateIds = new LinkedHashSet<>();
                    String provenanceType = MemorySignalService.normalizeToken(revision.provenanceType());
                    String reference = revision.provenanceRef();
                    if ("MEMORY_CANDIDATE".equals(provenanceType) && reference != null) {
                        revisionCandidateIds.add(reference);
                    } else if ("LEGACY_MEMORY_VERSION".equals(provenanceType) && reference != null) {
                        String candidateId = legacyCandidateByVersion.get(reference);
                        if (candidateId != null) {
                            revisionCandidateIds.add(candidateId);
                        }
                    }
                    for (String candidateId : revisionCandidateIds) {
                        for (String signalId : signalIdsByCandidate.getOrDefault(candidateId, List.of())) {
                            SignalSource source = sourceBySignal.get(signalId);
                            if (source != null && source.material()) {
                                sourceIdsByRevision
                                        .computeIfAbsent(revision.revisionId(),
                                                ignored -> new LinkedHashSet<>())
                                        .add(source.sourceId());
                                sourceKinds.putIfAbsent(source.sourceId(), source.sourceType());
                            }
                        }
                    }
                }
            }
        }
        if (sourceIdsByRevision.isEmpty()) {
            return Map.of();
        }

        com.noteweave.source.eligibility.MaterialEligibilityPort port =
                eligibilityPort == null ? null : eligibilityPort.getIfAvailable();
        if (port != null) {
            return judgeByEligibilityPort(workspaceId, sourceIdsByRevision, sourceKinds, port);
        }
        Set<String> allSourceIds = new LinkedHashSet<>();
        sourceIdsByRevision.values().forEach(allSourceIds::addAll);
        Map<String, String> statusBySource = loadSourceStatuses(workspaceId, allSourceIds);
        Map<String, String> exclusionReasons = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : sourceIdsByRevision.entrySet()) {
            // B-06：进入本集合的来源全部来自内部来源绑定（SOURCE/WIKI 类型信号或 provenance）。
            // 内部来源在 source 表中不存在属于缺失内部来源（MISSING_INTERNAL），不能按外部来源放行；
            // 只有全部来源都存在且为 READY 才可读。
            boolean readable = !entry.getValue().isEmpty() && entry.getValue().stream()
                    .allMatch(sourceId -> "READY".equals(statusBySource.get(sourceId)));
            if (!readable) {
                exclusionReasons.put(entry.getKey(), REASON_SOURCE_NOT_READY);
            }
        }
        return exclusionReasons;
    }

    /**
     * FB-05 / C1：判据经 MaterialEligibilityPort（A 的评估器）批量评估，用途
     * CURRENT_EVIDENCE，不在 Memory 侧保留另一套生产判定。生成来源的传递依赖
     * 由 A 的评估器闭包处理；端口故障按服务不可用上抛（fail-closed）。
     * WIKI 型 provenance 引用暂按不可读排除（保守）：C1 端口尚未表达知识项
     * 治理语义，已向 A 提交扩展请求；只读诊断 SQL 见交接包 findings-disposition。
     * MEM-05：这类排除带 {@link #REASON_WIKI_NOT_SUPPORTED}，与真实不可读区分开。
     */
    private Map<String, String> judgeByEligibilityPort(
            String workspaceId,
            Map<String, Set<String>> sourceIdsByRevision,
            Map<String, String> sourceKinds,
            com.noteweave.source.eligibility.MaterialEligibilityPort port) {
        List<com.noteweave.source.eligibility.OriginRef> origins = new ArrayList<>();
        Map<String, String> kinds = sourceKinds == null ? Map.of() : sourceKinds;
        Set<String> wikiRefs = new LinkedHashSet<>();
        for (Set<String> ids : sourceIdsByRevision.values()) {
            for (String id : ids) {
                if ("WIKI".equals(kinds.get(id))) {
                    wikiRefs.add(id);
                } else {
                    origins.add(com.noteweave.source.eligibility.OriginRef.internalSource(id));
                }
            }
        }
        Map<String, com.noteweave.source.eligibility.EligibilityDecision> decisions = new HashMap<>();
        try {
            for (int start = 0; start < origins.size();
                    start += com.noteweave.source.eligibility.MaterialEligibilityPort.MAX_ORIGINS_PER_ASSESS) {
                int end = Math.min(origins.size(),
                        start + com.noteweave.source.eligibility.MaterialEligibilityPort.MAX_ORIGINS_PER_ASSESS);
                decisions.putAll(port.assess(workspaceId, origins.subList(start, end),
                        com.noteweave.source.eligibility.AccessPurpose.CURRENT_EVIDENCE));
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("MEMORY_SOURCE_GATE_PORT_FAILED", failure);
        }
        Map<String, String> exclusionReasons = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : sourceIdsByRevision.entrySet()) {
            boolean hasWikiRef = false;
            boolean ineligible = entry.getValue().isEmpty();
            for (String sourceId : entry.getValue()) {
                if ("WIKI".equals(kinds.get(sourceId))) {
                    hasWikiRef = true;
                    continue;
                }
                com.noteweave.source.eligibility.EligibilityDecision decision =
                        decisions.get(com.noteweave.source.eligibility.OriginRef.internalSource(sourceId).originKey());
                if (decision == null
                        || !decision.allowed(com.noteweave.source.eligibility.AccessPurpose.CURRENT_EVIDENCE)) {
                    ineligible = true;
                }
            }
            // MEM-05：真实不可用优先于「暂不支持」。一条 revision 同时引用不可用资料与 Wiki 页面时，
            // 报 SOURCE_NOT_ELIGIBLE——那是用户现在就该处理的；Wiki 语义接通后这条也会自动恢复。
            if (ineligible) {
                exclusionReasons.put(entry.getKey(), REASON_SOURCE_NOT_ELIGIBLE);
            } else if (hasWikiRef) {
                exclusionReasons.put(entry.getKey(), REASON_WIKI_NOT_SUPPORTED);
            }
        }
        if (!wikiRefs.isEmpty()) {
            log.warn("Memory 读路径存在 WIKI 型来源绑定，当前按保守排除（等待 C1 端口知识项语义）: count={}",
                    wikiRefs.size());
        }
        return exclusionReasons;
    }

    private Map<String, String> loadLegacyCandidateIds(String workspaceId, Set<String> versionIds) {
        Map<String, String> candidateByVersion = new HashMap<>();
        try {
            jdbcTemplate.query("""
                    select id, created_from_candidate_id
                    from memory_version
                    where workspace_id = ? and id in (%s)
                    """.formatted(placeholders(versionIds)),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String candidateId = rs.getString("created_from_candidate_id");
                        if (candidateId != null && !candidateId.isBlank()) {
                            candidateByVersion.put(rs.getString("id"), candidateId);
                        }
                    },
                    parameters(workspaceId, versionIds));
        } catch (BadSqlGrammarException exception) {
            // 旧库可能没有 memory_version 表；只放弃旧版本链路，不影响候选链路的来源校验。
            log.warn("Memory 来源读时校验无法读取 memory_version：{}", exception.getMessage());
        }
        return candidateByVersion;
    }

    private Map<String, List<String>> loadCandidateSignalIds(String workspaceId, Set<String> candidateIds) {
        Map<String, List<String>> signalIdsByCandidate = new HashMap<>();
        jdbcTemplate.query("""
                select id, created_from_signal_ids_json
                from memory_candidate
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(candidateIds)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> signalIdsByCandidate.put(
                        rs.getString("id"),
                        readStringList(rs.getString("created_from_signal_ids_json"))),
                parameters(workspaceId, candidateIds));
        return signalIdsByCandidate;
    }

    private Map<String, SignalSource> loadSignalSources(String workspaceId, Set<String> signalIds) {
        Map<String, SignalSource> sourceBySignal = new HashMap<>();
        jdbcTemplate.query("""
                select id, source_type, source_id
                from memory_signal
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(signalIds)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> sourceBySignal.put(
                        rs.getString("id"),
                        new SignalSource(
                                MemorySignalService.normalizeToken(rs.getString("source_type")),
                                rs.getString("source_id"))),
                parameters(workspaceId, signalIds));
        return sourceBySignal;
    }

    private Map<String, String> loadSourceStatuses(String workspaceId, Set<String> sourceIds) {
        Map<String, String> statusBySource = new HashMap<>();
        jdbcTemplate.query("""
                select id, status
                from source
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(sourceIds)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        statusBySource.put(rs.getString("id"), rs.getString("status")),
                parameters(workspaceId, sourceIds));
        return statusBySource;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String placeholders(Collection<String> ids) {
        return String.join(",", ids.stream().map(ignored -> "?").toList());
    }

    private static Object[] parameters(String workspaceId, Collection<String> ids) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        return parameters.toArray();
    }

    private record RevisionRef(
            String revisionId,
            String memoryItemId,
            String provenanceType,
            String provenanceRef
    ) {
    }

    private record SignalSource(String sourceType, String sourceId) {
        private boolean material() {
            return sourceType != null
                    && MATERIAL_SOURCE_TYPES.contains(sourceType)
                    && sourceId != null
                    && !sourceId.isBlank();
        }
    }
}
