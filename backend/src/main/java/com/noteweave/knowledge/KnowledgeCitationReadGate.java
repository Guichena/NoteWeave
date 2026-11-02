package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.EligibilityStatus;
import com.noteweave.source.eligibility.MaterialEligibilityPolicy;
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
public class KnowledgeCitationReadGate implements com.noteweave.answer.generation.KnowledgeVersionEligibility {
    /**
     * 当前受支持的 Wiki 自动页编译策略版本。只有发布时写入该标记、且全部依赖仍然合法的自动页，
     * 才能作为当前生成证据；旧自动页没有标记时保持未认证，不会被默认放行。
     */
    public static final String WIKI_PROVENANCE_SCHEMA_VERSION = "wiki-page-provenance-v1";
    public static final Set<String> SUPPORTED_PROVENANCE_SCHEMA_VERSIONS =
            Set.of(WIKI_PROVENANCE_SCHEMA_VERSION);
    /** 导航目录页的 origin；目录只做导航，不进入事实检索、PPR 证据集合或生成契约。 */
    public static final String SYSTEM_NAVIGATION_ORIGIN = "SYSTEM_NAVIGATION";

    private final JdbcTemplate jdbc;
    private final MaterialEligibilityPort eligibilityPort;

    public KnowledgeCitationReadGate(JdbcTemplate jdbc,
                                     MaterialEligibilityPort eligibilityPort) {
        this.jdbc = jdbc;
        this.eligibilityPort = eligibilityPort;
    }

    /** 生成准入：完整版本身份、当前版本、可信编译标记及全部来源绑定快照必须仍然有效。 */
    public void requireCurrentVersionEligible(String workspaceId,String itemId,String versionId) {
        Integer current=jdbc.queryForObject("""
                select count(*) from knowledge_item i join knowledge_version v on v.id=i.latest_version_id
                where i.workspace_id=? and i.id=? and v.id=? and v.item_id=i.id and i.status='ACTIVE'
                  and (i.origin is null or i.origin<>'SYSTEM_NAVIGATION')
                """,Integer.class,workspaceId,itemId,versionId);
        if(current==null || current!=1) throw revoked("知识页面或版本不再是当前有效证据");
        Map<String, String> page = jdbc.query("""
                select coalesce(i.wiki_page_state, '') as wiki_page_state,
                       coalesce(i.updated_by, '') as updated_by,
                       coalesce(v.provenance_schema_version, '') as provenance_schema_version
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.id = ? and v.id = ?
                """, rs -> {
                    if (!rs.next()) {
                        return Map.<String, String>of();
                    }
                    return Map.of(
                            "state", rs.getString("wiki_page_state"),
                            "updated_by", rs.getString("updated_by"),
                            "provenance", rs.getString("provenance_schema_version"));
                }, workspaceId, itemId, versionId);
        String pageState = WikiPageState.fromStored(page.get("state"), page.get("updated_by")).name();
        if (!WikiPageState.MANUAL.name().equals(pageState)) {
            String provenance = page.get("provenance");
            // 自动页必须由受支持的编译策略标记过；旧自动页没有标记时显式未认证，不按依赖为空放行
            if (provenance == null || !SUPPORTED_PROVENANCE_SCHEMA_VERSIONS.contains(provenance)) {
                throw new BusinessException("KNOWLEDGE_PROVENANCE_UNVERIFIED",
                        "自动知识版本缺少可信完整依赖标记，不能作为当前生成证据", HttpStatus.CONFLICT);
            }
        }
        List<String> citations=jdbc.queryForList("select citation_id from knowledge_version_citation where knowledge_version_id=? order by sort_order,id",String.class,versionId);
        // MANUAL 人工页行为不变：不要求自动页标记，但已有依赖仍须逐条验证来源与绑定快照。
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
     * 按用途批量判定版本可读性：版本引用的全部来源-快照对都满足该用途才算可读。
     * 同一来源的不同快照按 (sourceId, snapshotId) 分别评估，不能按 sourceId 合并后互相放行。
     */
    public Set<String> readableVersionIds(String workspaceId, List<String> versionIds, AccessPurpose purpose) {
        List<String> ids = versionIds == null ? List.of()
                : versionIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        Map<String, List<CitationOrigin>> originsByVersion = citationOriginsByVersion(workspaceId, ids);
        List<CitationOrigin> allOrigins = originsByVersion.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<String, EligibilityDecision> decisions = assessOrigins(workspaceId, allOrigins, purpose);
        Set<String> derivedWithoutCitations = artifactDerivedVersionIds(workspaceId,
                ids.stream().filter(id -> originsByVersion.getOrDefault(id, List.of()).isEmpty()).toList());
        LinkedHashSet<String> readableVersions = new LinkedHashSet<>();
        for (String versionId : ids) {
            List<CitationOrigin> origins = originsByVersion.getOrDefault(versionId, List.of());
            if (origins.isEmpty()) {
                // KB-01：没有引用来源时不能无条件放行。人工笔记本来就没有来源依赖，照常可读；
                // 产物写回的内容必须有来源血缘，缺引用说明血缘已断，按不可读处理。
                if (!derivedWithoutCitations.contains(versionId)) {
                    readableVersions.add(versionId);
                }
                continue;
            }
            if (origins.stream().allMatch(origin -> originAllowed(decisions.get(origin.key()), origin, purpose))) {
                readableVersions.add(versionId);
            }
        }
        return Set.copyOf(readableVersions);
    }

    private boolean originAllowed(EligibilityDecision decision, CitationOrigin origin, AccessPurpose purpose) {
        if (decision == null || !decision.allowed(purpose)) {
            return false;
        }
        // 当前证据必须命中同一个绑定快照；历史展示允许旧快照按历史政策阅读。
        return purpose != AccessPurpose.CURRENT_EVIDENCE
                || (origin.snapshotId() != null && origin.snapshotId().equals(decision.snapshotId()));
    }

    private record CitationOrigin(String sourceId, String snapshotId) {
        private String key() {
            return sourceId + '\u0000' + (snapshotId == null ? "" : snapshotId);
        }
    }

    /** 每个版本完整的来源-快照依赖；research 引用没有工作台来源，不在这里展开。 */
    private Map<String, List<CitationOrigin>> citationOriginsByVersion(String workspaceId, List<String> versionIds) {
        Map<String, List<CitationOrigin>> byVersion = new LinkedHashMap<>();
        for (String versionId : versionIds) {
            byVersion.put(versionId, new ArrayList<>());
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(versionIds);
        jdbc.query("""
                select kvc.knowledge_version_id, c.source_id, c.source_snapshot_id
                from knowledge_version_citation kvc
                join knowledge_version v on v.id = kvc.knowledge_version_id
                join knowledge_item i on i.id = v.item_id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and kvc.knowledge_version_id in (%s)
                order by kvc.knowledge_version_id, kvc.sort_order, kvc.citation_id
                """.formatted(String.join(",", Collections.nCopies(versionIds.size(), "?"))),
                (RowCallbackHandler) rs -> {
                    String sourceId = rs.getString("source_id");
                    if (sourceId == null || sourceId.isBlank()) {
                        return;
                    }
                    byVersion.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                            .add(new CitationOrigin(sourceId, rs.getString("source_snapshot_id")));
                }, parameters.toArray());
        Map<String, List<CitationOrigin>> immutable = new LinkedHashMap<>();
        byVersion.forEach((versionId, origins) -> immutable.put(versionId, List.copyOf(origins)));
        return immutable;
    }

    /**
     * 分批评估来源-快照资格（单批不超过端口上限）。
     * 单快照来源可以合并成批；同一来源出现多个快照时必须逐对评估，避免结果按 sourceId 互相覆盖。
     */
    private Map<String, EligibilityDecision> assessOrigins(
            String workspaceId,
            List<CitationOrigin> origins,
            AccessPurpose purpose
    ) {
        Map<String, Set<String>> snapshotsBySource = new LinkedHashMap<>();
        for (CitationOrigin origin : origins) {
            snapshotsBySource.computeIfAbsent(origin.sourceId(), ignored -> new LinkedHashSet<>())
                    .add(origin.snapshotId() == null ? "" : origin.snapshotId());
        }
        List<OriginRef> singleSnapshotRefs = new ArrayList<>();
        Map<String, EligibilityDecision> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : snapshotsBySource.entrySet()) {
            String sourceId = entry.getKey();
            if (entry.getValue().size() > 1) {
                for (String snapshot : entry.getValue()) {
                    assessOriginPair(workspaceId, sourceId,
                            snapshot.isBlank() ? null : snapshot, purpose, result);
                }
                continue;
            }
            String snapshot = entry.getValue().iterator().next();
            if (snapshot.isBlank()) {
                if (purpose == AccessPurpose.CURRENT_EVIDENCE) {
                    // 当前证据必须带绑定快照，缺失时直接阻断，不能按当前快照通配
                    result.put(new CitationOrigin(sourceId, null).key(),
                            MaterialEligibilityPolicy.decide(originKey(sourceId),
                                    EligibilityStatus.MISSING_INTERNAL,
                                    "CITATION_SNAPSHOT_MISSING", null, null, List.of()));
                } else {
                    singleSnapshotRefs.add(OriginRef.internalSource(sourceId));
                }
                continue;
            }
            singleSnapshotRefs.add(OriginRef.internalSnapshot(sourceId, snapshot));
        }
        for (int start = 0; start < singleSnapshotRefs.size();
                start += MaterialEligibilityPort.MAX_ORIGINS_PER_ASSESS) {
            List<OriginRef> batch = singleSnapshotRefs.subList(start,
                    Math.min(start + MaterialEligibilityPort.MAX_ORIGINS_PER_ASSESS, singleSnapshotRefs.size()));
            Map<String, EligibilityDecision> decisions = eligibilityPort.assess(workspaceId, batch, purpose);
            for (OriginRef ref : batch) {
                EligibilityDecision decision = decisions.get(ref.originKey());
                if (decision != null) {
                    result.put(new CitationOrigin(ref.sourceId(), ref.snapshotId()).key(), decision);
                }
            }
        }
        return result;
    }

    private void assessOriginPair(
            String workspaceId,
            String sourceId,
            String snapshotId,
            AccessPurpose purpose,
            Map<String, EligibilityDecision> result
    ) {
        if (snapshotId == null && purpose == AccessPurpose.CURRENT_EVIDENCE) {
            result.put(new CitationOrigin(sourceId, null).key(),
                    MaterialEligibilityPolicy.decide(originKey(sourceId),
                            EligibilityStatus.MISSING_INTERNAL,
                            "CITATION_SNAPSHOT_MISSING", null, null, List.of()));
            return;
        }
        OriginRef ref = snapshotId == null
                ? OriginRef.internalSource(sourceId)
                : OriginRef.internalSnapshot(sourceId, snapshotId);
        Map<String, EligibilityDecision> decisions = eligibilityPort.assess(workspaceId, List.of(ref), purpose);
        EligibilityDecision decision = decisions.get(ref.originKey());
        if (decision != null) {
            result.put(new CitationOrigin(sourceId, snapshotId).key(), decision);
        }
    }

    private String originKey(String sourceId) {
        return OriginRef.internalSource(sourceId).originKey();
    }

    /** 没有引用来源且声明了来源依赖（产物写回）的版本；这些版本不能默认放行。 */
    private Set<String> artifactDerivedVersionIds(
            String workspaceId,
            List<String> versionsWithoutCitations
    ) {
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
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String> ownedIds = jdbc.queryForList("""
                select v.id from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where i.workspace_id = ? and v.id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                String.class, parameters.toArray());
        Map<String, List<CitationOrigin>> originsByVersion = citationOriginsByVersion(workspaceId, ownedIds);
        List<CitationOrigin> allOrigins = originsByVersion.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<String, EligibilityDecision> decisions =
                assessOrigins(workspaceId, allOrigins, AccessPurpose.CURRENT_EVIDENCE);
        Map<String, List<EligibilityDecision>> result = new LinkedHashMap<>();
        for (String versionId : ownedIds) {
            List<EligibilityDecision> versionDecisions = new ArrayList<>();
            for (CitationOrigin origin : originsByVersion.getOrDefault(versionId, List.of())) {
                EligibilityDecision decision = decisions.get(origin.key());
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
                  and (origin is null or origin <> 'SYSTEM_NAVIGATION')
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

    /**
     * 检索候选的当前证据认证：导航目录与未带受支持编译标记的旧自动页都不能进入事实证据。
     * MANUAL 人工页按既有行为保留（不要求自动编译标记）。
     */
    public Set<String> certifiedCurrentEvidenceVersionIds(String workspaceId, List<String> versionIds) {
        List<String> ids = versionIds == null ? List.of()
                : versionIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String[]> rows = new ArrayList<>();
        jdbc.query("""
                select v.id, coalesce(i.wiki_page_state, '') as wiki_page_state,
                       coalesce(i.updated_by, '') as updated_by,
                       coalesce(v.provenance_schema_version, '') as provenance_schema_version
                from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where i.workspace_id = ? and v.id in (%s)
                  and (i.origin is null or i.origin <> 'SYSTEM_NAVIGATION')
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs -> rows.add(new String[] {
                        rs.getString("id"),
                        rs.getString("wiki_page_state"),
                        rs.getString("updated_by"),
                        rs.getString("provenance_schema_version")}),
                parameters.toArray());
        LinkedHashSet<String> certified = new LinkedHashSet<>();
        for (String[] row : rows) {
            if (WikiPageState.MANUAL == WikiPageState.fromStored(row[1], row[2])) {
                certified.add(row[0]);
                continue;
            }
            if (SUPPORTED_PROVENANCE_SCHEMA_VERSIONS.contains(row[3])) {
                certified.add(row[0]);
            }
        }
        return Set.copyOf(certified);
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
