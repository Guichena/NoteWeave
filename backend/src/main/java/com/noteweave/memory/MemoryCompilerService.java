package com.noteweave.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.common.Ids;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryCompilerService {

    private final JdbcTemplate jdbcTemplate;
    private final MemoryCompilerReadRepository readRepository;
    private final ObjectProvider<CapabilityCatalogPort> capabilityCatalogProvider;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryCompilerPolicy compilerPolicy;
    private final MemoryCompiledPackCache compiledPackCache;
    private final MeterRegistry meterRegistry;

    public MemoryCompilerService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<CapabilityCatalogPort> capabilityCatalogProvider,
            CurrentUserProvider currentUserProvider,
            MemoryCompilerPolicy compilerPolicy,
            MemoryCompiledPackCache compiledPackCache,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.readRepository = new MemoryCompilerReadRepository(jdbcTemplate, objectMapper);
        this.capabilityCatalogProvider = capabilityCatalogProvider;
        this.currentUserProvider = currentUserProvider;
        this.compilerPolicy = compilerPolicy;
        this.compiledPackCache = compiledPackCache;
        this.meterRegistry = meterRegistry;
    }

    public MemoryControlPackResponse compileChatControlPack(String workspaceId, String answerMode) {
        String normalizedMode = MemorySignalService.normalizeToken(answerMode);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("CHAT");
        neighborhoods.add("CHAT_" + normalizedMode);
        return compilePack(workspaceId, "chat", normalizedMode, "CHAT_" + normalizedMode, neighborhoods,
                List.of("Memory 不作为事实来源，事实内容必须来自工作台资料池、原文窗口或 Wiki 页面"),
                List.of());
    }

    public MemoryControlPackResponse compileArtifactControlPack(String workspaceId, String skillKey) {
        return compileArtifactControlPackForActor(workspaceId, skillKey,
                currentUserProvider.requireUserId());
    }

    public MemoryControlPackResponse compileArtifactControlPackForActor(
            String workspaceId, String skillKey, String actorUserId) {
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
                ), degradationReasons, actorUserId);
    }

    public MemoryControlPackResponse compileResearchControlPack(String workspaceId, String profileKey) {
        String normalizedProfile = MemorySignalService.normalizeToken(profileKey);
        Set<String> neighborhoods = new LinkedHashSet<>();
        neighborhoods.add("COMMON");
        neighborhoods.add("RESEARCH");
        neighborhoods.add("RESEARCH_" + normalizedProfile);
        return compilePack(workspaceId, "research", normalizedProfile, "RESEARCH_" + normalizedProfile, neighborhoods,
                List.of(
                        "Memory 不作为研究证据",
                        "研究结论必须由搜索结果、工作台资料或验证证据支持"
                ), List.of());
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
            List<String> degradationReasons
    ) {
        return compilePack(workspaceId, packType, targetKey, taskNeighborhood,
                allowedNeighborhoods, evidencePolicy, degradationReasons,
                currentUserProvider.requireUserId());
    }

    private MemoryControlPackResponse compilePack(
            String workspaceId,
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy,
            List<String> degradationReasons,
            String currentUserId
    ) {
        List<MemoryCompilerReadRepository.StateRow> stateRows = loadStateRows(workspaceId);
        List<MemoryCompilerReadRepository.StateRow> eligibleState = stateRows.stream()
                .filter(row -> compilerPolicy.scopeAllowed(
                        row.memoryScope(), row.ownerUserId(), currentUserId))
                .filter(row -> compilerPolicy.neighborhoodPriority(
                        row.taskNeighborhoods(), taskNeighborhood, allowedNeighborhoods) < 3)
                .sorted(java.util.Comparator.comparing(
                        MemoryCompilerReadRepository.StateRow::memoryObjectId))
                .toList();
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
                        degradationReasons),
                compilerPolicy.version(),
                stateFingerprint(eligibleState)
        );
        java.util.Optional<MemoryControlPackResponse> cached = compiledPackCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<MemoryCompilerReadRepository.ObjectRow> rows = loadObjectRows(workspaceId);
        List<RankedObject> rankedRows = rows.stream()
                .filter(row -> compilerPolicy.scopeAllowed(
                        row.memoryScope(), row.ownerUserId(), currentUserId))
                .map(row -> new RankedObject(
                        row,
                        compilerPolicy.scopePriority(row.memoryScope()),
                        compilerPolicy.neighborhoodPriority(
                                row.taskNeighborhoods(), taskNeighborhood, allowedNeighborhoods)))
                .filter(row -> row.neighborhoodPriority() < 3)
                .sorted((left, right) -> compilerPolicy.comparator().compare(
                        left.rankable(), right.rankable()))
                .toList();

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

        for (RankedObject ranked : rankedRows) {
            MemoryCompilerReadRepository.ObjectRow row = ranked.row();
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
                continue;
            }
            selectedTokens += incrementalTokens;
            memoryObjectIds.add(row.memoryObjectId());
            memoryReferences.add(new MemoryReferenceResponse(
                    row.memoryObjectId(),
                    row.memoryVersionId(),
                    row.utilityScore(),
                    ranked.scopePriority(),
                    ranked.neighborhoodPriority(),
                    selectionReason(ranked)));
            styleConstraints.addAll(row.compileHints().styleConstraints());
            structureConstraints.addAll(row.compileHints().structureConstraints());
            terminologyPolicy.addAll(row.compileHints().terminologyPolicy());
            forbiddenPatterns.addAll(row.compileHints().forbiddenPatterns());
            interactionPolicy.addAll(row.compileHints().interactionPolicy());
            reviewChecklist.addAll(row.compileHints().reviewChecklist());
        }

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
                        rankedRows.size(),
                        memoryReferences.size(),
                        rankedRows.size() - memoryReferences.size(),
                        rankedRows.size() > memoryReferences.size(),
                        !degradationReasons.isEmpty(),
                        degradationReasons
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

    private String requestFingerprint(
            String packType,
            String targetKey,
            String taskNeighborhood,
            Set<String> allowedNeighborhoods,
            List<String> evidencePolicy,
            List<String> degradationReasons
    ) {
        List<String> fields = new ArrayList<>();
        fields.add(packType);
        fields.add(targetKey);
        fields.add(taskNeighborhood);
        allowedNeighborhoods.stream().sorted().forEach(value -> fields.add("n:" + value));
        evidencePolicy.forEach(value -> fields.add("e:" + value));
        degradationReasons.forEach(value -> fields.add("d:" + value));
        return fingerprint(fields);
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

    private String selectionReason(RankedObject ranked) {
        return "scope_priority=" + ranked.scopePriority()
                + ";neighborhood_priority=" + ranked.neighborhoodPriority()
                + ";utility=" + ranked.row().utilityScore()
                + ";fresh_at=" + ranked.row().updatedAt();
    }

    private String normalizeCapabilityKey(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private record RankedObject(
            MemoryCompilerReadRepository.ObjectRow row,
            int scopePriority,
            int neighborhoodPriority
    ) {
        private MemoryCompilerPolicy.RankableMemory rankable() {
            return new MemoryCompilerPolicy.RankableMemory(
                    row.memoryObjectId(),
                    scopePriority,
                    neighborhoodPriority,
                    row.utilityScore(),
                    row.updatedAt()
            );
        }
    }
}
