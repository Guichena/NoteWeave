package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lease-bound search over every window in the source snapshots frozen into an agent task. */
@Service
public class ResearchWorkspaceWindowSearchService {
    private static final int MAX_QUERIES = 8;
    private static final int MAX_RESULTS = 16;
    private static final int MAX_MATCH_TERMS = 24;
    private static final int MAX_CANDIDATES = 256;
    private static final Pattern ASCII_TERM = Pattern.compile("[a-zA-Z0-9]{3,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]+");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer;

    public ResearchWorkspaceWindowSearchService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.snapshotCanonicalizer = snapshotCanonicalizer;
    }

    @Transactional(readOnly = true)
    public List<WindowHit> search(SearchCommand command) {
        validate(command);
        SearchTask task = loadTask(command.taskId());
        if (task == null || !task.leaseValid() || !task.runnable()
                || !command.workerInstanceId().equals(task.workerInstanceId())
                || command.leaseEpoch() != task.leaseEpoch()
                || command.fencingToken() != task.fencingToken()) {
            throw staleLease();
        }
        ResearchAgentTaskSnapshotCanonicalizer.CanonicalSnapshot canonical = snapshotCanonicalizer.canonicalize(
                new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                        task.snapshotSchemaVersion(), task.id(), task.runId(), task.workspaceId(), task.role(),
                        task.entityId(), task.branchId(), task.planRevision(), task.entitySetVersion(),
                        task.leaseEpoch(), task.fencingToken(), task.targetBindingsJson(), task.budgetJson(),
                        task.executionContextJson(), task.logicalTaskKey(), task.quorumGroupKey(),
                        task.candidateQuorum(), task.candidateSlot(), task.candidateQuorum() == 2));
        if (!canonical.digest().equals(task.snapshotDigest())) {
            throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot digest is invalid");
        }

        List<SourceSnapshot> scope = sourceScope(task.executionContextJson());
        if (scope.isEmpty()) return List.of();
        List<String> terms = matchTerms(command.queries());
        List<WindowRow> candidates = queryCandidates(task.workspaceId(), scope, terms);
        int limit = Math.min(MAX_RESULTS, Math.max(1, command.limit()));
        return candidates.stream()
                .map(row -> score(row, command.queries()))
                .sorted(Comparator.comparingInt(ScoredWindow::scorePpm).reversed()
                        .thenComparing(item -> item.row().sourceId())
                        .thenComparingInt(item -> item.row().chunkNo())
                        .thenComparingInt(item -> item.row().windowNo()))
                .limit(limit)
                .map(item -> new WindowHit(
                        item.row().windowId(), item.row().snapshotId(), item.row().sourceId(),
                        item.row().sourceTitle(), item.query(), item.row().content(), item.scorePpm()))
                .toList();
    }

    private SearchTask loadTask(String taskId) {
        return jdbcTemplate.query("""
                select rat.id, rat.research_run_id, rr.workspace_id, rr.status as run_status,
                       rr.agent_execution_mode, rat.status, rat.worker_instance_id, rat.lease_epoch,
                       rat.fencing_token, rat.role, rat.entity_id, rat.branch_id, rat.plan_revision,
                       rat.entity_set_version, rat.target_bindings_json, rat.budget_json,
                       rat.execution_context_json, rat.snapshot_schema_version, rat.snapshot_digest,
                       rat.logical_task_key, rat.quorum_group_key, rat.candidate_quorum, rat.candidate_slot,
                       case when rat.lease_expires_at > current_timestamp then true else false end as lease_valid
                from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ?
                """, rs -> rs.next() ? new SearchTask(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("workspace_id"),
                rs.getString("run_status"), rs.getString("agent_execution_mode"), rs.getString("status"),
                rs.getString("worker_instance_id"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                rs.getString("role"), rs.getString("entity_id"), rs.getString("branch_id"),
                rs.getInt("plan_revision"), rs.getInt("entity_set_version"),
                rs.getString("target_bindings_json"), rs.getString("budget_json"),
                rs.getString("execution_context_json"), rs.getString("snapshot_schema_version"),
                rs.getString("snapshot_digest"), rs.getString("logical_task_key"),
                rs.getString("quorum_group_key"), rs.getInt("candidate_quorum"),
                rs.getInt("candidate_slot"), rs.getBoolean("lease_valid")) : null, taskId);
    }

    private List<SourceSnapshot> sourceScope(String contextJson) {
        try {
            Map<String, Object> context = objectMapper.readValue(contextJson, new TypeReference<>() { });
            if (!(context.get("source_policy") instanceof Map<?, ?> policy)
                    || !(policy.get("source_scope") instanceof List<?> rawScope)) {
                throw invalid();
            }
            List<SourceSnapshot> result = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (Object raw : rawScope) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                String sourceId = text(item.get("source_id"));
                String snapshotId = text(item.get("source_snapshot_id"));
                if (sourceId.isBlank() || snapshotId.isBlank()) continue;
                if (!identities.add(sourceId + "\u0000" + snapshotId)) throw invalid();
                result.add(new SourceSnapshot(sourceId, snapshotId));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private List<WindowRow> queryCandidates(String workspaceId, List<SourceSnapshot> scope, List<String> terms) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        StringBuilder scoped = new StringBuilder();
        for (SourceSnapshot item : scope) {
            if (!scoped.isEmpty()) scoped.append(" or ");
            scoped.append("(sc.source_id = ? and sc.source_snapshot_id = ?)");
            parameters.add(item.sourceId());
            parameters.add(item.snapshotId());
        }
        StringBuilder matched = new StringBuilder();
        for (String term : terms) {
            if (!matched.isEmpty()) matched.append(" or ");
            matched.append("lower(sw.content) like ?");
            parameters.add("%" + term.toLowerCase(java.util.Locale.ROOT) + "%");
        }
        parameters.add(MAX_CANDIDATES);
        String sql = """
                select sw.id as window_id, sc.source_snapshot_id, sc.source_id, s.title,
                       sc.chunk_no, sw.window_no, sw.content
                from source_window sw
                join source_chunk sc on sc.id = sw.source_chunk_id
                join source s on s.id = sc.source_id
                where sc.workspace_id = ? and (%s)%s
                order by sc.source_id, sc.chunk_no, sw.window_no
                limit ?
                """.formatted(scoped, matched.isEmpty() ? "" : " and (" + matched + ")");
        return jdbcTemplate.query(sql, (rs, rowNum) -> new WindowRow(
                rs.getString("window_id"), rs.getString("source_snapshot_id"), rs.getString("source_id"),
                rs.getString("title"), rs.getInt("chunk_no"), rs.getInt("window_no"),
                rs.getString("content")), parameters.toArray());
    }

    private ScoredWindow score(WindowRow row, List<String> queries) {
        String normalizedContent = row.content().toLowerCase(java.util.Locale.ROOT);
        String bestQuery = queries.get(0);
        int bestScore = 0;
        for (String query : queries) {
            List<String> terms = terms(query);
            int matched = (int) terms.stream().filter(normalizedContent::contains).count();
            int coverage = terms.isEmpty() ? 0 : (int) Math.round(900_000d * matched / terms.size());
            if (row.sourceTitle().toLowerCase(java.util.Locale.ROOT)
                    .contains(query.toLowerCase(java.util.Locale.ROOT))) coverage += 50_000;
            if (coverage > bestScore) {
                bestScore = coverage;
                bestQuery = query;
            }
        }
        return new ScoredWindow(row, bestQuery, Math.min(1_000_000, bestScore));
    }

    private List<String> matchTerms(List<String> queries) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String query : queries) {
            result.addAll(terms(query));
            if (result.size() >= MAX_MATCH_TERMS) break;
        }
        return result.stream().limit(MAX_MATCH_TERMS).toList();
    }

    private List<String> terms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher ascii = ASCII_TERM.matcher(value.toLowerCase(java.util.Locale.ROOT));
        while (ascii.find()) result.add(ascii.group());
        Matcher cjk = CJK_RUN.matcher(value);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) result.add(run);
            for (int index = 0; index + 1 < run.length(); index++) result.add(run.substring(index, index + 2));
        }
        return List.copyOf(result);
    }

    private void validate(SearchCommand command) {
        if (command == null || blank(command.taskId()) || blank(command.workerInstanceId())
                || command.leaseEpoch() < 1 || command.fencingToken() < 1
                || command.queries() == null || command.queries().isEmpty()
                || command.queries().size() > MAX_QUERIES || command.limit() < 1 || command.limit() > MAX_RESULTS
                || command.queries().stream().anyMatch(query -> blank(query) || query.length() > 1_000)) {
            throw invalid();
        }
    }

    private String text(Object value) { return value instanceof String text ? text.trim() : ""; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private BusinessException invalid() {
        return new BusinessException("RESEARCH_AGENT_WORKSPACE_SEARCH_INVALID", "Workspace window search request is invalid");
    }
    private BusinessException staleLease() {
        return new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Research agent lease is stale or owned by another worker");
    }

    public record SearchCommand(String taskId, String workerInstanceId, int leaseEpoch, long fencingToken,
                                List<String> queries, int limit) { }
    public record WindowHit(String sourceWindowId, String sourceSnapshotId, String sourceId,
                            String sourceTitle, String query, String windowText, int scorePpm) { }
    private record SourceSnapshot(String sourceId, String snapshotId) { }
    private record WindowRow(String windowId, String snapshotId, String sourceId, String sourceTitle,
                             int chunkNo, int windowNo, String content) { }
    private record ScoredWindow(WindowRow row, String query, int scorePpm) { }
    private record SearchTask(
            String id, String runId, String workspaceId, String runStatus, String executionMode, String status,
            String workerInstanceId, int leaseEpoch, long fencingToken, String role, String entityId,
            String branchId, int planRevision, int entitySetVersion, String targetBindingsJson,
            String budgetJson, String executionContextJson, String snapshotSchemaVersion, String snapshotDigest,
            String logicalTaskKey, String quorumGroupKey, int candidateQuorum, int candidateSlot,
            boolean leaseValid
    ) {
        boolean runnable() {
            return "INCREMENTAL_V1".equals(executionMode)
                    && !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(runStatus)
                    && Set.of("CLAIMED", "RUNNING").contains(status);
        }
    }
}
