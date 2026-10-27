package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Rechecks persisted citation origins before exposing a Knowledge version.
 *
 * <p>来源事实统一来自 C1 MaterialEligibilityPort；用途参数区分历史展示与当前证据。
 * 旧签名保持既有 CURRENT_EVIDENCE 语义，调用方逐个迁移，不允许默认放宽为历史展示。</p>
 */
@Component
public class KnowledgeCitationReadGate {
    private final JdbcTemplate jdbc;
    private final MaterialEligibilityPort eligibilityPort;

    public KnowledgeCitationReadGate(JdbcTemplate jdbc,
                                     MaterialEligibilityPort eligibilityPort) {
        this.jdbc = jdbc;
        this.eligibilityPort = eligibilityPort;
    }

    /** 旧签名：当前证据语义，与既有行为一致。 */
    public void requireReadable(String workspaceId, String versionId) {
        requireReadable(workspaceId, versionId, AccessPurpose.CURRENT_EVIDENCE);
    }

    public void requireReadable(String workspaceId, String versionId, AccessPurpose purpose) {
        if (!readableVersionIds(workspaceId, List.of(versionId), purpose).contains(versionId)) {
            throw new BusinessException("KNOWLEDGE_SOURCE_REVOKED",
                    "知识版本引用的资料已撤销", HttpStatus.CONFLICT);
        }
    }

    public void requireCitationIdsReadable(String workspaceId, List<String> citationIds) {
        List<String> ids = citationIds.stream().distinct().toList();
        if (ids.isEmpty()) return;
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String> sourceIds = jdbc.queryForList("""
                select source_id from citation
                where workspace_id = ? and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                String.class, parameters.toArray());
        if (sourceIds.size() != ids.size()) {
            throw new BusinessException("KNOWLEDGE_CITATION_REVOKED",
                    "知识引用的资料已撤销或不属于当前工作台", HttpStatus.CONFLICT);
        }
        Map<String, EligibilityDecision> decisions = eligibilityPort.assess(
                workspaceId,
                sourceIds.stream().filter(id -> id != null && !id.isBlank())
                        .map(OriginRef::internalSource).toList(),
                AccessPurpose.CURRENT_EVIDENCE);
        for (String sourceId : sourceIds) {
            EligibilityDecision decision = decisions.get(OriginRef.internalSource(sourceId).originKey());
            if (decision == null || !decision.evidenceEligible()) {
                throw new BusinessException("KNOWLEDGE_CITATION_REVOKED",
                        "知识引用的资料已撤销或不属于当前工作台", HttpStatus.CONFLICT);
            }
        }
    }

    /** 旧签名：当前证据语义，与既有行为一致。 */
    public Set<String> readableVersionIds(String workspaceId, List<String> versionIds) {
        return readableVersionIds(workspaceId, versionIds, AccessPurpose.CURRENT_EVIDENCE);
    }

    /**
     * 按用途批量判定版本可读性：版本引用的全部来源都满足该用途才算可读。
     * 检索结果中个别来源失效时由调用方做局部过滤；这里不做静默丢弃。
     */
    public Set<String> readableVersionIds(String workspaceId, List<String> versionIds, AccessPurpose purpose) {
        Map<String, List<EligibilityDecision>> availability =
                versionSourceAvailability(workspaceId, versionIds);
        Set<String> derivedWithoutCitations = artifactDerivedVersionIds(workspaceId, availability);
        LinkedHashSet<String> readableVersions = new LinkedHashSet<>();
        availability.forEach((versionId, decisions) -> {
            if (decisions.isEmpty()) {
                // KB-01：没有引用来源时不能无条件放行。人工笔记本来就没有来源依赖，照常可读；
                // 产物写回的内容必须有来源血缘，缺引用说明血缘已断，按不可读处理。
                if (!derivedWithoutCitations.contains(versionId)) {
                    readableVersions.add(versionId);
                }
                return;
            }
            boolean allAllowed = decisions.stream().allMatch(decision -> decision.allowed(purpose));
            if (allAllowed) {
                readableVersions.add(versionId);
            }
        });
        return Set.copyOf(readableVersions);
    }

    /** 没有引用来源且声明了来源依赖（产物写回）的版本；这些版本不能默认放行。 */
    private Set<String> artifactDerivedVersionIds(
            String workspaceId,
            Map<String, List<EligibilityDecision>> availability
    ) {
        List<String> versionsWithoutCitations = availability.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (versionsWithoutCitations.isEmpty()) return Set.of();
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(versionsWithoutCitations);
        return Set.copyOf(jdbc.queryForList("""
                select v.id
                from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where i.workspace_id = ? and i.origin = 'ARTIFACT_SOURCED'
                  and v.id in (%s)
                """.formatted(String.join(",",
                        Collections.nCopies(versionsWithoutCitations.size(), "?"))),
                String.class, parameters.toArray()));
    }

    /** 每个版本引用来源的资格事实；仅供展示与原因说明，不作为放行依据。 */
    public Map<String, List<EligibilityDecision>> versionSourceAvailability(
            String workspaceId,
            List<String> versionIds
    ) {
        List<String> ids = versionIds.stream().distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, Set<String>> sourcesByVersion = new LinkedHashMap<>();
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String> ownedIds = jdbc.queryForList("""
                select v.id from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where i.workspace_id = ? and v.id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                String.class, parameters.toArray());
        jdbc.query("""
                select kvc.knowledge_version_id, c.source_id
                from knowledge_version_citation kvc
                join knowledge_version v on v.id = kvc.knowledge_version_id
                join knowledge_item i on i.id = v.item_id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and kvc.knowledge_version_id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs ->
                        sourcesByVersion.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>())
                                .add(rs.getString(2)), parameters.toArray());
        List<String> sourceIds = sourcesByVersion.values().stream().flatMap(Set::stream)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, EligibilityDecision> decisions = sourceIds.isEmpty() ? Map.of()
                : eligibilityPort.assess(workspaceId,
                        sourceIds.stream().map(OriginRef::internalSource).toList(),
                        AccessPurpose.CURRENT_EVIDENCE);
        Map<String, List<EligibilityDecision>> result = new LinkedHashMap<>();
        for (String versionId : ownedIds) {
            List<EligibilityDecision> versionDecisions = new ArrayList<>();
            for (String sourceId : sourcesByVersion.getOrDefault(versionId, Set.of())) {
                EligibilityDecision decision = sourceId == null || sourceId.isBlank() ? null
                        : decisions.get(OriginRef.internalSource(sourceId).originKey());
                if (decision != null) {
                    versionDecisions.add(decision);
                }
            }
            result.put(versionId, List.copyOf(versionDecisions));
        }
        return result;
    }

    public Set<String> readableWikiItemIds(String workspaceId, List<String> itemIds) {
        List<String> ids = itemIds.stream().filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (ids.isEmpty()) return Set.of();
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        Map<String, String> versionsByItem = new LinkedHashMap<>();
        jdbc.query("""
                select id, latest_version_id from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                  and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs -> versionsByItem.put(rs.getString(1), rs.getString(2)),
                parameters.toArray());
        Set<String> readableVersions = readableVersionIds(workspaceId,
                versionsByItem.values().stream().filter(id -> id != null && !id.isBlank()).toList(),
                AccessPurpose.CURRENT_EVIDENCE);
        LinkedHashSet<String> readableItems = new LinkedHashSet<>();
        versionsByItem.forEach((itemId, versionId) -> {
            if (readableVersions.contains(versionId)) readableItems.add(itemId);
        });
        return Set.copyOf(readableItems);
    }

    /** 当前证据用途下各来源的资格事实，供检索路径局部过滤与原因展示。 */
    public Map<String, EligibilityDecision> sourceAvailability(String workspaceId, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return new HashMap<>(eligibilityPort.assess(workspaceId,
                ids.stream().map(OriginRef::internalSource).toList(),
                AccessPurpose.CURRENT_EVIDENCE));
    }
}
