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
final class MemorySourceReadGate {

    private static final Logger log = LoggerFactory.getLogger(MemorySourceReadGate.class);

    /**
     * 表示来源资料的 source_type。
     * CONVERSATION、USER_FEEDBACK、MODEL_INFERENCE、CONVERSATION_FEEDBACK、
     * ARTIFACT_FEEDBACK、PROJECT_DECISION 不属于来源资料，不做 source 表校验。
     */
    private static final Set<String> MATERIAL_SOURCE_TYPES = Set.of("SOURCE", "WIKI");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean schemaUnavailable;

    MemorySourceReadGate(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回因为来源不可读而需要从读路径排除的 revision id。
     * 没有来源绑定的 revision 不在结果里。
     */
    Set<String> staleRevisionIds(String workspaceId, Collection<String> revisionIds) {
        if (schemaUnavailable || revisionIds == null || revisionIds.isEmpty()) {
            return Set.of();
        }
        try {
            return loadStaleRevisionIds(workspaceId, new LinkedHashSet<>(revisionIds));
        } catch (BadSqlGrammarException exception) {
            // 精简测试库或旧库可能还没有来源绑定表；此时没有来源可校验，保持原有读取行为。
            schemaUnavailable = true;
            log.warn("Memory 来源读时校验已禁用：缺少来源绑定表或列：{}", exception.getMessage());
            return Set.of();
        }
    }

    private Set<String> loadStaleRevisionIds(String workspaceId, Set<String> revisionIds) {
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
                            }
                        }
                    }
                }
            }
        }
        if (sourceIdsByRevision.isEmpty()) {
            return Set.of();
        }

        Set<String> allSourceIds = new LinkedHashSet<>();
        sourceIdsByRevision.values().forEach(allSourceIds::addAll);
        Map<String, String> statusBySource = loadSourceStatuses(workspaceId, allSourceIds);
        Set<String> staleRevisionIds = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : sourceIdsByRevision.entrySet()) {
            // 全部来源都不在 source 表时按外部来源处理，避免把 web-... 这类引用误判为已删除
            boolean hasKnownSource = entry.getValue().stream()
                    .anyMatch(statusBySource::containsKey);
            boolean readable = entry.getValue().stream()
                    .filter(statusBySource::containsKey)
                    .allMatch(sourceId -> "READY".equals(statusBySource.get(sourceId)));
            if (hasKnownSource && !readable) {
                staleRevisionIds.add(entry.getKey());
            }
        }
        return staleRevisionIds;
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
