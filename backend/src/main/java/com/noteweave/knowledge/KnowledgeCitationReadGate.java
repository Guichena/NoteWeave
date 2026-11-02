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

    /** 生成准入：完整版本身份、当前版本及全部来源绑定快照必须仍然有效。 */
    public void requireCurrentVersionEligible(String workspaceId,String itemId,String versionId) {
        Integer current=jdbc.queryForObject("""
                select count(*) from knowledge_item i join knowledge_version v on v.id=i.latest_version_id
                where i.workspace_id=? and i.id=? and v.id=? and v.item_id=i.id and i.status='ACTIVE'
                  and (i.origin is null or i.origin<>'SYSTEM_NAVIGATION')
                """,Integer.class,workspaceId,itemId,versionId);
        if(current==null || current!=1) throw revoked("知识页面或版本不再是当前有效证据");
        String pageState = jdbc.queryForObject("select wiki_page_state from knowledge_item where workspace_id=? and id=?", String.class, workspaceId, itemId);
        if (!"MANUAL".equals(pageState)) {
            throw new BusinessException("KNOWLEDGE_PROVENANCE_UNVERIFIED",
                    "自动知识版本缺少可信完整依赖标记，不能作为当前生成证据", HttpStatus.CONFLICT);
        }
        List<String> citations=jdbc.queryForList("select citation_id from knowledge_version_citation where knowledge_version_id=? order by sort_order,id",String.class,versionId);
        // 本期不伪造旧自动页的完整依赖；资料编译型Wiki至少必须有来源身份。
        if(citations.isEmpty()) throw revoked("知识版本缺少可验证依赖");
        requireCitationIdsCurrent(workspaceId,citations);
    }

    public void requireCitationIdsCurrent(String workspaceId,List<String> citationIds) {
        for(String id:citationIds.stream().distinct().toList()) {
            List<String[]> origins=jdbc.query("select source_id,source_snapshot_id,research_run_id,research_evidence_key from citation where workspace_id=? and id=?",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},workspaceId,id);
            if(origins.size()!=1) throw revoked("引用不存在或不属于当前工作台");
            String[] origin=origins.get(0);
            if(origin[0]!=null && !origin[0].isBlank()) {
                if(origin[1]==null || origin[1].isBlank()) throw revoked("引用缺少绑定快照");
                OriginRef ref = OriginRef.internalSnapshot(origin[0], origin[1]);
                var decision = eligibilityPort.assess(workspaceId, List.of(ref), AccessPurpose.CURRENT_EVIDENCE).get(ref.originKey());
                if (decision == null || !decision.evidenceEligible() || !origin[1].equals(decision.snapshotId()))
                    throw revoked("引用绑定快照失效或资格结果缺失");
            } else if(origin[2]!=null && !origin[2].isBlank() && origin[3]!=null && !origin[3].isBlank()) {
                Integer valid=jdbc.queryForObject("""
                        select count(*) from research_run r join source_evidence e on e.research_run_id=r.id
                        left join run_input_snapshot s on s.id=r.context_snapshot_id
                        where r.workspace_id=? and r.id=? and r.status='COMPLETED' and e.evidence_key=?
                          and (s.id is null or s.replay_availability='FULL')
                        """,Integer.class,workspaceId,origin[2],origin[3]);
                if(valid==null || valid!=1 || !eligibilityPort.assessGeneratedDependenciesDetailed(workspaceId,"research_agent",origin[2],AccessPurpose.CURRENT_EVIDENCE).allAllowed(AccessPurpose.CURRENT_EVIDENCE))
                    throw revoked("研究引用身份或依赖已失效");
            } else throw revoked("引用缺少合法来源身份");
        }
    }

    private BusinessException revoked(String reason) {
        return new BusinessException("KNOWLEDGE_CITATION_REVOKED",reason,HttpStatus.CONFLICT);
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
        List<String> sourceIds = new ArrayList<>();
        Set<String> researchRunIds = new LinkedHashSet<>();
        int[] found = {0};
        jdbc.query("""
                select source_id, research_run_id from citation
                where workspace_id = ? and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs -> {
                    found[0]++;
                    String researchRunId = rs.getString("research_run_id");
                    // 研究报告引用没有工作台资料，按所属研究报告的依赖闭包校验
                    if (rs.getString("source_id") == null && researchRunId != null && !researchRunId.isBlank()) {
                        researchRunIds.add(researchRunId);
                    } else {
                        sourceIds.add(rs.getString("source_id"));
                    }
                }, parameters.toArray());
        if (found[0] != ids.size()) {
            throw new BusinessException("KNOWLEDGE_CITATION_REVOKED",
                    "知识引用的资料已撤销或不属于当前工作台", HttpStatus.CONFLICT);
        }
        for (String researchRunId : researchRunIds) {
            if (!eligibilityPort.assessGeneratedDependenciesDetailed(
                    workspaceId, "research_agent", researchRunId, AccessPurpose.CURRENT_EVIDENCE)
                    .allAllowed(AccessPurpose.CURRENT_EVIDENCE)) {
                throw new BusinessException("KNOWLEDGE_CITATION_REVOKED",
                        "知识引用的研究报告依赖的资料已撤销", HttpStatus.CONFLICT);
            }
        }
        if (sourceIds.isEmpty()) {
            return;
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
