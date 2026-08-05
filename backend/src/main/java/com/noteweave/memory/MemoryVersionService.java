package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryVersionService {

    public static final String LIFECYCLE_POLICY_VERSION = "memory-lifecycle-policy-v1";
    private static final String LEGACY_BOOTSTRAP_POLICY_VERSION = "memory-legacy-bootstrap-v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public MemoryVersionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    MemoryVersionResponse createInitialVersion(InitialVersionCommand command) {
        String versionId = Ids.newId();
        Instant validFrom = Instant.now();
        jdbcTemplate.update("""
                insert into memory_version(
                    id, memory_object_id, workspace_id, version_no, canonical_statement,
                    task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                    status, supersedes_version_id, valid_from, valid_to,
                    created_from_candidate_id, policy_version, risk_score, scope_status
                ) values (?, ?, ?, 1, ?, ?, ?, ?, 'ACTIVE', null, ?, null, ?, ?, ?, ?)
                """,
                versionId,
                command.memoryObjectId(),
                command.workspaceId(),
                command.canonicalStatement(),
                Json.write(objectMapper, command.taskNeighborhoods()),
                Json.write(objectMapper, command.compileHints()),
                Json.write(objectMapper, command.forbiddenPatterns()),
                Timestamp.from(validFrom),
                command.createdFromCandidateId(),
                command.policyVersion(),
                command.riskScore(),
                command.scopeStatus()
        );
        int updated = jdbcTemplate.update("""
                update memory_object
                set latest_version_id = ?, current_version_no = 1, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                  and latest_version_id is null and current_version_no = 0
                """, versionId, command.workspaceId(), command.memoryObjectId());
        if (updated != 1) {
            throw new BusinessException(
                    "MEMORY_VERSION_INITIALIZATION_CONFLICT",
                    "Memory object 已存在初始版本"
            );
        }
        MemoryVersionResponse response = new MemoryVersionResponse(
                versionId,
                command.memoryObjectId(),
                command.workspaceId(),
                1,
                command.canonicalStatement(),
                List.copyOf(command.taskNeighborhoods()),
                MemoryCompileHintsResponse.from(command.compileHints()),
                List.copyOf(command.forbiddenPatterns()),
                "ACTIVE",
                null,
                validFrom,
                null,
                command.createdFromCandidateId(),
                command.policyVersion(),
                command.riskScore(),
                command.scopeStatus()
        );
        return response;
    }

    @Transactional
    public MemoryVersionResponse appendVersion(
            String workspaceId,
            String memoryObjectId,
            AppendMemoryVersionRequest request
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        ObjectState state = lockObject(workspaceId, memoryObjectId);
        MemoryVersionResponse previous = currentVersionOrBootstrap(state);
        if (!"ACTIVE".equals(previous.status())) {
            throw new BusinessException(
                    "MEMORY_VERSION_NOT_ACTIVE",
                    "只有 ACTIVE Memory version 可以被 supersede"
            );
        }

        Instant now = Instant.now();
        String versionId = Ids.newId();
        int versionNo = previous.versionNo() + 1;
        MemorySignalService.MemoryCompileHints compileHints = request.compileHints();
        jdbcTemplate.update("""
                insert into memory_version(
                    id, memory_object_id, workspace_id, version_no, canonical_statement,
                    task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                    status, supersedes_version_id, valid_from, valid_to,
                    created_from_candidate_id, policy_version, risk_score, scope_status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, null, null, ?, ?, ?)
                """,
                versionId,
                memoryObjectId,
                workspaceId,
                versionNo,
                request.canonicalStatement(),
                Json.write(objectMapper, request.taskNeighborhoods()),
                Json.write(objectMapper, compileHints),
                Json.write(objectMapper, request.forbiddenPatterns()),
                previous.memoryVersionId(),
                Timestamp.from(now),
                LIFECYCLE_POLICY_VERSION,
                previous.riskScore(),
                previous.scopeStatus()
        );
        int closed = jdbcTemplate.update("""
                update memory_version
                set status = 'SUPERSEDED', valid_to = ?
                where workspace_id = ? and memory_object_id = ? and id = ? and status = 'ACTIVE'
                """, Timestamp.from(now), workspaceId, memoryObjectId, previous.memoryVersionId());
        if (closed != 1) {
            throw new BusinessException(
                    "MEMORY_VERSION_SUPERSEDE_CONFLICT",
                    "Memory latest version 已发生变化"
            );
        }
        jdbcTemplate.update("""
                update memory_object
                set canonical_statement = ?, task_neighborhood_json = ?,
                    compile_policy_json = ?, forbidden_pattern_json = ?,
                    latest_version_id = ?, current_version_no = ?, status = 'ACTIVE',
                    review_status = 'APPROVED',
                    updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """,
                request.canonicalStatement(),
                Json.write(objectMapper, request.taskNeighborhoods()),
                Json.write(objectMapper, compileHints),
                Json.write(objectMapper, request.forbiddenPatterns()),
                versionId,
                versionNo,
                workspaceId,
                memoryObjectId
        );
        MemoryVersionResponse response = new MemoryVersionResponse(
                versionId,
                memoryObjectId,
                workspaceId,
                versionNo,
                request.canonicalStatement(),
                request.taskNeighborhoods(),
                MemoryCompileHintsResponse.from(compileHints),
                request.forbiddenPatterns(),
                "ACTIVE",
                previous.memoryVersionId(),
                now,
                null,
                null,
                LIFECYCLE_POLICY_VERSION,
                previous.riskScore(),
                previous.scopeStatus()
        );
        return response;
    }

    @Transactional
    public MemoryVersionResponse revoke(String workspaceId, String memoryObjectId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        ObjectState state = lockObject(workspaceId, memoryObjectId);
        MemoryVersionResponse current = currentVersionOrBootstrap(state);
        if ("REVOKED".equals(current.status()) || "REVOKED".equals(state.status())) {
            return current;
        }
        if (!"ACTIVE".equals(current.status())) {
            throw new BusinessException(
                    "MEMORY_VERSION_NOT_ACTIVE",
                    "只有 ACTIVE Memory version 可以撤销"
            );
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update memory_version
                set status = 'REVOKED', valid_to = ?
                where workspace_id = ? and memory_object_id = ? and id = ? and status = 'ACTIVE'
                """, Timestamp.from(now), workspaceId, memoryObjectId, current.memoryVersionId());
        jdbcTemplate.update("""
                update memory_object
                set status = 'REVOKED', updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, workspaceId, memoryObjectId);
        MemoryVersionResponse response = new MemoryVersionResponse(
                current.memoryVersionId(),
                current.memoryObjectId(),
                current.workspaceId(),
                current.versionNo(),
                current.canonicalStatement(),
                current.taskNeighborhoods(),
                current.compileHints(),
                current.forbiddenPatterns(),
                "REVOKED",
                current.supersedesVersionId(),
                current.validFrom(),
                now,
                current.createdFromCandidateId(),
                current.policyVersion(),
                current.riskScore(),
                current.scopeStatus()
        );
        return response;
    }

    public List<MemoryVersionResponse> listVersions(String workspaceId, String memoryObjectId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        requireObject(workspaceId, memoryObjectId);
        return jdbcTemplate.query("""
                select id, memory_object_id, workspace_id, version_no, canonical_statement,
                       task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                       status, supersedes_version_id, valid_from, valid_to,
                       created_from_candidate_id, policy_version, risk_score, scope_status
                from memory_version
                where workspace_id = ? and memory_object_id = ?
                order by version_no desc
                """, (rs, rowNum) -> mapVersion(
                rs.getString("id"),
                rs.getString("memory_object_id"),
                rs.getString("workspace_id"),
                rs.getInt("version_no"),
                rs.getString("canonical_statement"),
                rs.getString("task_neighborhood_json"),
                rs.getString("compile_policy_json"),
                rs.getString("forbidden_pattern_json"),
                rs.getString("status"),
                rs.getString("supersedes_version_id"),
                rs.getTimestamp("valid_from"),
                rs.getTimestamp("valid_to"),
                rs.getString("created_from_candidate_id"),
                rs.getString("policy_version"),
                rs.getDouble("risk_score"),
                rs.getString("scope_status")
        ), workspaceId, memoryObjectId);
    }

    public MemoryVersionResponse getVersion(
            String workspaceId,
            String memoryObjectId,
            String memoryVersionId
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        List<MemoryVersionResponse> versions = jdbcTemplate.query("""
                select id, memory_object_id, workspace_id, version_no, canonical_statement,
                       task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                       status, supersedes_version_id, valid_from, valid_to,
                       created_from_candidate_id, policy_version, risk_score, scope_status
                from memory_version
                where workspace_id = ? and memory_object_id = ? and id = ?
                """, (rs, rowNum) -> mapVersion(
                rs.getString("id"),
                rs.getString("memory_object_id"),
                rs.getString("workspace_id"),
                rs.getInt("version_no"),
                rs.getString("canonical_statement"),
                rs.getString("task_neighborhood_json"),
                rs.getString("compile_policy_json"),
                rs.getString("forbidden_pattern_json"),
                rs.getString("status"),
                rs.getString("supersedes_version_id"),
                rs.getTimestamp("valid_from"),
                rs.getTimestamp("valid_to"),
                rs.getString("created_from_candidate_id"),
                rs.getString("policy_version"),
                rs.getDouble("risk_score"),
                rs.getString("scope_status")
        ), workspaceId, memoryObjectId, memoryVersionId);
        if (versions.isEmpty()) {
            throw new BusinessException("MEMORY_VERSION_NOT_FOUND", "Memory version 不存在");
        }
        return versions.get(0);
    }

    private MemoryVersionResponse mapVersion(
            String memoryVersionId,
            String memoryObjectId,
            String workspaceId,
            int versionNo,
            String canonicalStatement,
            String taskNeighborhoodJson,
            String compilePolicyJson,
            String forbiddenPatternJson,
            String status,
            String supersedesVersionId,
            Timestamp validFrom,
            Timestamp validTo,
            String createdFromCandidateId,
            String policyVersion,
            double riskScore,
            String scopeStatus
    ) {
        return new MemoryVersionResponse(
                memoryVersionId,
                memoryObjectId,
                workspaceId,
                versionNo,
                canonicalStatement,
                readStringList(taskNeighborhoodJson),
                MemoryCompileHintsResponse.from(readCompileHints(compilePolicyJson)),
                readStringList(forbiddenPatternJson),
                status,
                supersedesVersionId,
                toInstant(validFrom),
                toInstant(validTo),
                createdFromCandidateId,
                policyVersion,
                riskScore,
                scopeStatus
        );
    }

    private void requireObject(String workspaceId, String memoryObjectId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_object where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, memoryObjectId);
        if (count == null || count == 0) {
            throw new BusinessException("MEMORY_OBJECT_NOT_FOUND", "Memory object 不存在");
        }
    }

    private ObjectState lockObject(String workspaceId, String memoryObjectId) {
        List<ObjectState> states = jdbcTemplate.query("""
                select id, workspace_id, latest_version_id, current_version_no, status,
                       canonical_statement, task_neighborhood_json, compile_policy_json,
                       forbidden_pattern_json
                from memory_object
                where workspace_id = ? and id = ?
                for update
                """, (rs, rowNum) -> new ObjectState(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("latest_version_id"),
                rs.getInt("current_version_no"),
                rs.getString("status"),
                rs.getString("canonical_statement"),
                rs.getString("task_neighborhood_json"),
                rs.getString("compile_policy_json"),
                rs.getString("forbidden_pattern_json")
        ), workspaceId, memoryObjectId);
        if (states.isEmpty()) {
            throw new BusinessException("MEMORY_OBJECT_NOT_FOUND", "Memory object 不存在");
        }
        return states.get(0);
    }

    private MemoryVersionResponse currentVersionOrBootstrap(ObjectState state) {
        if (state.latestVersionId() != null && !state.latestVersionId().isBlank()) {
            return findVersionInternal(
                    state.workspaceId(), state.memoryObjectId(), state.latestVersionId());
        }
        String versionId = Ids.newId();
        Instant validFrom = Instant.now();
        List<String> neighborhoods = readStringList(state.taskNeighborhoodJson());
        MemorySignalService.MemoryCompileHints compileHints =
                readCompileHints(state.compilePolicyJson());
        List<String> forbiddenPatterns = readStringList(state.forbiddenPatternJson());
        jdbcTemplate.update("""
                insert into memory_version(
                    id, memory_object_id, workspace_id, version_no, canonical_statement,
                    task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                    status, supersedes_version_id, valid_from, valid_to,
                    created_from_candidate_id, policy_version, risk_score, scope_status
                ) values (?, ?, ?, 1, ?, ?, ?, ?, 'ACTIVE', null, ?, null, null, ?, 0.0000, 'VALID')
                """,
                versionId,
                state.memoryObjectId(),
                state.workspaceId(),
                state.canonicalStatement(),
                state.taskNeighborhoodJson(),
                state.compilePolicyJson(),
                state.forbiddenPatternJson(),
                Timestamp.from(validFrom),
                LEGACY_BOOTSTRAP_POLICY_VERSION
        );
        jdbcTemplate.update("""
                update memory_object
                set latest_version_id = ?, current_version_no = 1, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, versionId, state.workspaceId(), state.memoryObjectId());
        return new MemoryVersionResponse(
                versionId,
                state.memoryObjectId(),
                state.workspaceId(),
                1,
                state.canonicalStatement(),
                neighborhoods,
                MemoryCompileHintsResponse.from(compileHints),
                forbiddenPatterns,
                "ACTIVE",
                null,
                validFrom,
                null,
                null,
                LEGACY_BOOTSTRAP_POLICY_VERSION,
                0.0,
                "VALID"
        );
    }

    private MemoryVersionResponse findVersionInternal(
            String workspaceId,
            String memoryObjectId,
            String memoryVersionId
    ) {
        List<MemoryVersionResponse> versions = jdbcTemplate.query("""
                select id, memory_object_id, workspace_id, version_no, canonical_statement,
                       task_neighborhood_json, compile_policy_json, forbidden_pattern_json,
                       status, supersedes_version_id, valid_from, valid_to,
                       created_from_candidate_id, policy_version, risk_score, scope_status
                from memory_version
                where workspace_id = ? and memory_object_id = ? and id = ?
                """, (rs, rowNum) -> mapVersion(
                rs.getString("id"),
                rs.getString("memory_object_id"),
                rs.getString("workspace_id"),
                rs.getInt("version_no"),
                rs.getString("canonical_statement"),
                rs.getString("task_neighborhood_json"),
                rs.getString("compile_policy_json"),
                rs.getString("forbidden_pattern_json"),
                rs.getString("status"),
                rs.getString("supersedes_version_id"),
                rs.getTimestamp("valid_from"),
                rs.getTimestamp("valid_to"),
                rs.getString("created_from_candidate_id"),
                rs.getString("policy_version"),
                rs.getDouble("risk_score"),
                rs.getString("scope_status")
        ), workspaceId, memoryObjectId, memoryVersionId);
        if (versions.isEmpty()) {
            throw new BusinessException("MEMORY_VERSION_NOT_FOUND", "Memory version 不存在");
        }
        return versions.get(0);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("MEMORY_JSON_PARSE_FAILED", "Memory JSON 解析失败");
        }
    }

    private MemorySignalService.MemoryCompileHints readCompileHints(String json) {
        if (json == null || json.isBlank()) {
            return new MemorySignalService.MemoryCompileHints(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "MEMORY_COMPILE_POLICY_PARSE_FAILED",
                    "Memory compile policy 解析失败"
            );
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    record InitialVersionCommand(
            String workspaceId,
            String memoryObjectId,
            String canonicalStatement,
            List<String> taskNeighborhoods,
            MemorySignalService.MemoryCompileHints compileHints,
            List<String> forbiddenPatterns,
            String createdFromCandidateId,
            String policyVersion,
            double riskScore,
            String scopeStatus
    ) {
    }

    private record ObjectState(
            String memoryObjectId,
            String workspaceId,
            String latestVersionId,
            int currentVersionNo,
            String status,
            String canonicalStatement,
            String taskNeighborhoodJson,
            String compilePolicyJson,
            String forbiddenPatternJson
    ) {
    }
}
