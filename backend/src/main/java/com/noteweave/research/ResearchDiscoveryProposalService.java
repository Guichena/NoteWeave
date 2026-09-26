package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Validates and optionally accepts scope proposals without granting Worker matrix authority.
 *
 * <p>DR-110: accepting a proposal is a Replan. The Decision to widen the matrix is taken here,
 * so this is also where its auditable summary is persisted - inside the same transaction that
 * writes the second {@code research_matrix_plan} row, never as a later reconstruction.
 */
@Service
class ResearchDiscoveryProposalService {
    private static final Logger log = LoggerFactory.getLogger(ResearchDiscoveryProposalService.class);
    private static final String SCHEMA = "research-discovery-proposal.v1";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,159}");
    private static final Pattern LINEAGE = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");
    /** {@code selected_repair} of a Discovery revision: the plan was widened, not re-routed. */
    private static final String SELECTED_REPAIR = "ACCEPT_DISCOVERY_PROPOSALS";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentReplanAuditService replanAudit;
    private final boolean autoAccept;
    private final double acceptedPrecision;
    private final double precisionThreshold;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchDiscoveryProposalService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentReplanAuditService replanAudit,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.wide-discovery-auto-accept:false}") boolean autoAccept,
            @Value("${noteweave.research.wide-discovery-accepted-precision:0.0}") double acceptedPrecision,
            @Value("${noteweave.research.wide-discovery-precision-threshold:0.90}") double precisionThreshold
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.replanAudit = replanAudit;
        this.featureFlags = featureFlags;
        this.autoAccept = autoAccept;
        this.acceptedPrecision = acceptedPrecision;
        this.precisionThreshold = precisionThreshold;
    }

    void validateAndPersist(
            ResearchAgentCompletionCommitter.TaskRow task,
            Map<String, Object> result,
            String roleResultId
    ) {
        if (!SCHEMA.equals(text(result.get("result_schema_version")))
                || !"WIDE_DISCOVERY".equals(text(result.get("role")))) throw invalid("Discovery schema or role is invalid");
        Map<String, Object> input = mapValue(queryPolicy(task).get("discovery_input"));
        if (input.isEmpty() || !text(input.get("input_digest")).equals(text(result.get("input_digest")))) {
            throw stale("Discovery input digest is stale");
        }
        int planRevision = integer(result.get("plan_revision"));
        int entityVersion = integer(result.get("entity_set_version"));
        if (planRevision != task.planRevision() || entityVersion != task.entitySetVersion()) {
            throw stale("Discovery plan or entity-set revision is stale");
        }
        List<Map<String, Object>> proposals = listOfMaps(result.get("proposals"));
        if (proposals.size() > 20) throw invalid("Discovery proposal count exceeds the contract");
        Set<String> keys = new HashSet<>();
        Set<String> candidateKeys = new HashSet<>();
        Set<String> identities = new HashSet<>();
        Set<String> allowedSourceIds = new HashSet<>(stringList(input.get("allowed_source_ids")));
        boolean acceptanceGate = autoAccept
                && featureFlags.enabledForRun(task.runId(), ResearchAgentFeatureFlagService.WIDE_DISCOVERY_AUTO_ACCEPT)
                && acceptedPrecision >= precisionThreshold
                && featureFlags.numberForRun(task.runId(), "wide_discovery_accepted_precision", 0.0)
                    >= featureFlags.numberForRun(task.runId(), "wide_discovery_precision_threshold", 1.0);
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Map<String, Object> proposal : proposals) {
            String proposalKey = text(proposal.get("proposal_key"));
            String candidateKey = text(proposal.get("candidate_key"));
            String type = text(proposal.get("proposal_type"));
            int confidence = integer(proposal.get("confidence_ppm"));
            if (!KEY.matcher(proposalKey).matches() || !KEY.matcher(candidateKey).matches()
                    || !keys.add(proposalKey) || !candidateKeys.add(type + ":" + candidateKey)
                    || !Set.of("ENTITY", "DIMENSION", "SEARCH_QUERY", "SOURCE_LEAD").contains(type)
                    || confidence < 0 || confidence > 1_000_000) throw invalid("Discovery proposal is malformed");
            String domain = text(proposal.get("source_domain"));
            String lineage = text(proposal.get("lineage_digest"));
            String sourceLead = text(proposal.get("source_lead"));
            if (!sourceLead.isBlank() && !allowedSourceIds.contains(sourceLead)) {
                throw invalid("Discovery source lead is outside the frozen source scope");
            }
            if ((!domain.isBlank() || !lineage.isBlank())
                    && (domain.isBlank() || !LINEAGE.matcher(lineage).matches()
                    || !identities.add(domain + "|" + lineage))) {
                throw invalid("Discovery source identity is incomplete or duplicated");
            }
            String status = acceptanceGate && confidence >= 800_000 && Set.of("ENTITY", "DIMENSION").contains(type)
                    ? "ACCEPTED" : "SHADOW";
            jdbcTemplate.update("""
                    insert into research_discovery_proposal(
                        id, research_run_id, task_id, role_result_id, proposal_key, proposal_type,
                        candidate_key, label, search_query, source_lead, source_domain, lineage_digest,
                        rationale, confidence_ppm, plan_revision, entity_set_version, proposal_status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), task.runId(), task.id(), roleResultId, proposalKey, type, candidateKey,
                    text(proposal.get("label")), text(proposal.get("search_query")), sourceLead,
                    nullable(domain), nullable(lineage), text(proposal.get("rationale")), confidence,
                    planRevision, entityVersion, status);
            if ("ACCEPTED".equals(status)) accepted.add(proposal);
        }
        if (!accepted.isEmpty()) applyAcceptedRevision(task.runId(), planRevision, entityVersion, accepted);
    }

    private void applyAcceptedRevision(
            String runId,
            int planRevision,
            int entityVersion,
            List<Map<String, Object>> accepted
    ) {
        Integer currentCells = jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, runId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "select count(*) from research_row where research_run_id = ?", Integer.class, runId);
        Integer columnCount = jdbcTemplate.queryForObject(
                "select count(distinct column_key) from research_cell where research_run_id = ?", Integer.class, runId);
        int addedCells = 0;
        for (Map<String, Object> proposal : accepted) {
            if ("DIMENSION".equals(text(proposal.get("proposal_type")))) addedCells += rowCount == null ? 0 : rowCount;
            if ("ENTITY".equals(text(proposal.get("proposal_type")))) addedCells += columnCount == null ? 0 : columnCount;
        }
        if ((currentCells == null ? 0 : currentCells) + addedCells > 80) {
            throw new BusinessException("RESEARCH_DISCOVERY_PLAN_BOUNDED", "Accepted discovery proposal exceeds 80 cells");
        }
        int nextPlanRevision = planRevision + 1;
        int nextEntityVersion = entityVersion + (accepted.stream()
                .anyMatch(item -> "ENTITY".equals(text(item.get("proposal_type")))) ? 1 : 0);
        jdbcTemplate.update("""
                update research_cell set plan_revision = ?, entity_set_version = ?, updated_at = current_timestamp
                where research_run_id = ? and plan_revision = ? and entity_set_version = ?
                """, nextPlanRevision, nextEntityVersion, runId, planRevision, entityVersion);
        String branchId = jdbcTemplate.query("""
                select id from research_branch where research_run_id = ? and branch_key = 'branch-main'
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        List<Row> rows = jdbcTemplate.query("""
                select id, row_key from research_row where research_run_id = ? order by row_key
                """, (rs, rowNum) -> new Row(rs.getString(1), rs.getString(2)), runId);
        List<String> columns = jdbcTemplate.queryForList("""
                select distinct column_key from research_cell where research_run_id = ? order by column_key
                """, String.class, runId);
        // DR-110: the cells this revision really adds. Collected from the same inserts that
        // create them, so the Replan audit row never has to guess its affected-cell set.
        LinkedHashSet<String> addedCellKeys = new LinkedHashSet<>();
        for (Map<String, Object> proposal : accepted) {
            String key = text(proposal.get("candidate_key"));
            if ("DIMENSION".equals(text(proposal.get("proposal_type")))) {
                for (Row row : rows) {
                    addedCellKeys.add(insertGapCell(runId, branchId, row.id(), row.key(), key,
                            nextPlanRevision, nextEntityVersion));
                }
            } else if ("ENTITY".equals(text(proposal.get("proposal_type")))) {
                String rowId = Ids.newId();
                jdbcTemplate.update("""
                        insert into research_row(
                            id, research_run_id, row_key, branch_id, source_title, row_status, verification_status)
                        values (?, ?, ?, ?, ?, 'DISCOVERED', 'PENDING')
                        """, rowId, runId, key, branchId, text(proposal.get("label")));
                for (String column : columns) {
                    addedCellKeys.add(insertGapCell(runId, branchId, rowId, key, column,
                            nextPlanRevision, nextEntityVersion));
                }
            }
        }
        Integer revisedRowCount = jdbcTemplate.queryForObject(
                "select count(*) from research_row where research_run_id = ?", Integer.class, runId);
        Integer revisedColumnCount = jdbcTemplate.queryForObject(
                "select count(distinct column_key) from research_cell where research_run_id = ?", Integer.class, runId);
        Integer revisedCellCount = jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, runId);
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("planner_version", "intent-matrix.v2-revision-" + nextPlanRevision);
        revision.put("plan_revision", nextPlanRevision);
        revision.put("entity_set_version", nextEntityVersion);
        revision.put("accepted_proposal_keys", accepted.stream().map(item -> text(item.get("proposal_key"))).sorted().toList());
        revision.put("bounded", false);
        String digest = canonicalizer.domainSeparatedDigest("research-matrix-plan.v2", revision);
        // The plan this revision replaces. Read before the revision row is inserted, with the same
        // "newest plan row wins" rule the checkpoint snapshot compiler uses for the same table.
        String replacedPlanDigest = currentPlanDigest(runId);
        // Written before the plan row on purpose: the (run, resulting revision) key is then the
        // first thing a replayed revision collides with, so a replay can never widen the matrix
        // twice. Both writes share the caller's transaction, so the two rows land or vanish
        // together.
        recordReplanAudit(runId, planRevision, nextPlanRevision, replacedPlanDigest, digest, accepted, addedCellKeys);
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json, plan_json, plan_digest)
                values (?, ?, ?, 'DISCOVERY_REVISION', 'ACTIVE', ?, ?, ?, false, '[]', ?, ?)
                """, Ids.newId(), runId, text(revision.get("planner_version")),
                revisedRowCount == null ? 0 : revisedRowCount,
                revisedColumnCount == null ? 0 : revisedColumnCount,
                revisedCellCount == null ? 0 : revisedCellCount,
                Json.write(objectMapper, revision), digest);
    }

    /** Inserts the GAP cell a Discovery revision adds and returns its {@code cell_key}. */
    private String insertGapCell(String runId, String branchId, String rowId, String rowKey, String column,
                                 int planRevision, int entityVersion) {
        String cellKey = rowKey + ":" + column;
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                    cell_status, evidence_refs_json, repair_count, cell_version,
                    plan_revision, entity_set_version, high_risk)
                values (?, ?, ?, ?, ?, ?, 'GAP', '[]', 0, 0, ?, ?, false)
                """, Ids.newId(), runId, rowId, cellKey, branchId, column, planRevision, entityVersion);
        return cellKey;
    }

    /**
     * The plan digest this revision replaces, or {@code null} when the run has no plan row.
     * Same ordering rule as {@code ResearchAgentCheckpointSnapshotCompiler}, so "the plan in
     * force" means the same thing everywhere.
     */
    private String currentPlanDigest(String runId) {
        return jdbcTemplate.query("""
                select plan_digest from research_matrix_plan
                where research_run_id = ? order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
    }

    /**
     * Persists the auditable summary of a Discovery revision. Every field is read back from what
     * this revision did: the digests of the replaced and the new plan row, the revision it moves
     * to, and the cells it actually inserted. Nothing here is inferred from the model - the
     * proposal rationale stays out of the audit table.
     *
     * <p>The budget delta is empty on purpose: an accepted proposal widens the matrix but consumes
     * no reservation counter at plan time, and inventing a delta would be a fabricated number.
     *
     * <p>Fail closed: this revision is about to append a {@code DISCOVERY_REVISION} plan row, so
     * it must also produce its audit row. Leaving it unaudited would drop the
     * {@code (run, resulting revision)} idempotency key, letting a replayed submission widen the
     * matrix again on every attempt. Both degenerate shapes below therefore throw, which rolls
     * back the whole {@code validateAndPersist} transaction (proposals, cells and plan rows
     * included) instead of leaving a scope change without its audit record.
     */
    private void recordReplanAudit(String runId, int planRevisionFrom, int planRevisionTo,
                                   String replacedPlanDigest, String revisionDigest,
                                   List<Map<String, Object>> accepted, Set<String> addedCells) {
        if (replacedPlanDigest == null) {
            log.warn("Discovery revision of run {} found no plan to replace", runId);
            throw new BusinessException("RESEARCH_REPLAN_AUDIT_PLAN_MISSING",
                    "Discovery revision has no replaced plan, so no auditable Replan can be formed");
        }
        if (addedCells.isEmpty()) {
            log.warn("Discovery revision of run {} added no cell", runId);
            throw new BusinessException("RESEARCH_REPLAN_AUDIT_NO_AFFECTED_CELLS",
                    "Discovery revision added no cell, so the scope did not expand and the plan change"
                            + " is not one of the auditable Replan deviations");
        }
        long entities = accepted.stream().filter(item -> "ENTITY".equals(text(item.get("proposal_type")))).count();
        long dimensions = accepted.stream().filter(item -> "DIMENSION".equals(text(item.get("proposal_type")))).count();
        String observedFacts = String.format(
                "Discovery accepted %d ENTITY and %d DIMENSION proposal(s) at plan revision %d;"
                        + " %d cell(s) were added to the matrix plan.",
                entities, dimensions, planRevisionFrom, addedCells.size());
        replanAudit.recordReplan(new ResearchAgentReplanAuditService.ReplanAuditCommand(
                runId, planRevisionFrom, planRevisionTo, replacedPlanDigest, revisionDigest,
                ResearchReplanDeviationTypes.SCOPE_EXPANDED, observedFacts,
                List.copyOf(addedCells), Map.of(), null, SELECTED_REPAIR));
    }

    private Map<String, Object> queryPolicy(ResearchAgentCompletionCommitter.TaskRow task) {
        try {
            Map<String, Object> context = objectMapper.readValue(task.executionContextJson(), new TypeReference<>() { });
            return mapValue(context.get("query_policy"));
        } catch (JsonProcessingException exception) { throw invalid("Discovery query policy is invalid"); }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> mapValue(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> listOfMaps(Object value) { return value instanceof List<?> list ? list.stream().filter(item -> item instanceof Map<?, ?>).map(item -> (Map<String, Object>) item).toList() : List.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).strip(); }
    private String nullable(String value) { return value.isBlank() ? null : value; }
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }
    private int integer(Object value) { return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value)); }
    private BusinessException invalid(String message) { return new BusinessException("RESEARCH_DISCOVERY_PROPOSAL_INVALID", message); }
    private BusinessException stale(String message) { return new BusinessException("RESEARCH_DISCOVERY_PROPOSAL_STALE", message); }
    private record Row(String id, String key) { }
}
