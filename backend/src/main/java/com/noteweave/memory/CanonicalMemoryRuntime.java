package com.noteweave.memory;

import com.noteweave.security.CurrentUserProvider;
import com.noteweave.common.Ids;
import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonicalMemoryRuntime implements MemoryRuntime {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryCompilerPolicy compilerPolicy;

    public CanonicalMemoryRuntime(
            JdbcTemplate jdbcTemplate,
            CurrentUserProvider currentUserProvider,
            MemoryCompilerPolicy compilerPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserProvider = currentUserProvider;
        this.compilerPolicy = compilerPolicy;
    }

    @Override
    public MemoryRuntimePack recall(MemoryRuntimeQuery query) {
        String currentUserId = currentUserProvider.requireUserId();
        List<RuntimeRow> rows = jdbcTemplate.query("""
                select i.id,
                       r.id as memory_revision_id,
                       i.utility_score,
                       i.memory_scope,
                       i.owner_user_id,
                       i.updated_at
                from memory_item i
                join memory_runtime_revision r
                  on r.id = i.current_revision_id
                 and r.memory_item_id = i.id
                where i.workspace_id = ?
                  and i.status = 'ACTIVE'
                  and r.status = 'ACTIVE'
                  and r.valid_from <= current_timestamp
                  and (r.valid_until is null or r.valid_until > current_timestamp)
                """, (rs, rowNum) -> new RuntimeRow(
                rs.getString("id"),
                rs.getString("memory_revision_id"),
                rs.getDouble("utility_score"),
                rs.getString("memory_scope"),
                rs.getString("owner_user_id"),
                rs.getTimestamp("updated_at").toInstant()
        ), query.workspaceId());

        List<MemoryReferenceResponse> references = rows.stream()
                .filter(row -> compilerPolicy.scopeAllowed(
                        row.memoryScope(), row.ownerUserId(), currentUserId))
                .sorted(Comparator
                        .comparingInt((RuntimeRow row) -> compilerPolicy.scopePriority(row.memoryScope()))
                        .thenComparing(Comparator.comparingDouble(RuntimeRow::utilityScore).reversed())
                        .thenComparing(RuntimeRow::updatedAt, Comparator.reverseOrder())
                        .thenComparing(RuntimeRow::memoryObjectId))
                .map(row -> new MemoryReferenceResponse(
                        row.memoryObjectId(),
                        row.memoryVersionId(),
                        row.utilityScore(),
                        compilerPolicy.scopePriority(row.memoryScope()),
                        0,
                        "runtime-active-revision"))
                .toList();
        return new MemoryRuntimePack(MemoryRuntimePack.POLICY_VERSION, references);
    }

    @Override
    @Transactional
    public MemoryObservationResult observe(ExecutionObservation observation) {
        if (!java.util.Set.of("USER_FEEDBACK", "PROJECT_DECISION").contains(observation.provenanceType())) {
            throw new BusinessException("MEMORY_OBSERVATION_PROVENANCE_FORBIDDEN",
                    "Only trusted user or project observations may write memory");
        }
        if (!java.util.Set.of("USER", "WORKSPACE").contains(observation.scope())) {
            throw new BusinessException("MEMORY_OBSERVATION_SCOPE_INVALID",
                    "Memory observation scope must be USER or WORKSPACE");
        }
        MemoryObservationResult duplicate = findObservation(observation.observationId());
        if (duplicate != null) {
            return duplicate;
        }
        String owner = currentUserProvider.requireUserId();
        String scope = observation.scope();
        String scopeRef = "USER".equals(scope) ? owner : observation.workspaceId();
        List<String> items = findItems(observation, owner, scope, scopeRef);
        if (items.isEmpty()) {
            try {
                jdbcTemplate.update("insert into memory_item(id,workspace_id,owner_user_id,memory_scope,scope_ref_key,slot_key,slot_schema_version,status,current_revision_id,lock_version) values (?,?,?,?,?,?,'observation-v1','EMPTY',null,0)", Ids.newId(), observation.workspaceId(), owner, scope, scopeRef, observation.slotKey());
            } catch (DuplicateKeyException ignored) {
                // A concurrent observation won the unique scope/slot insert.
            }
        }
        String itemId = jdbcTemplate.queryForObject("select id from memory_item where workspace_id = ? and owner_user_id = ? and memory_scope = ? and scope_ref_key = ? and slot_key = ? for update", String.class, observation.workspaceId(), owner, scope, scopeRef, observation.slotKey());
        duplicate = findObservation(observation.observationId());
        if (duplicate != null) {
            return duplicate;
        }
        Integer lastVersion = jdbcTemplate.queryForObject("select coalesce(max(version_no), 0) from memory_runtime_revision where memory_item_id = ?", Integer.class, itemId);
        String revisionId = Ids.newId();
        String contentHash = contentHash(observation);
        jdbcTemplate.update("insert into memory_runtime_revision(id,memory_item_id,workspace_id,version_no,status,display_text,provenance_type,provenance_ref,observation_id,content_hash) values (?,?,?,?, 'PROPOSED',?,?,?,?,?)", revisionId,itemId,observation.workspaceId(), lastVersion + 1, observation.displayText(),observation.provenanceType(),observation.provenanceRef(),observation.observationId(),contentHash);
        return new MemoryObservationResult(itemId, revisionId, false);
    }

    private String contentHash(ExecutionObservation observation) {
        String canonical = String.join("\n",
                observation.displayText().strip(),
                observation.provenanceType().strip().toUpperCase(java.util.Locale.ROOT),
                observation.provenanceRef().strip());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the memory revision contract", exception);
        }
    }

    private List<String> findItems(ExecutionObservation observation, String owner, String scope, String scopeRef) {
        return jdbcTemplate.query("select id from memory_item where workspace_id = ? and owner_user_id = ? and memory_scope = ? and scope_ref_key = ? and slot_key = ?", (rs, row) -> rs.getString(1), observation.workspaceId(), owner, scope, scopeRef, observation.slotKey());
    }

    private MemoryObservationResult findObservation(String observationId) {
        List<MemoryObservationResult> matches = jdbcTemplate.query("select memory_item_id, id from memory_runtime_revision where observation_id = ?", (rs, row) -> new MemoryObservationResult(rs.getString("memory_item_id"), rs.getString("id"), true), observationId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private record RuntimeRow(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore,
            String memoryScope,
            String ownerUserId,
            Instant updatedAt
    ) {
    }
}
