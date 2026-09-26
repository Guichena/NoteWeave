package com.noteweave.answer;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.AuditActorProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerRunService {

    private static final String PLAN_VERSION = "legacy-adapter-v1";
    private static final long STREAM_LEASE_SECONDS = 150;

    private final JdbcTemplate jdbcTemplate;
    private final AuditActorProvider auditActorProvider;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public AnswerRunService(
            JdbcTemplate jdbcTemplate,
            AuditActorProvider auditActorProvider,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditActorProvider = auditActorProvider;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String createAndStartRetrieval(
            String workspaceId,
            String conversationId,
            String mode,
            String queryMessageId,
            RetrievalPlan retrievalPlan
    ) {
        String runId = Ids.newId();
        String actor = actor();
        jdbcTemplate.update("""
                insert into answer_run(
                    id, workspace_id, conversation_id, mode, status, query_message_id,
                    retrieval_plan_version, retrieval_plan_json, created_by, updated_by
                ) values (?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """, runId, workspaceId, conversationId, mode, queryMessageId,
                versionOrDefault(retrievalPlan == null ? null : retrievalPlan.version()),
                Json.write(objectMapper, retrievalPlan), actor, actor);
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "CREATED"));
        transition(runId, workspaceId, "CREATED", "RETRIEVING", actor);
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "RETRIEVING"));
        meterRegistry.counter("noteweave.answer.run.created", "mode", mode).increment();
        return runId;
    }

    @Transactional
    public String createPreparingRun(
            String workspaceId,
            String conversationId,
            String mode,
            String queryMessageId,
            String answerMessageId,
            String assistantRequestId
    ) {
        String runId = Ids.newId();
        String actor = actor();
        jdbcTemplate.update("""
                insert into answer_run(
                    id, workspace_id, conversation_id, mode, status, query_message_id,
                    answer_message_id, assistant_request_id, retrieval_plan_version,
                    created_by, updated_by
                ) values (?, ?, ?, ?, 'PREPARING', ?, ?, ?, 'pending', ?, ?)
                """, runId, workspaceId, conversationId, mode, queryMessageId,
                answerMessageId, assistantRequestId, actor, actor);
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "PREPARING"));
        meterRegistry.counter("noteweave.answer.run.created", "mode", mode).increment();
        return runId;
    }

    @Transactional
    public void startPreparedRetrieval(
            String workspaceId,
            String runId,
            RetrievalPlan retrievalPlan
    ) {
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update answer_run
                set retrieval_plan_version = ?, retrieval_plan_json = ?, status = 'RETRIEVING',
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'PREPARING'
                """, versionOrDefault(retrievalPlan == null ? null : retrievalPlan.version()),
                Json.write(objectMapper, retrievalPlan), actor, workspaceId, runId);
        requireTransition(updated, runId, "RETRIEVING");
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "RETRIEVING"));
    }

    @Transactional
    public void prepareGeneration(
            String workspaceId,
            String runId,
            String answerMessageId,
            String assistantRequestId,
            String initialContent,
            String model,
            EvidenceBundle evidenceBundle,
            String promptVersion,
            int maximumOutputTokens
    ) {
        String actor = actor();
        EvidenceBundleSnapshot bundleSnapshot = EvidenceBundleSnapshot.from(evidenceBundle);
        persistEvidenceManifest(workspaceId, runId, evidenceBundle);
        int updated = jdbcTemplate.update("""
                update answer_run
                set answer_message_id = ?, assistant_request_id = ?, model = ?,
                    maximum_output_tokens = ?, evidence_bundle_json = ?, status = 'GENERATING',
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'RETRIEVING'
                """, answerMessageId, assistantRequestId, model,
                maximumOutputTokens, Json.write(objectMapper, bundleSnapshot),
                actor, workspaceId, runId);
        requireTransition(updated, runId, "GENERATING");
        jdbcTemplate.update("""
                insert into message_revision(
                    id, workspace_id, answer_run_id, message_id, revision_no, status, content,
                    model, prompt_version, evidence_bundle_ref, created_by
                ) values (?, ?, ?, ?, 1, 'STREAMING', ?, ?, ?, ?, ?)
                """, Ids.newId(), workspaceId, runId, answerMessageId, initialContent, model,
                versionOrDefault(promptVersion),
                evidenceBundle == null || evidenceBundle.bundleId() == null
                        || evidenceBundle.bundleId().isBlank()
                        ? "answer-run:" + runId + ":citations"
                        : "evidence-bundle:" + evidenceBundle.bundleId(),
                actor);
        RetrievalExecutionTrace trace = evidenceBundle == null
                ? RetrievalExecutionTrace.empty(PLAN_VERSION)
                : evidenceBundle.trace();
        appendEvent(runId, workspaceId, "retrieval.summary", Map.ofEntries(
                Map.entry("trace_schema_version", trace.schemaVersion()),
                Map.entry("plan_version", versionOrDefault(
                        evidenceBundle == null ? null : evidenceBundle.retrievalPlanVersion())),
                Map.entry("strategy_profile", retrievalMetadata(evidenceBundle, "strategy_profile")),
                Map.entry("relevance_policy", retrievalMetadata(evidenceBundle, "relevance_policy")),
                Map.entry("selection_policy", retrievalMetadata(evidenceBundle, "selection_policy")),
                Map.entry("retrieval_latency_micros", trace.totalLatencyMicros()),
                Map.entry("candidate_count", trace.rawCandidateCount()),
                Map.entry("admitted_candidate_count", trace.admittedCandidateCount()),
                Map.entry("selected_evidence_count", trace.selectedEvidenceCount()),
                Map.entry("selected_evidence_characters", trace.selectedEvidenceCharacters()),
                Map.entry("degraded", bundleSnapshot.degraded()),
                Map.entry("degradation_reasons", bundleSnapshot.degradationReasons()),
                Map.entry("maximum_output_tokens", maximumOutputTokens),
                Map.entry("execution_trace", trace)
        ));
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "GENERATING"));
    }

    @Transactional
    public String claimStream(String workspaceId, String runId) {
        AnswerRunRef current = requireRef(workspaceId, runId);
        if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())
                || "CANCELLED".equals(current.status())) {
            return null;
        }
        String owner = Ids.newId();
        int updated = jdbcTemplate.update("""
                update answer_run
                set stream_owner = ?,
                    stream_lease_until = timestampadd(second, ?, current_timestamp),
                    updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING'
                  and (stream_lease_until is null or stream_lease_until < current_timestamp)
                """, owner, STREAM_LEASE_SECONDS, actor(), workspaceId, runId);
        if (updated == 0) {
            throw new BusinessException(
                    "ANSWER_RUN_ALREADY_STREAMING",
                    "回答正在由另一个连接生成，请使用运行快照恢复进度",
                    HttpStatus.CONFLICT
            );
        }
        return owner;
    }

    @Transactional
    public boolean renewStreamLease(String workspaceId, String runId, String streamOwner) {
        int updated = jdbcTemplate.update("""
                update answer_run
                set stream_lease_until = timestampadd(second, ?, current_timestamp),
                    updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING'
                  and stream_owner = ? and stream_lease_until >= current_timestamp
                """, STREAM_LEASE_SECONDS, actor(), workspaceId, runId, streamOwner);
        return updated == 1;
    }

    @Transactional
    public void markFirstToken(String workspaceId, String runId, String streamOwner) {
        jdbcTemplate.update("""
                update answer_run
                set first_token_at = coalesce(first_token_at, current_timestamp),
                    updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING' and stream_owner = ?
                """, actor(), workspaceId, runId, streamOwner);
    }

    @Transactional
    public CompletionOutcome complete(
            String workspaceId,
            String runId,
            String streamOwner,
            String finalContent,
            Integer outputTokens
    ) {
        AnswerRunRef current = requireRef(workspaceId, runId);
        if ("COMPLETED".equals(current.status())) {
            return CompletionOutcome.COMPLETED;
        }
        if ("CANCELLED".equals(current.status())) {
            return CompletionOutcome.CANCELLED;
        }
        if ("FAILED".equals(current.status())) {
            return CompletionOutcome.FAILED;
        }
        requireNonBlankFinalContent(finalContent);
        String actor = actor();
        int finalizing = jdbcTemplate.update("""
                update answer_run
                set status = 'FINALIZING', version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING' and stream_owner = ?
                """, actor, workspaceId, runId, streamOwner);
        if (finalizing == 0) {
            AnswerRunRef latest = requireRef(workspaceId, runId);
            if ("COMPLETED".equals(latest.status())) {
                return CompletionOutcome.COMPLETED;
            }
            if ("CANCELLED".equals(latest.status())) {
                return CompletionOutcome.CANCELLED;
            }
            if ("FAILED".equals(latest.status())) {
                return CompletionOutcome.FAILED;
            }
            requireTransition(finalizing, runId, "FINALIZING");
        }
        appendEvent(runId, workspaceId, "answer.status", Map.of("status", "FINALIZING"));
        int finalizedRevision = jdbcTemplate.update("""
                update message_revision
                set status = 'FINAL', content = ?, output_tokens = ?, updated_at = current_timestamp
                where workspace_id = ? and answer_run_id = ? and revision_no = 1 and status = 'STREAMING'
                """, finalContent, outputTokens, workspaceId, runId);
        requireMessageRevisionFinalization(finalizedRevision);
        int completed = jdbcTemplate.update("""
                update answer_run
                set status = 'COMPLETED', finished_at = current_timestamp,
                    stream_owner = null, stream_lease_until = null,
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'FINALIZING' and stream_owner = ?
                """, actor, workspaceId, runId, streamOwner);
        requireTransition(completed, runId, "COMPLETED");
        appendEvent(runId, workspaceId, "answer.completed", Map.of(
                "status", "COMPLETED",
                "answer_message_id", current.answerMessageId()
        ));
        meterRegistry.counter("noteweave.answer.run.completed").increment();
        return CompletionOutcome.COMPLETED;
    }

    static void requireNonBlankFinalContent(String finalContent) {
        if (finalContent == null || finalContent.isBlank()) {
            throw new BusinessException(
                    "ANSWER_LLM_EMPTY_RESPONSE",
                    "Answer generation produced no content",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    static void requireMessageRevisionFinalization(int updated) {
        if (updated == 1) {
            return;
        }
        throw new BusinessException(
                "ANSWER_MESSAGE_REVISION_INVALID",
                "Answer message revision is not in STREAMING state",
                HttpStatus.CONFLICT
        );
    }

    @Transactional
    public void fail(String workspaceId, String runId, String streamOwner, String errorCode, String errorMessage) {
        AnswerRunRef current = requireRef(workspaceId, runId);
        if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())
                || "CANCELLED".equals(current.status())) {
            return;
        }
        String actor = actor();
        int updated = jdbcTemplate.update("""
                update answer_run
                set status = 'FAILED', error_code = ?, error_message = ?, finished_at = current_timestamp,
                    stream_owner = null, stream_lease_until = null,
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                  and status in ('CREATED', 'RETRIEVING', 'GENERATING', 'FINALIZING')
                  and (? is null or stream_owner = ?)
                """, errorCode, errorMessage, actor, workspaceId, runId, streamOwner, streamOwner);
        requireTransition(updated, runId, "FAILED");
        jdbcTemplate.update("""
                update message_revision set status = 'PARTIAL', updated_at = current_timestamp
                where workspace_id = ? and answer_run_id = ? and status = 'STREAMING'
                """, workspaceId, runId);
        appendEvent(runId, workspaceId, "answer.failed", Map.of(
                "status", "FAILED",
                "error_code", errorCode,
                "message", errorMessage == null ? "" : errorMessage
        ));
        meterRegistry.counter("noteweave.answer.run.failed", "error_code", errorCode).increment();
    }

    @Transactional
    public AnswerRunResponse cancel(String workspaceId, String runId) {
        AnswerRunRef current = requireRef(workspaceId, runId);
        if ("CANCELLED".equals(current.status())) {
            return get(workspaceId, runId);
        }
        if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())) {
            throw new BusinessException(
                    "ANSWER_RUN_TERMINAL",
                    "终态回答不能取消",
                    HttpStatus.CONFLICT
            );
        }
        int updated = jdbcTemplate.update("""
                update answer_run
                set status = 'CANCELLED', finished_at = current_timestamp,
                    stream_owner = null, stream_lease_until = null,
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                  and status in ('CREATED', 'RETRIEVING', 'GENERATING', 'FINALIZING')
                """, actor(), workspaceId, runId);
        if (updated == 0) {
            AnswerRunRef latest = requireRef(workspaceId, runId);
            if ("CANCELLED".equals(latest.status())) {
                return get(workspaceId, runId);
            }
            if ("COMPLETED".equals(latest.status()) || "FAILED".equals(latest.status())) {
                throw new BusinessException(
                        "ANSWER_RUN_TERMINAL",
                        "终态回答不能取消",
                        HttpStatus.CONFLICT
                );
            }
        }
        requireTransition(updated, runId, "CANCELLED");
        jdbcTemplate.update("""
                update message_revision set status = 'PARTIAL', updated_at = current_timestamp
                where workspace_id = ? and answer_run_id = ? and status = 'STREAMING'
                """, workspaceId, runId);
        appendEvent(runId, workspaceId, "answer.cancelled", Map.of("status", "CANCELLED"));
        meterRegistry.counter("noteweave.answer.run.cancelled").increment();
        return get(workspaceId, runId);
    }

    @Transactional
    public AnswerRunResponse cancelFromStream(String workspaceId, String runId, String streamOwner) {
        int updated = jdbcTemplate.update("""
                update answer_run
                set status = 'CANCELLED', finished_at = current_timestamp,
                    stream_owner = null, stream_lease_until = null,
                    version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING' and stream_owner = ?
                """, actor(), workspaceId, runId, streamOwner);
        if (updated == 0) {
            AnswerRunRef latest = requireRef(workspaceId, runId);
            if ("CANCELLED".equals(latest.status())
                    || "COMPLETED".equals(latest.status())
                    || "FAILED".equals(latest.status())) {
                return get(workspaceId, runId);
            }
            requireTransition(updated, runId, "CANCELLED");
        }
        jdbcTemplate.update("""
                update message_revision set status = 'PARTIAL', updated_at = current_timestamp
                where workspace_id = ? and answer_run_id = ? and status = 'STREAMING'
                """, workspaceId, runId);
        appendEvent(runId, workspaceId, "answer.cancelled", Map.of("status", "CANCELLED"));
        meterRegistry.counter("noteweave.answer.run.cancelled").increment();
        return get(workspaceId, runId);
    }

    public AnswerRunResponse get(String workspaceId, String runId) {
        return jdbcTemplate.query("""
                select r.id, r.workspace_id, r.conversation_id, r.mode, r.status,
                       r.query_message_id, r.answer_message_id, r.assistant_request_id,
                       r.retrieval_plan_version, r.model, r.maximum_output_tokens,
                       r.evidence_bundle_json,
                       r.started_at, r.first_token_at, r.finished_at,
                       r.error_code, r.error_message,
                       mr.revision_no, mr.status as revision_status, mr.content
                from answer_run r
                left join message_revision mr on mr.answer_run_id = r.id and mr.revision_no = 1
                where r.workspace_id = ? and r.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw notFound();
            }
            return response(rs);
        }, workspaceId, runId);
    }

    public AnswerEvidenceManifestResponse evidenceManifest(String workspaceId, String runId) {
        requireRef(workspaceId, runId);
        List<AnswerEvidenceManifestResponse.Evidence> evidence = jdbcTemplate.query("""
                select rank_no, evidence_id, evidence_kind, source_id, source_snapshot_id, passage_id,
                       knowledge_item_id, knowledge_version_id, title, excerpt, content_hash,
                       location_info, fresh_at, character_cost
                from answer_run_evidence_manifest
                where workspace_id = ? and answer_run_id = ?
                order by rank_no asc
                """, (rs, rowNum) -> new AnswerEvidenceManifestResponse.Evidence(
                rs.getInt("rank_no"), rs.getString("evidence_id"), rs.getString("evidence_kind"),
                rs.getString("source_id"), rs.getString("source_snapshot_id"), rs.getString("passage_id"),
                rs.getString("knowledge_item_id"), rs.getString("knowledge_version_id"), rs.getString("title"),
                rs.getString("excerpt"), rs.getString("content_hash"), rs.getString("location_info"),
                rs.getTimestamp("fresh_at") == null ? null : rs.getTimestamp("fresh_at").toInstant(),
                rs.getInt("character_cost")
        ), workspaceId, runId);
        return new AnswerEvidenceManifestResponse(runId, evidence);
    }

    public List<AnswerRunResponse> listConversationRuns(
            String workspaceId,
            String conversationId,
            int limit
    ) {
        return jdbcTemplate.query("""
                select r.id, r.workspace_id, r.conversation_id, r.mode, r.status,
                       r.query_message_id, r.answer_message_id, r.assistant_request_id,
                       r.retrieval_plan_version, r.model, r.maximum_output_tokens,
                       r.evidence_bundle_json,
                       r.started_at, r.first_token_at, r.finished_at,
                       r.error_code, r.error_message,
                       mr.revision_no, mr.status as revision_status, mr.content
                from answer_run r
                left join message_revision mr on mr.answer_run_id = r.id and mr.revision_no = 1
                where r.workspace_id = ? and r.conversation_id = ?
                order by r.created_at desc
                limit ?
                """, (rs, rowNum) -> response(rs),
                workspaceId, conversationId, Math.max(1, Math.min(limit, 50)));
    }

    public AnswerRunRef requireByAssistantRequest(String assistantRequestId) {
        return jdbcTemplate.query("""
                select id, workspace_id, conversation_id, answer_message_id, assistant_request_id, status
                from answer_run where assistant_request_id = ?
                """, rs -> rs.next() ? ref(rs) : null, assistantRequestId);
    }

    public AnswerRunRef requireRef(String workspaceId, String runId) {
        AnswerRunRef ref = jdbcTemplate.query("""
                select id, workspace_id, conversation_id, answer_message_id, assistant_request_id, status
                from answer_run where workspace_id = ? and id = ?
                """, rs -> rs.next() ? ref(rs) : null, workspaceId, runId);
        if (ref == null) {
            throw notFound();
        }
        return ref;
    }

    public List<AnswerEventResponse> eventsAfter(String workspaceId, String runId, long after) {
        requireRef(workspaceId, runId);
        return jdbcTemplate.query("""
                select seq, event_type, payload_json, created_at
                from answer_event
                where workspace_id = ? and answer_run_id = ? and seq > ?
                order by seq asc
                """, (rs, rowNum) -> new AnswerEventResponse(
                rs.getLong("seq"), rs.getString("event_type"),
                eventPayload(rs.getString("event_type"), rs.getString("payload_json")),
                instant(rs.getTimestamp("created_at"))
        ), workspaceId, runId, after);
    }

    @Transactional
    public void persistCitationEvents(String workspaceId, String runId, List<String> citationLines) {
        requireRef(workspaceId, runId);
        for (String citationLine : citationLines) {
            appendEvent(runId, workspaceId, "citation.upsert", Map.of(
                    "text", citationLine == null ? "" : citationLine));
        }
    }

    public long currentEventSequence(String workspaceId, String runId) {
        Long sequence = jdbcTemplate.queryForObject("""
                select event_seq from answer_run where workspace_id = ? and id = ?
                """, Long.class, workspaceId, runId);
        if (sequence == null) {
            throw notFound();
        }
        return sequence;
    }

    public boolean hasActiveStream(String workspaceId, String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from answer_run
                where workspace_id = ? and id = ? and status = 'GENERATING'
                  and stream_owner is not null and stream_lease_until >= current_timestamp
                """, Integer.class, workspaceId, runId);
        return count != null && count > 0;
    }

    @Transactional
    public void advanceEventSequence(String workspaceId, String runId, long sequence) {
        jdbcTemplate.update("""
                update answer_run
                set event_seq = case when event_seq < ? then ? else event_seq end,
                    updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = 'GENERATING'
                """, sequence, sequence, workspaceId, runId);
    }

    private void persistEvidenceManifest(String workspaceId, String runId, EvidenceBundle bundle) {
        if (bundle == null) {
            return;
        }
        for (int index = 0; index < bundle.evidence().size(); index++) {
            EvidenceBundle.Evidence item = bundle.evidence().get(index);
            String excerpt = item.excerpt() == null ? "" : item.excerpt();
            jdbcTemplate.update("""
                    insert into answer_run_evidence_manifest(
                        id, workspace_id, answer_run_id, rank_no, evidence_id, evidence_kind,
                        source_id, source_snapshot_id, passage_id, knowledge_item_id, knowledge_version_id,
                        title, excerpt, content_hash, location_info, fresh_at, character_cost
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), workspaceId, runId, index + 1, item.evidenceId(), item.kind(),
                    blank(item.sourceId()), blank(item.sourceSnapshotId()), blank(item.passageId()),
                    blank(item.knowledgeItemId()), blank(item.knowledgeVersionId()), blank(item.title()), excerpt,
                    sha256(excerpt), blank(item.location()),
                    item.freshAt() == null ? null : Timestamp.from(item.freshAt()), item.characterCost());
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void transition(String runId, String workspaceId, String from, String to, String actor) {
        int updated = jdbcTemplate.update("""
                update answer_run set status = ?, version = version + 1, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and status = ?
                """, to, actor, workspaceId, runId, from);
        requireTransition(updated, runId, to);
    }

    private void requireTransition(int updated, String runId, String target) {
        if (updated == 1) {
            return;
        }
        meterRegistry.counter("noteweave.answer.run.invalid_transition", "target", target).increment();
        throw new BusinessException(
                "ANSWER_RUN_INVALID_TRANSITION",
                "回答运行状态不能转换为 " + target + "，runId=" + runId,
                HttpStatus.CONFLICT
        );
    }

    private void appendEvent(String runId, String workspaceId, String eventType, Map<String, ?> payload) {
        Long current = jdbcTemplate.queryForObject(
                "select event_seq from answer_run where workspace_id = ? and id = ? for update",
                Long.class,
                workspaceId,
                runId
        );
        if (current == null) {
            throw notFound();
        }
        long next = current + 1;
        jdbcTemplate.update("""
                update answer_run set event_seq = ? where workspace_id = ? and id = ?
                """, next, workspaceId, runId);
        jdbcTemplate.update("""
                insert into answer_event(id, workspace_id, answer_run_id, seq, event_type, payload_json)
                values (?, ?, ?, ?, ?, ?)
                """, Ids.newId(), workspaceId, runId, next, eventType, Json.write(objectMapper, payload));
    }

    private String eventPayload(String eventType, String payloadJson) {
        String safePayload = text(payloadJson);
        if (!"citation.upsert".equals(eventType)) {
            return safePayload;
        }
        try {
            return objectMapper.readTree(safePayload).path("text").asText(safePayload);
        } catch (Exception ignored) {
            return safePayload;
        }
    }

    private AnswerRunRef ref(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AnswerRunRef(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("conversation_id"),
                text(rs.getString("answer_message_id")),
                text(rs.getString("assistant_request_id")), rs.getString("status")
        );
    }

    private AnswerRunResponse response(java.sql.ResultSet rs) throws java.sql.SQLException {
        RetrievalStatus retrievalStatus = retrievalStatus(rs.getString("evidence_bundle_json"));
        return new AnswerRunResponse(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("conversation_id"),
                rs.getString("mode"), rs.getString("status"), rs.getString("query_message_id"),
                text(rs.getString("answer_message_id")), text(rs.getString("assistant_request_id")),
                rs.getString("retrieval_plan_version"), text(rs.getString("model")),
                (Integer) rs.getObject("maximum_output_tokens"),
                retrievalStatus.degraded(), retrievalStatus.reasons(),
                (Integer) rs.getObject("revision_no"), text(rs.getString("revision_status")),
                text(rs.getString("content")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("first_token_at")), instant(rs.getTimestamp("finished_at")),
                text(rs.getString("error_code")), text(rs.getString("error_message"))
        );
    }

    private RetrievalStatus retrievalStatus(String evidenceBundleJson) {
        if (evidenceBundleJson == null || evidenceBundleJson.isBlank()) {
            return new RetrievalStatus(null, List.of());
        }
        try {
            EvidenceBundleSnapshot snapshot = objectMapper.readValue(
                    evidenceBundleJson, EvidenceBundleSnapshot.class);
            return new RetrievalStatus(snapshot.degraded(), snapshot.degradationReasons());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Stored EvidenceBundle snapshot is not readable", ex);
        }
    }

    private BusinessException notFound() {
        return new BusinessException("ANSWER_RUN_NOT_FOUND", "回答运行不存在", HttpStatus.NOT_FOUND);
    }

    private String actor() {
        return auditActorProvider.currentOrSystem("ANSWER");
    }

    private String versionOrDefault(String value) {
        return value == null || value.isBlank() ? PLAN_VERSION : value;
    }

    private String retrievalMetadata(EvidenceBundle bundle, String key) {
        if (bundle == null || bundle.metadata() == null) {
            return "";
        }
        return text(bundle.metadata().get(key));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record RetrievalStatus(Boolean degraded, List<String> reasons) {
    }

    public enum CompletionOutcome {
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
