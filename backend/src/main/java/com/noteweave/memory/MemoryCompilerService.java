package com.noteweave.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.common.Ids;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryCompilerService {

    private final JdbcTemplate jdbcTemplate;
    private final MemoryCompilerReadRepository readRepository;
    private MemorySourceReadGate sourceReadGate;
    private final ObjectProvider<CapabilityCatalogPort> capabilityCatalogProvider;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryCompilerPolicy compilerPolicy;
    private final MemoryCompiledPackCache compiledPackCache;
    private final MeterRegistry meterRegistry;
    private final MemoryTextEmbeddingStore embeddingStore;
    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;
    private final MemoryRelevanceScorer relevanceScorer = new MemoryRelevanceScorer();
    private final boolean queryInjectionEnabled;

    public MemoryCompilerService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<CapabilityCatalogPort> capabilityCatalogProvider,
            CurrentUserProvider currentUserProvider,
            MemoryCompilerPolicy compilerPolicy,
            MemoryCompiledPackCache compiledPackCache,
            MeterRegistry meterRegistry,
            MemoryTextEmbeddingStore embeddingStore,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            @Value("${noteweave.memory.query-injection-enabled:true}") boolean queryInjectionEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.readRepository = new MemoryCompilerReadRepository(jdbcTemplate, objectMapper);
        this.sourceReadGate = new MemorySourceReadGate(jdbcTemplate, objectMapper);
        this.capabilityCatalogProvider = capabilityCatalogProvider;
        this.currentUserProvider = currentUserProvider;
        this.compilerPolicy = compilerPolicy;
        this.compiledPackCache = compiledPackCache;
        this.meterRegistry = meterRegistry;
        this.embeddingStore = embeddingStore;
        this.embeddingClientProvider = embeddingClientProvider;
        this.queryInjectionEnabled = queryInjectionEnabled;
    }

    public MemoryControlPackResponse compileChatControlPack(String workspaceId, String answerMode) {
        return compileChatControlPack(workspaceId, answerMode, null);
    }

    public MemoryControlPackResponse compileChatControlPack(String workspaceId, String answerMode, String query) {
        String normalizedMode = MemorySignalService.normalizeToken(answerMode);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("CHAT");
        neighborhoods.add("CHAT_" + normalizedMode);
        return compilePack(workspaceId, "chat", normalizedMode, "CHAT_" + normalizedMode, neighborhoods,
                List.of("Memory 不作为事实来源，事实内容必须来自工作台资料池、原文窗口或 Wiki 页面"),
                List.of(), query);
    }

    public MemoryControlPackResponse compileArtifactControlPack(String workspaceId, String skillKey) {
        return compileArtifactControlPack(workspaceId, skillKey, null);
    }

    public MemoryControlPackResponse compileArtifactControlPack(
            String workspaceId, String skillKey, String query) {
        return compileArtifactControlPackForActor(workspaceId, skillKey,
                currentUserProvider.requireUserId(), query);
    }

    public MemoryControlPackResponse compileArtifactControlPackForActor(
            String workspaceId, String skillKey, String actorUserId) {
        return compileArtifactControlPackForActor(workspaceId, skillKey, actorUserId, null);
    }

    public MemoryControlPackResponse compileArtifactControlPackForActor(
            String workspaceId, String skillKey, String actorUserId, String query) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Artifact actor is required");
        }
        List<String> degradationReasons = new ArrayList<>();
        CapabilityCatalogPort catalog = capabilityCatalogProvider.getIfAvailable();
        String normalizedSkillKey;
        String normalizedAction;
        if (catalog == null) {
            normalizedSkillKey = normalizeCapabilityKey(skillKey);
            normalizedAction = "";
            degradationReasons.add("capability_catalog_unavailable");
        } else {
            CapabilityCatalogPort.CapabilityDescriptor capability =
                    catalog.requireCapability(skillKey);
            normalizedSkillKey = capability.capabilityKey();
            normalizedAction = MemorySignalService.normalizeToken(capability.actionKey());
        }
        String skillNeighborhood = "ARTIFACT_SKILL_" + MemorySignalService.normalizeToken(normalizedSkillKey);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("ARTIFACT");
        if (!normalizedAction.isBlank()) {
            neighborhoods.add("ARTIFACT_" + normalizedAction);
        }
        neighborhoods.add(skillNeighborhood);
        return compilePack(workspaceId, "artifact", normalizedSkillKey, skillNeighborhood, neighborhoods,
                List.of(
                        "产物事实必须来自工作台资料池或已保存为资料的系统产物",
                        "Memory 不作为产物生成原材料"
                ), degradationReasons, actorUserId, query);
    }

    public MemoryControlPackResponse compileResearchControlPack(String workspaceId, String profileKey) {
        return compileResearchControlPack(workspaceId, profileKey, null);
    }

    public MemoryControlPackResponse compileResearchControlPack(
            String workspaceId, String profileKey, String query) {
        String normalizedProfile = MemorySignalService.normalizeToken(profileKey);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("RESEARCH");
        neighborhoods.add("RESEARCH_" + normalizedProfile);
        return compilePack(workspaceId, "research", normalizedProfile, "RESEARCH_" + normalizedProfile, neighborhoods,
                List.of(
                        "Memory 不作为研究证据",
                        "研究结论必须由搜索结果、工作台资料或验证证据支持"
                ), List.of(), query);
    }

    public void logPackUsage(String workspaceId, String taskType, String targetType, String targetId, MemoryControlPackResponse pack) {
        if (pack == null || pack.memoryObjectIds().isEmpty()) {
            return;
        }
        List<MemoryReferenceResponse> references = pack.memoryReferences().isEmpty()
                ? readRepository.hydrateReferences(workspaceId, pack.memoryObjectIds())
                : pack.memoryReferences();
        for (MemoryReferenceResponse reference : references) {
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*)
                    from memory_usage_log
                    where workspace_id = ? and target_type = ? and target_id = ?
                      and memory_item_id = ?
                    """, Integer.class, workspaceId, targetType, targetId,
                    reference.memoryObjectId());
            if (existing != null && existing > 0) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into memory_usage_log(
                        id, memory_object_id, memory_version_id,
                        memory_item_id, memory_revision_id, workspace_id,
                        task_type, target_type, target_id, compiled_as,
                        outcome_policy_version
                    )
                    select ?, i.legacy_memory_object_id,
                           case when i.legacy_memory_object_id is null then null else ? end,
                           i.id, ?, ?, ?, ?, ?, ?, ?
                    from memory_item i
                    where i.workspace_id = ? and i.id = ?
                    """,
                    Ids.newId(),
                    reference.memoryVersionId(),
                    reference.memoryVersionId(),
                    workspaceId,
                    taskType,
                    targetType,
                    targetId,
                    pack.packType(),
                    MemoryOutcomePolicy.VERSION,
                    workspaceId,
                    reference.memoryObjectId()
            );
        }
    }

    private MemoryControlPackResponse compilePack(
            String workspaceId,
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy,
            List<String> degradationReasons,
            String query
    ) {
        return compilePack(workspaceId, packType, targetKey, taskNeighborhood,
                allowedNeighborhoods, evidencePolicy, degradationReasons,
                currentUserProvider.requireUserId(), query);
    }

    private MemoryControlPackResponse compilePack(
            String workspaceId,
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy,
            List<String> degradationReasons,
            String currentUserId,
            String query
    ) {
        // 先排除来源不可读的条目，再计算缓存键，避免已删除来源的旧 Pack 命中缓存。
        List<MemoryCompilerReadRepository.StateRow> stateRows =
                filterReadable(workspaceId, loadStateRows(workspaceId),
                        MemoryCompilerReadRepository.StateRow::memoryVersionId);
        List<MemoryCompilerReadRepository.StateRow> eligibleState = stateRows.stream()
                .filter(row -> compilerPolicy.scopeAllowed(
                        row.memoryScope(), row.ownerUserId(), currentUserId))
                .filter(row -> compilerPolicy.neighborhoodPriority(
                        row.taskNeighborhoods(), taskNeighborhood, allowedNeighborhoods) < 3)
                .sorted(Comparator.comparing(
                        MemoryCompilerReadRepository.StateRow::memoryObjectId))
                .toList();
        boolean onDemandPresent = eligibleState.stream().anyMatch(row ->
                MemoryCompilerPolicy.ON_DEMAND.equals(
                        compilerPolicy.memoryClass(row.semanticSlotKey(), row.compileHints())));
        // 没有按需记忆时，查询不进入缓存键，避免同一批常驻约束被不同问题拆成多份缓存。
        String queryFingerprint = queryInjectionEnabled && onDemandPresent ? normalizeQuery(query) : null;
        MemoryCompiledPackCache.CacheKey cacheKey = new MemoryCompiledPackCache.CacheKey(
                workspaceId,
                fingerprint(List.of(currentUserId)),
                packType,
                requestFingerprint(
                        packType,
                        targetKey,
                        taskNeighborhood,
                        allowedNeighborhoods,
                        evidencePolicy,
                        degradationReasons,
                        queryFingerprint),
                compilerPolicy.version(),
                stateFingerprint(eligibleState)
        );
        java.util.Optional<MemoryControlPackResponse> cached = compiledPackCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<MemoryCompilerReadRepository.ObjectRow> rows =
                filterReadable(workspaceId, loadObjectRows(workspaceId),
                        MemoryCompilerReadRepository.ObjectRow::memoryVersionId);
        List<PreparedMemory> prepared = rows.stream()
                .filter(row -> compilerPolicy.scopeAllowed(
                        row.memoryScope(), row.ownerUserId(), currentUserId))
                .map(row -> new PreparedMemory(
                        row,
                        compilerPolicy.scopePriority(row.memoryScope()),
                        compilerPolicy.neighborhoodPriority(
                                row.taskNeighborhoods(), taskNeighborhood, allowedNeighborhoods),
                        compilerPolicy.memoryClass(row.semanticSlotKey(), row.compileHints())))
                .filter(row -> row.neighborhoodPriority() < 3)
                .sorted(Comparator.comparing(item -> item.row().memoryObjectId()))
                .toList();

        List<String> relevanceReasons = List.of();
        java.util.Map<String, Double> demandRelevance = java.util.Map.of();
        List<PreparedMemory> onDemand = prepared.stream()
                .filter(item -> MemoryCompilerPolicy.ON_DEMAND.equals(item.memoryClass()))
                .toList();
        if (!onDemand.isEmpty() && queryInjectionEnabled) {
            List<MemoryRelevanceScorer.Item> items = onDemand.stream()
                    .map(item -> new MemoryRelevanceScorer.Item(
                            item.row().memoryObjectId(),
                            item.row().displayText(),
                            embeddingStore.vector(
                                    workspaceId,
                                    item.row().memoryObjectId(),
                                    item.row().displayText(),
                                    item.row().textEmbedding(),
                                    item.row().textEmbeddingHash())))
                    .toList();
            MemoryRelevanceScorer.Result scored = relevanceScorer.score(
                    query, items, embeddingClientProvider.getIfAvailable());
            demandRelevance = scored.relevance();
            relevanceReasons = scored.degradationReasons();
        }

        Instant now = Instant.now();
        List<ValuedMemory> valued = new ArrayList<>();
        List<MemoryCompilationTraceResponse.Decision> decisions = new ArrayList<>();
        for (PreparedMemory item : prepared) {
            boolean demand = MemoryCompilerPolicy.ON_DEMAND.equals(item.memoryClass());
            double relevance = !demand ? 1d
                    : (queryInjectionEnabled ? demandRelevance.getOrDefault(item.row().memoryObjectId(), 0d) : 1d);
            if (demand && relevance <= 0d) {
                decisions.add(new MemoryCompilationTraceResponse.Decision(
                        item.row().memoryObjectId(), false, MemoryCompilerPolicy.REASON_IRRELEVANT));
                continue;
            }
            Instant basis = item.row().lastReinforcedAt() != null
                    ? item.row().lastReinforcedAt() : item.row().updatedAt();
            double value = compilerPolicy.value(
                    compilerPolicy.confidence(item.row().evidenceAlpha(), item.row().evidenceBeta()),
                    item.row().utilityScore(),
                    relevance,
                    compilerPolicy.recency(basis, now));
            int tokens = compilerPolicy.memoryTokens(item.row().compileHints(), item.row().displayText());
            valued.add(new ValuedMemory(item, relevance, value, tokens));
        }
        valued.sort(Comparator
                .comparingDouble(ValuedMemory::density).reversed()
                .thenComparing(item -> item.prepared().row().memoryObjectId()));

        List<String> memoryObjectIds = new ArrayList<>();
        List<MemoryReferenceResponse> memoryReferences = new ArrayList<>();
        Set<String> styleConstraints = new LinkedHashSet<>();
        Set<String> structureConstraints = new LinkedHashSet<>();
        Set<String> terminologyPolicy = new LinkedHashSet<>();
        Set<String> forbiddenPatterns = new LinkedHashSet<>();
        Set<String> interactionPolicy = new LinkedHashSet<>();
        Set<String> reviewChecklist = new LinkedHashSet<>();
        int maximumTokens = compilerPolicy.maximumTokens(packType);
        int selectedTokens = evidencePolicy.stream()
                .mapToInt(compilerPolicy::estimateTokens)
                .sum();

        for (ValuedMemory item : valued) {
            MemoryCompilerReadRepository.ObjectRow row = item.prepared().row();
            int incrementalTokens = incrementalTokens(
                    row.compileHints(),
                    styleConstraints,
                    structureConstraints,
                    terminologyPolicy,
                    forbiddenPatterns,
                    interactionPolicy,
                    reviewChecklist
            );
            if (selectedTokens + incrementalTokens > maximumTokens) {
                decisions.add(new MemoryCompilationTraceResponse.Decision(
                        row.memoryObjectId(), false, MemoryCompilerPolicy.REASON_BUDGET));
                continue;
            }
            selectedTokens += incrementalTokens;
            String reason = compilerPolicy.selectedReason(item.prepared().memoryClass());
            boolean demand = MemoryCompilerPolicy.ON_DEMAND.equals(item.prepared().memoryClass());
            memoryObjectIds.add(row.memoryObjectId());
            memoryReferences.add(new MemoryReferenceResponse(
                    row.memoryObjectId(),
                    row.memoryVersionId(),
                    row.utilityScore(),
                    item.prepared().scopePriority(),
                    item.prepared().neighborhoodPriority(),
                    reason,
                    item.prepared().memoryClass(),
                    demand ? item.relevance() : null,
                    row.displayText()));
            decisions.add(new MemoryCompilationTraceResponse.Decision(
                    row.memoryObjectId(), true, reason));
            styleConstraints.addAll(row.compileHints().styleConstraints());
            structureConstraints.addAll(row.compileHints().structureConstraints());
            terminologyPolicy.addAll(row.compileHints().terminologyPolicy());
            forbiddenPatterns.addAll(row.compileHints().forbiddenPatterns());
            interactionPolicy.addAll(row.compileHints().interactionPolicy());
            reviewChecklist.addAll(row.compileHints().reviewChecklist());
        }
        decisions.sort(Comparator.comparing(MemoryCompilationTraceResponse.Decision::memoryObjectId));
        List<String> combinedReasons = new ArrayList<>(degradationReasons);
        combinedReasons.addAll(relevanceReasons);
        boolean truncated = decisions.stream().anyMatch(decision ->
                !decision.selected() && MemoryCompilerPolicy.REASON_BUDGET.equals(decision.reason()));

        MemoryControlPackResponse pack = new MemoryControlPackResponse(
                packType,
                targetKey,
                taskNeighborhood,
                List.copyOf(styleConstraints),
                List.copyOf(structureConstraints),
                List.copyOf(terminologyPolicy),
                List.copyOf(forbiddenPatterns),
                List.copyOf(evidencePolicy),
                List.copyOf(interactionPolicy),
                List.copyOf(reviewChecklist),
                List.copyOf(memoryObjectIds),
                List.copyOf(memoryReferences),
                new MemoryCompilationTraceResponse(
                        compilerPolicy.version(),
                        maximumTokens,
                        selectedTokens,
                        prepared.size(),
                        memoryReferences.size(),
                        prepared.size() - memoryReferences.size(),
                        truncated,
                        !combinedReasons.isEmpty(),
                        combinedReasons,
                        decisions
                )
        );
        compiledPackCache.put(cacheKey, pack);
        return pack;
    }

    private List<MemoryCompilerReadRepository.StateRow> loadStateRows(String workspaceId) {
        long startedAt = System.nanoTime();
        try {
            List<MemoryCompilerReadRepository.StateRow> rows = readRepository.loadStateRows(workspaceId);
            recordDatabaseLoad("state", "success", startedAt);
            return rows;
        } catch (RuntimeException exception) {
            recordDatabaseLoad("state", "error", startedAt);
            throw exception;
        }
    }

    private List<MemoryCompilerReadRepository.ObjectRow> loadObjectRows(String workspaceId) {
        long startedAt = System.nanoTime();
        try {
            List<MemoryCompilerReadRepository.ObjectRow> rows = readRepository.loadObjectRows(workspaceId);
            recordDatabaseLoad("pack", "success", startedAt);
            return rows;
        } catch (RuntimeException exception) {
            recordDatabaseLoad("pack", "error", startedAt);
            throw exception;
        }
    }

    /** 批量校验来源可读性后过滤；不修改数据库，只影响本次编译结果。 */
    private <T> List<T> filterReadable(String workspaceId, List<T> rows, Function<T, String> revisionId) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<String> staleRevisionIds = sourceReadGate.staleRevisionIds(
                workspaceId, rows.stream().map(revisionId).toList());
        if (staleRevisionIds.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> !staleRevisionIds.contains(revisionId.apply(row)))
                .toList();
    }

    private String requestFingerprint(
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy,
            List<String> degradationReasons,
            String normalizedQuery
    ) {
        List<String> fields = new ArrayList<>();
        fields.add(packType);
        fields.add(targetKey);
        fields.add(taskNeighborhood);
        allowedNeighborhoods.stream().sorted().forEach(value -> fields.add("n:" + value));
        evidencePolicy.forEach(value -> fields.add("e:" + value));
        degradationReasons.forEach(value -> fields.add("d:" + value));
        if (normalizedQuery != null) {
            fields.add("q:" + normalizedQuery);
        }
        return fingerprint(fields);
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String stateFingerprint(List<MemoryCompilerReadRepository.StateRow> rows) {
        List<String> fields = new ArrayList<>();
        fields.add(Integer.toString(rows.size()));
        for (MemoryCompilerReadRepository.StateRow row : rows) {
            fields.add(row.memoryObjectId());
            fields.add(row.memoryVersionId());
            fields.add(Double.toString(row.utilityScore()));
            fields.add(row.memoryScope());
            fields.add(row.ownerUserId());
            fields.add(row.updatedAt().toString());
            row.taskNeighborhoods().stream().sorted()
                    .forEach(value -> fields.add("n:" + value));
            fields.add(row.status());
            fields.add(row.validFrom());
            fields.add(row.validTo());
            fields.add(row.semanticSlotKey());
            fields.add(Double.toString(row.evidenceAlpha()));
            fields.add(Double.toString(row.evidenceBeta()));
            fields.add(row.lastReinforcedAt() == null ? "" : row.lastReinforcedAt().toString());
            fields.add(row.displayText());
            fields.add(row.textEmbeddingHash());
        }
        return fingerprint(fields);
    }

    private String fingerprint(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = (field == null ? "<null>" : field)
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void recordDatabaseLoad(String phase, String result, long startedAt) {
        meterRegistry.timer(
                        "noteweave.memory.compiler.db.load",
                        "phase", phase,
                        "result", result)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private int incrementalTokens(
            MemorySignalService.MemoryCompileHints hints,
            Set<String> styleConstraints,
            Set<String> structureConstraints,
            Set<String> terminologyPolicy,
            Set<String> forbiddenPatterns,
            Set<String> interactionPolicy,
            Set<String> reviewChecklist
    ) {
        return incrementalTokens(hints.styleConstraints(), styleConstraints)
                + incrementalTokens(hints.structureConstraints(), structureConstraints)
                + incrementalTokens(hints.terminologyPolicy(), terminologyPolicy)
                + incrementalTokens(hints.forbiddenPatterns(), forbiddenPatterns)
                + incrementalTokens(hints.interactionPolicy(), interactionPolicy)
                + incrementalTokens(hints.reviewChecklist(), reviewChecklist);
    }

    private int incrementalTokens(List<String> candidates, Set<String> selected) {
        return candidates.stream()
                .filter(item -> !selected.contains(item))
                .mapToInt(compilerPolicy::estimateTokens)
                .sum();
    }

    private String normalizeCapabilityKey(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private record PreparedMemory(
            MemoryCompilerReadRepository.ObjectRow row,
            int scopePriority,
            int neighborhoodPriority,
            String memoryClass
    ) {
    }

    private record ValuedMemory(
            PreparedMemory prepared,
            double relevance,
            double value,
            int tokens
    ) {
        private double density() {
            return value / Math.max(tokens, 1);
        }
    }

    /** FB-05/C1：注入共享读门实例（携带 MaterialEligibilityPort）；直连构造回退内置实现。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSourceReadGate(MemorySourceReadGate sourceReadGate) {
        this.sourceReadGate = sourceReadGate;
    }
}
