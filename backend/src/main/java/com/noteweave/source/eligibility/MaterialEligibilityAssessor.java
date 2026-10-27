package com.noteweave.source.eligibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C1 端口的默认实现：批量加载来源事实、执行状态矩阵并解析生成来源依赖闭包。
 *
 * <p>依赖闭包的 SQL 只在这里维护一份；ResearchGeneratedSourceReadGate、
 * KnowledgeCitationReadGate、Artifact 门控都通过本端口获取同一事实。
 * 每次公共评估创建一个 {@link AssessmentContext}：当前路径集合做真实回路检测
 * （完成当前节点后移除，共享子依赖的 DAG 复用已完成结论，不误判循环）；
 * 深度、节点与 SQL 查询分别计数，任一超限返回 DEPENDENCY_DEPTH_EXCEEDED /
 * DEPENDENCY_BUDGET_EXCEEDED，禁止按 READY 放行或无限递归。
 * 私有递归不再进入公共入口，评估事实按 workspace+来源身份缓存，不跨租户复用。</p>
 */
@Service
public class MaterialEligibilityAssessor implements MaterialEligibilityPort {

    private static final Logger log = LoggerFactory.getLogger(MaterialEligibilityAssessor.class);

    /** 外部证据 source_id 的既有前缀；由采集器写入，其他未知 ID 不推断为外部。 */
    private static final Set<String> EXTERNAL_EVIDENCE_PREFIXES = Set.of("web-", "external:", "url-", "http:", "https:");

    /** 单次公共评估允许的闭包节点评估总数（含根与复用命中）。 */
    static final int MAX_CLOSURE_NODES = 500;

    /** 单次公共评估允许的依赖解析 SQL 次数。 */
    static final int MAX_CLOSURE_QUERIES = 150;

    /** 事实批量加载的单批来源数；超过该数分批查询，不隐式放行也不放大 IN 列表。 */
    private static final int FACT_BATCH_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MaterialEligibilityAssessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 单次评估的闭包执行上下文；只在一次公共调用内存在，绝不跨调用复用。 */
    private static final class AssessmentContext {
        final String workspaceId;
        final AccessPurpose purpose;
        /** 当前 DFS 路径上的来源集合；真实回路检测，节点完成后移除。 */
        final Set<String> path = new LinkedHashSet<>();
        /** 已完成闭包评估的来源结论；共享子依赖按 DAG 复用。 */
        final Map<String, List<String>> closureMemo = new HashMap<>();
        /** 来源行事实缓存；键含 workspace，不跨租户复用。 */
        final Map<String, SourceFact> facts = new HashMap<>();
        /** 确认不存在的来源；避免缺失来源反复回查。 */
        final Set<String> factMisses = new HashSet<>();
        /** 依赖关系映射缓存（run 级）。 */
        final Map<String, DependencyMap> dependencyMaps = new HashMap<>();
        /** 研究运行上下文脱敏状态缓存（run 级）。 */
        final Map<String, Boolean> researchContextRedacted = new HashMap<>();
        int depth;
        int nodes;
        int queries;
        String truncationReason = "";

        AssessmentContext(String workspaceId, AccessPurpose purpose) {
            this.workspaceId = workspaceId;
            this.purpose = purpose;
        }

        String factKey(String sourceId) {
            return workspaceId + '\u0000' + sourceId;
        }

        String runKey(String refId) {
            return workspaceId + '\u0000' + refId;
        }

        boolean budgetExhausted() {
            return nodes >= MAX_CLOSURE_NODES || queries >= MAX_CLOSURE_QUERIES;
        }
    }

    /** 生成来源的依赖关系：dependencies 为依赖 ID 到是否严格声明范围的映射，markers 为解析失败原因。 */
    private record DependencyMap(Map<String, Boolean> dependencies, List<String> markers) {
        static final DependencyMap EMPTY = new DependencyMap(Map.of(), List.of());
    }

    @Override
    public Map<String, EligibilityDecision> assess(String workspaceId, List<OriginRef> origins, AccessPurpose purpose) {
        if (origins == null || origins.isEmpty()) {
            return Map.of();
        }
        List<OriginRef> distinct = origins.stream().distinct().toList();
        if (distinct.size() > MAX_ORIGINS_PER_ASSESS) {
            throw new IllegalArgumentException("Eligibility assessment accepts at most "
                    + MAX_ORIGINS_PER_ASSESS + " origins per call, got " + distinct.size());
        }
        AssessmentContext context = new AssessmentContext(workspaceId, purpose);
        Map<String, EligibilityDecision> decisions = new LinkedHashMap<>();
        List<OriginRef> internalOrigins = new ArrayList<>();
        for (OriginRef origin : distinct) {
            if (!origin.isValid()) {
                decisions.put(origin.originKey(), MaterialEligibilityPolicy.decide(
                        origin.originKey(), EligibilityStatus.UNKNOWN_SCHEMA, "ORIGIN_REF_INVALID",
                        null, null, List.of()));
                continue;
            }
            if (origin.kind() == OriginKind.EXTERNAL_ARCHIVE) {
                decisions.put(origin.originKey(), assessExternalArchive(origin));
                continue;
            }
            internalOrigins.add(origin);
        }
        if (!internalOrigins.isEmpty()) {
            assessInternal(context, internalOrigins, decisions);
        }
        recordStats(context);
        return Map.copyOf(decisions);
    }

    /** 最近一次评估的闭包规模与查询次数；观测用，不参与资格判断。 */
    public record ClosureStats(int nodes, int queries, String truncationReason) {
    }

    private final java.util.concurrent.atomic.AtomicReference<ClosureStats> lastStats =
            new java.util.concurrent.atomic.AtomicReference<>(new ClosureStats(0, 0, ""));

    public ClosureStats lastClosureStats() {
        return lastStats.get();
    }

    private void recordStats(AssessmentContext context) {
        lastStats.set(new ClosureStats(context.nodes, context.queries, context.truncationReason));
    }

    private EligibilityDecision assessExternalArchive(OriginRef origin) {
        // 外部归档身份目前只认可采集器写入的前缀；建立归档注册表前保持保守判断。
        String ref = origin.archiveRef();
        boolean trustedPrefix = EXTERNAL_EVIDENCE_PREFIXES.stream().anyMatch(ref::startsWith);
        return MaterialEligibilityPolicy.decide(
                origin.originKey(),
                trustedPrefix ? EligibilityStatus.READY : EligibilityStatus.UNKNOWN_SCHEMA,
                trustedPrefix ? "EXTERNAL_ARCHIVE_COLLECTED" : "EXTERNAL_ARCHIVE_UNVERIFIED",
                null, null, List.of());
    }

    private void assessInternal(
            AssessmentContext context,
            List<OriginRef> origins,
            Map<String, EligibilityDecision> decisions
    ) {
        List<String> sourceIds = origins.stream().map(OriginRef::sourceId).toList();
        loadFacts(context, sourceIds);
        List<String> workspaceSourceIds = origins.stream().map(OriginRef::sourceId)
                .filter(id -> {
                    SourceFact fact = context.facts.get(context.factKey(id));
                    return fact != null && fact.workspaceId().equals(context.workspaceId);
                })
                .toList();
        Map<String, String> latestSnapshots = loadLatestSnapshotIds(context, workspaceSourceIds);
        Map<String, Long> latestVersions = loadLatestVersionNos(context, List.copyOf(latestSnapshots.keySet()));
        for (OriginRef origin : origins) {
            String key = origin.originKey();
            EligibilityDecision decision = decideInternalSource(
                    context,
                    origin,
                    context.facts.get(context.factKey(origin.sourceId())),
                    latestSnapshots.get(origin.sourceId()),
                    latestVersions.get(origin.sourceId()));
            decisions.put(key, decision);
        }
    }

    /**
     * 单个内部来源的判定。生成来源在任何用途下都解析依赖闭包：
     * HISTORY_DISPLAY 只放宽叶子的暂不可用/快照过期，传递合规撤销/缺失/越权仍阻断历史正文。
     */
    private EligibilityDecision decideInternalSource(
            AssessmentContext context,
            OriginRef origin,
            SourceFact fact,
            String latestSnapshotId,
            Long latestVersion
    ) {
        String key = origin.originKey();
        if (fact == null) {
            return MaterialEligibilityPolicy.decide(key, EligibilityStatus.MISSING_INTERNAL,
                    "SOURCE_ROW_MISSING", null, null, List.of());
        }
        if (!fact.workspaceId().equals(context.workspaceId)) {
            return MaterialEligibilityPolicy.decide(key, EligibilityStatus.FOREIGN_WORKSPACE,
                    "SOURCE_OTHER_WORKSPACE", null, null, List.of());
        }
        EligibilityStatus status = MaterialEligibilityPolicy.statusOf(fact.status(), fact.deleteReason());
        String reasonCode = status.name();
        if (status == EligibilityStatus.READY && origin.snapshotId() != null
                && !origin.snapshotId().equals(latestSnapshotId)) {
            status = EligibilityStatus.STALE_SNAPSHOT;
            reasonCode = "SNAPSHOT_NOT_CURRENT";
        }
        if (status == EligibilityStatus.READY && "research_agent".equals(fact.generatedBy())
                && researchContextRedacted(context, fact.generatedRefId())) {
            // 运行上下文已脱敏（METADATA_ONLY）：生成报告视同撤销，历史也不可读
            status = EligibilityStatus.COMPLIANCE_REVOKED;
            reasonCode = "RESEARCH_CONTEXT_REDACTED";
        }
        List<String> dependencyFailures = List.of();
        // NR04：历史可读的生成来源状态（READY/USER 删除/暂不可用/快照过期）都解析依赖闭包。
        // 此前仅 READY 遍历——G 先被 USER 删除后上游 S 合规撤销，历史评估会跳过整个闭包。
        if (fact.generated() && MaterialEligibilityPolicy.historyReadableByStatus(status)) {
            dependencyFailures = closureFailures(context, origin.sourceId());
        }
        EligibilityDecision decision = MaterialEligibilityPolicy.decide(
                key, status, reasonCode, latestVersion, latestSnapshotId, dependencyFailures);
        if (!dependencyFailures.isEmpty()) {
            if (status == EligibilityStatus.READY) {
                decision = MaterialEligibilityPolicy.withDependencyFailure(decision, dependencyFailures);
            } else {
                // 非 READY 状态证据位已为 false，只携带闭包摘要供历史判定与原因展示
                decision = MaterialEligibilityPolicy.withDependencySummary(decision, dependencyFailures);
            }
            // NR04：闭包硬失败对历史的阻断是来源的固有事实，不随评估入口的用途参数变化——
            // 知识版本可读性按 allowed(HISTORY_DISPLAY) 读 historyReadable，
            // 而批量评估入口固定以 CURRENT_EVIDENCE 调用（用途门控曾让降级永不生效）
            decision = MaterialEligibilityPolicy.withHistoryHardFailure(decision);
        }
        return decision;
    }

    /**
     * 解析生成来源的依赖闭包，返回未通过项摘要。
     *
     * <p>当前路径集合检测真实回路（DAG 共享子依赖经 memo 复用不误判）；
     * 深度按当前路径计数，节点与查询按整个评估计数，超限返回明确失败标记。
     * 研究来源的依赖来自 research_run.source_scope_json（声明范围，严格要求 READY）
     * 与 evidence manifest/source_evidence（实际引用，内部来源要求 READY，采集器外部证据放行）。</p>
     */
    /** 研究运行上下文是否已脱敏；结果按 run 缓存在当前评估上下文内。 */
    private boolean researchContextRedacted(AssessmentContext context, String researchRunId) {
        if (researchRunId == null || researchRunId.isBlank()) {
            return false;
        }
        String key = context.runKey(researchRunId);
        Boolean cached = context.researchContextRedacted.get(key);
        if (cached != null) {
            return cached;
        }
        context.queries++;
        String replay = jdbcTemplate.query("""
                select ris.replay_availability
                from research_run rr
                left join run_input_snapshot ris on ris.id = rr.context_snapshot_id
                where rr.id = ? and rr.workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null,
                researchRunId, context.workspaceId);
        boolean redacted = "METADATA_ONLY".equals(replay);
        context.researchContextRedacted.put(key, redacted);
        return redacted;
    }

    private List<String> closureFailures(AssessmentContext context, String sourceId) {
        List<String> memo = context.closureMemo.get(sourceId);
        if (memo != null) {
            return memo;
        }
        if (context.path.contains(sourceId)) {
            return List.of(sourceId + ":DEPENDENCY_CYCLE");
        }
        if (context.depth >= MAX_DEPENDENCY_DEPTH) {
            noteTruncation(context, "DEPENDENCY_DEPTH_EXCEEDED");
            return List.of(sourceId + ":DEPENDENCY_DEPTH_EXCEEDED");
        }
        if (context.budgetExhausted()) {
            noteTruncation(context, "DEPENDENCY_BUDGET_EXCEEDED");
            return List.of(sourceId + ":DEPENDENCY_BUDGET_EXCEEDED");
        }
        context.nodes++;
        context.path.add(sourceId);
        context.depth++;
        try {
            List<String> failures = new ArrayList<>();
            DependencyMap dependencyMap = loadDependencyMap(context, sourceId);
            failures.addAll(dependencyMap.markers());
            for (Map.Entry<String, Boolean> entry : dependencyMap.dependencies().entrySet()) {
                String dependencyId = entry.getKey();
                EligibilityDecision decision = dependencyDecision(context, dependencyId, entry.getValue());
                if (!closureLeafPasses(context, decision)) {
                    failures.add(dependencyId + ":" + failureSuffix(decision));
                    if (decision.status() == EligibilityStatus.READY
                            && !decision.dependencySummary().isEmpty()) {
                        // 传递闭包失败的真实原因随叶子冒泡（环/超限/撤销），
                        // 每层限量防止摘要膨胀；最深原因不被压平成通用标记
                        decision.dependencySummary().stream().limit(8).forEach(failures::add);
                    }
                }
            }
            List<String> result = List.copyOf(failures);
            context.closureMemo.put(sourceId, result);
            return result;
        } finally {
            context.depth--;
            context.path.remove(sourceId);
        }
    }

    private void noteTruncation(AssessmentContext context, String reason) {
        if (context.truncationReason.isEmpty()) {
            context.truncationReason = reason;
        }
        log.warn("Eligibility dependency closure truncated: reason={}, purpose={}, depth={}, nodes={}, queries={}",
                reason, context.purpose, context.depth, context.nodes, context.queries);
    }

    private String failureSuffix(EligibilityDecision decision) {
        if (decision.status() != EligibilityStatus.READY) {
            return decision.status().name();
        }
        // READY 但闭包未过：叶子自身历史也不可读说明存在硬失败；否则只是证据/再发布不可用
        return decision.historyReadable() ? "DEPENDENCY_NOT_READY" : "DEPENDENCY_HISTORY_BLOCKED";
    }

    /** 闭包叶子的通过判定：HISTORY 只放宽暂不可用/快照过期；USER 删除按保守正文规则仍阻断。 */
    private boolean closureLeafPasses(AssessmentContext context, EligibilityDecision decision) {
        return switch (context.purpose) {
            case HISTORY_DISPLAY -> decision.historyReadable()
                    && decision.status() != EligibilityStatus.USER_DELETED;
            case CURRENT_EVIDENCE -> decision.evidenceEligible();
            case REPUBLISH -> decision.republishAllowed();
        };
    }

    /** 单个依赖的判定；宽松引用里采集器外部证据没有 source 行，按归档证据放行。 */
    private EligibilityDecision dependencyDecision(
            AssessmentContext context, String dependencyId, boolean strict) {
        SourceFact fact = context.facts.get(context.factKey(dependencyId));
        if (fact == null && !context.factMisses.contains(context.factKey(dependencyId))) {
            loadFacts(context, List.of(dependencyId));
            fact = context.facts.get(context.factKey(dependencyId));
        }
        EligibilityDecision decision = decideInternalSource(
                context, OriginRef.internalSource(dependencyId), fact, null, null);
        if (decision.status() == EligibilityStatus.MISSING_INTERNAL && !strict
                && EXTERNAL_EVIDENCE_PREFIXES.stream().anyMatch(dependencyId::startsWith)) {
            // 仅当来源行确认缺失且来自宽松引用时按采集器外部证据放行；
            // 已确认的内部删除/跨工作台事实不被前缀覆盖。
            return MaterialEligibilityPolicy.decide(decision.originKey(),
                    EligibilityStatus.READY, "EXTERNAL_ARCHIVE_COLLECTED", null, null, List.of());
        }
        return decision;
    }

    private DependencyMap loadDependencyMap(AssessmentContext context, String sourceId) {
        SourceFact fact = context.facts.get(context.factKey(sourceId));
        if (fact == null || !fact.generated()) {
            return DependencyMap.EMPTY;
        }
        if ("research_agent".equals(fact.generatedBy())) {
            return researchRunDependencyMap(context, fact.generatedRefId());
        }
        if ("artifact_agent".equals(fact.generatedBy())) {
            return artifactVersionDependencyMap(context, fact.generatedRefId());
        }
        // 未知生成类型没有可信依赖记录：明确未知并阻断，不能默认无依赖放行
        return new DependencyMap(Map.of(),
                List.of("generated:" + sourceId + ":DEPENDENCIES_UNKNOWN"));
    }

    @Override
    public GeneratedDependencyAssessment assessGeneratedDependenciesDetailed(
            String workspaceId, String generatedBy, String generatedRefId, AccessPurpose purpose) {
        if (generatedRefId == null || generatedRefId.isBlank()) {
            return new GeneratedDependencyAssessment(false, List.of("GENERATION_REF_INVALID"), Map.of());
        }
        AssessmentContext context = new AssessmentContext(workspaceId, purpose);
        if (!"research_agent".equals(generatedBy) && !"artifact_agent".equals(generatedBy)) {
            recordStats(context);
            return new GeneratedDependencyAssessment(false,
                    List.of("GENERATED_KIND_UNKNOWN:" + (generatedBy == null ? "" : generatedBy)), Map.of());
        }
        DependencyMap dependencyMap = loadDependencyMap(context, generatedRefId, generatedBy);
        List<String> markers = new ArrayList<>(dependencyMap.markers());
        Map<String, EligibilityDecision> result = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : dependencyMap.dependencies().entrySet()) {
            result.put(entry.getKey(), dependencyDecision(context, entry.getKey(), entry.getValue()));
        }
        recordStats(context);
        return new GeneratedDependencyAssessment(markers.isEmpty(), List.copyOf(markers), result);
    }

    private DependencyMap loadDependencyMap(AssessmentContext context, String generatedRefId, String generatedBy) {
        if ("research_agent".equals(generatedBy)) {
            return researchRunDependencyMap(context, generatedRefId);
        }
        return artifactVersionDependencyMap(context, generatedRefId);
    }

    private DependencyMap researchRunDependencyMap(AssessmentContext context, String researchRunId) {
        if (researchRunId == null || researchRunId.isBlank()) {
            return new DependencyMap(Map.of(), List.of("research-run:GENERATION_REF_INVALID"));
        }
        DependencyMap cached = context.dependencyMaps.get(context.runKey(researchRunId));
        if (cached != null) {
            return cached;
        }
        context.queries++;
        String scopeJson = jdbcTemplate.query("""
                select source_scope_json from research_run
                where id = ? and workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, researchRunId, context.workspaceId);
        if (scopeJson == null) {
            // run 不存在或属于其他工作台：与合法空范围分开，按未解析阻断
            DependencyMap unresolved = new DependencyMap(Map.of(),
                    List.of("research-run:" + researchRunId + ":RUN_UNRESOLVED"));
            context.dependencyMaps.put(context.runKey(researchRunId), unresolved);
            return unresolved;
        }
        Map<String, Boolean> dependencies = new LinkedHashMap<>();
        List<String> markers = new ArrayList<>();
        if (!scopeJson.isBlank()) {
            try {
                List<String> ids = objectMapper.readValue(scopeJson, new TypeReference<List<String>>() { });
                if (ids != null) {
                    ids.stream().filter(id -> id != null && !id.isBlank())
                            .forEach(id -> dependencies.put(id, Boolean.TRUE));
                }
            } catch (Exception ex) {
                // 范围 JSON 损坏：标记解析失败并阻断，禁止当作没有依赖
                markers.add("research-run:" + researchRunId + ":SCOPE_PARSE_FAILED");
            }
        }
        context.queries += 2;
        List<String> citedIds = new ArrayList<>(jdbcTemplate.query("""
                select distinct item.source_id
                from research_evidence_manifest m
                join research_evidence_manifest_item item on item.manifest_id = m.id
                where m.workspace_id = ? and m.research_run_id = ?
                  and item.source_id is not null and item.source_id <> ''
                """, (rs, rowNum) -> rs.getString(1), context.workspaceId, researchRunId));
        citedIds.addAll(jdbcTemplate.query("""
                select distinct source_id from source_evidence
                where research_run_id = ? and source_id is not null and source_id <> ''
                """, (rs, rowNum) -> rs.getString(1), researchRunId));
        citedIds.forEach(id -> dependencies.putIfAbsent(id, Boolean.FALSE));
        DependencyMap resolved = new DependencyMap(Map.copyOf(dependencies), List.copyOf(markers));
        context.dependencyMaps.put(context.runKey(researchRunId), resolved);
        return resolved;
    }

    /**
     * Artifact 另存来源的依赖记录：artifact_version.citations_json 中的引用来源。
     * 版本行缺失/越权或引用 JSON 损坏按未解析阻断；合法空引用列表是允许的空依赖。
     */
    private DependencyMap artifactVersionDependencyMap(AssessmentContext context, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return new DependencyMap(Map.of(), List.of("artifact-version:GENERATION_REF_INVALID"));
        }
        DependencyMap cached = context.dependencyMaps.get(context.runKey(versionId));
        if (cached != null) {
            return cached;
        }
        context.queries++;
        String citationsJson = jdbcTemplate.query("""
                select v.citations_json
                from artifact_version v
                join artifact_job j on j.id = v.artifact_job_id
                where v.id = ? and j.workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, versionId, context.workspaceId);
        if (citationsJson == null) {
            DependencyMap unresolved = new DependencyMap(Map.of(),
                    List.of("artifact-version:" + versionId + ":VERSION_UNRESOLVED"));
            context.dependencyMaps.put(context.runKey(versionId), unresolved);
            return unresolved;
        }
        Map<String, Boolean> dependencies = new LinkedHashMap<>();
        List<String> markers = new ArrayList<>();
        if (!citationsJson.isBlank()) {
            try {
                List<Object> citations = objectMapper.readValue(citationsJson, new TypeReference<List<Object>>() { });
                if (citations != null) {
                    for (Object citation : citations) {
                        String id = citationSourceId(citation);
                        if (id != null && !id.isBlank()) {
                            dependencies.put(id, Boolean.TRUE);
                        }
                    }
                }
            } catch (Exception ex) {
                markers.add("artifact-version:" + versionId + ":CITATIONS_PARSE_FAILED");
            }
        }
        DependencyMap resolved = new DependencyMap(Map.copyOf(dependencies), List.copyOf(markers));
        context.dependencyMaps.put(context.runKey(versionId), resolved);
        return resolved;
    }

    /** 引用项可能是 sourceId 字符串或携带 sourceId/source_id 字段的对象。 */
    private String citationSourceId(Object citation) {
        if (citation instanceof String value) {
            return value;
        }
        if (citation instanceof Map<?, ?> map) {
            Object id = map.get("sourceId");
            if (id == null) {
                id = map.get("source_id");
            }
            return id instanceof String value ? value : null;
        }
        return null;
    }

    private void loadFacts(AssessmentContext context, List<String> sourceIds) {
        List<String> ids = sourceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> !context.facts.containsKey(context.factKey(id)))
                .filter(id -> !context.factMisses.contains(context.factKey(id)))
                .distinct()
                .toList();
        for (List<String> batch : partition(ids, FACT_BATCH_SIZE)) {
            context.queries++;
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            Object[] parameters = batch.toArray();
            jdbcTemplate.query("""
                    select id, workspace_id, status, delete_reason,
                           coalesce(generated_by, '') as generated_by,
                           coalesce(generated_ref_id, '') as generated_ref_id
                    from source where id in (%s)
                    """.formatted(placeholders), rs -> {
                context.facts.put(context.factKey(rs.getString("id")), new SourceFact(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("status"),
                        rs.getString("delete_reason"),
                        rs.getString("generated_by"),
                        rs.getString("generated_ref_id")));
            }, parameters);
        }
        for (String id : ids) {
            if (!context.facts.containsKey(context.factKey(id))) {
                context.factMisses.add(context.factKey(id));
            }
        }
    }

    private static List<List<String>> partition(List<String> ids, int size) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += size) {
            batches.add(ids.subList(i, Math.min(i + size, ids.size())));
        }
        return batches;
    }

    private Map<String, String> loadLatestSnapshotIds(AssessmentContext context, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, String> snapshots = new HashMap<>();
        for (List<String> batch : partition(ids, FACT_BATCH_SIZE)) {
            context.queries++;
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    select ss.source_id, ss.id
                    from source_snapshot ss
                    join (
                        select source_id, max(version_no) as version_no
                        from source_snapshot where source_id in (%s) group by source_id
                    ) latest on latest.source_id = ss.source_id and latest.version_no = ss.version_no
                    """.formatted(placeholders), rs -> {
                snapshots.put(rs.getString(1), rs.getString(2));
            }, batch.toArray());
        }
        return snapshots;
    }

    private Map<String, Long> loadLatestVersionNos(AssessmentContext context, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, Long> versions = new HashMap<>();
        for (List<String> batch : partition(ids, FACT_BATCH_SIZE)) {
            context.queries++;
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    select source_id, max(version_no) as version_no
                    from source_snapshot where source_id in (%s) group by source_id
                    """.formatted(placeholders), rs -> {
                versions.put(rs.getString(1), rs.getLong(2));
            }, batch.toArray());
        }
        return versions;
    }

    private record SourceFact(
            String sourceId,
            String workspaceId,
            String status,
            String deleteReason,
            String generatedBy,
            String generatedRefId
    ) {
        private boolean generated() {
            return generatedBy != null && !generatedBy.isBlank();
        }
    }
}
