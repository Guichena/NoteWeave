package com.noteweave.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatService;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.research.CreateResearchRunRequest;
import com.noteweave.research.ResearchRunResponse;
import com.noteweave.research.ResearchRunService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;

@Service
public class ConversationTurnModule {

    private static final int RECOVERY_LEASE_SECONDS = 60;
    private static final Logger log = LoggerFactory.getLogger(ConversationTurnModule.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConversationTurnPayloadCodec payloadCodec;
    private final ChatService chatService;
    private final ResearchRunService researchRunService;
    private final ConversationMessageSequence messageSequence;
    private final AuditActorProvider auditActorProvider;
    private final AnswerRunService answerRunService;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final RunInputSnapshotService runInputSnapshotService;
    private final ConversationSegmentBuildService conversationSegmentBuildService;
    private final ContextV2ShadowSnapshotService shadowSnapshots;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate nestedContextTransaction;
    private com.noteweave.memory.MemoryConversationExtractionService memoryExtractionService;

    public ConversationTurnModule(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ChatService chatService,
            ResearchRunService researchRunService,
            ConversationMessageSequence messageSequence,
            AuditActorProvider auditActorProvider,
            AnswerRunService answerRunService,
            WorkspaceAccessGuard workspaceAccessGuard,
            RunInputSnapshotService runInputSnapshotService,
            ConversationSegmentBuildService conversationSegmentBuildService,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcTemplate, objectMapper, chatService, researchRunService, messageSequence,
                auditActorProvider, answerRunService, workspaceAccessGuard, runInputSnapshotService,
                conversationSegmentBuildService, transactionManager, null);
    }

    @Autowired
    public ConversationTurnModule(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ChatService chatService,
            ResearchRunService researchRunService,
            ConversationMessageSequence messageSequence,
            AuditActorProvider auditActorProvider,
            AnswerRunService answerRunService,
            WorkspaceAccessGuard workspaceAccessGuard,
            RunInputSnapshotService runInputSnapshotService,
            ConversationSegmentBuildService conversationSegmentBuildService,
            PlatformTransactionManager transactionManager,
            ContextV2ShadowSnapshotService shadowSnapshots
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.payloadCodec = new ConversationTurnPayloadCodec(objectMapper);
        this.chatService = chatService;
        this.researchRunService = researchRunService;
        this.messageSequence = messageSequence;
        this.auditActorProvider = auditActorProvider;
        this.answerRunService = answerRunService;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.runInputSnapshotService = runInputSnapshotService;
        this.conversationSegmentBuildService = conversationSegmentBuildService;
        this.shadowSnapshots = shadowSnapshots;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedContextTransaction = new TransactionTemplate(transactionManager);
        this.nestedContextTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** 用户消息提交后，按需入队从对话中提取记忆候选的任务。 */
    @Autowired(required = false)
    void setMemoryExtractionService(com.noteweave.memory.MemoryConversationExtractionService service) {
        this.memoryExtractionService = service;
    }

    private String submissionActor(String submissionId) {
        return jdbcTemplate.query("select actor_user_id from turn_submission where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, submissionId);
    }

    public TurnReceipt submitNewTurn(SubmitTurnCommand command) {
        command.effectiveRetrievalConfig();
        String actor = auditActorProvider.currentOrSystem("CONVERSATION");
        String executionKind = executionKind(command.requestedTurnMode());
        String requestHash = payloadCodec.requestHash(command);
        ExistingSubmission existing = findExisting(actor, command.conversationId(), command.clientRequestId());
        if (existing != null) {
            if (!existing.requestPayloadHash().equals(requestHash)) {
                throw new BusinessException(
                        "TURN_SUBMISSION_CONFLICT",
                        "clientRequestId was already used with a different turn request",
                        HttpStatus.CONFLICT
                );
            }
            if (existing.receiptJson() == null || existing.receiptJson().isBlank()) {
                throw new BusinessException(
                        "TURN_SUBMISSION_IN_PROGRESS",
                        "The original turn submission is still being prepared",
                        HttpStatus.CONFLICT
                );
            }
            if ("FAILED".equals(existing.status())) {
                throw new BusinessException(
                        existing.errorCode() == null ? "TURN_SUBMISSION_FAILED" : existing.errorCode(),
                        existing.errorMessage() == null ? "Turn submission preparation failed" : existing.errorMessage()
                );
            }
            return payloadCodec.readReceipt(existing.receiptJson()).asReused();
        }

        if ("RESEARCH".equals(executionKind)) {
            try {
                return transactionTemplate.execute(status -> submitResearchTurn(command, actor, requestHash));
            } catch (DuplicateKeyException duplicate) {
                return awaitWinner(actor, command, requestHash);
            }
        }

        PreparedAnswerTurn prepared;
        try {
            prepared = transactionTemplate.execute(
                    status -> prepareAnswerTurn(command, actor, requestHash));
        } catch (DuplicateKeyException duplicate) {
            return awaitWinner(actor, command, requestHash);
        }
        freezeShadowAnswer(command, actor, prepared);
        try {
            ChatService.PreparedAnswerMaterial material = chatService.compilePreparedAnswer(
                    command.conversationId(),
                    prepared.receipt().messageId(),
                    prepared.workspaceId(),
                    command.toLegacyAnswerRequest()
            );
            transactionTemplate.executeWithoutResult(status -> finalizeAnswerTurn(command, prepared, material, null));
            return prepared.receipt();
        } catch (RuntimeException failure) {
            transactionTemplate.executeWithoutResult(status -> failAnswerPreparation(prepared, failure, null));
            throw failure;
        }
    }

    private void freezeShadowAnswer(SubmitTurnCommand command, String actor,
                                    PreparedAnswerTurn prepared) {
        if (shadowSnapshots == null) return;
        TurnReceipt receipt = prepared.receipt();
        try {
            shadowSnapshots.freezeAnswer(prepared.workspaceId(), actor, command.conversationId(),
                    receipt.messageId(), receipt.answerRunId(), command.content(),
                    command.requestedTurnMode());
        } catch (RuntimeException failure) {
            String code = failure instanceof BusinessException business
                    ? business.code() : "CONTEXT_V2_SHADOW_FAILURE";
            log.warn("Shadow Context freeze failed; runId={}, code={}", receipt.answerRunId(), code);
            try {
                shadowSnapshots.recordFailure(prepared.workspaceId(), command.conversationId(),
                        receipt.messageId(), receipt.answerRunId(), code);
            } catch (RuntimeException recordFailure) {
                log.warn("Shadow Context failure receipt could not be recorded; runId={}",
                        receipt.answerRunId(), recordFailure);
            }
        }
    }

    private TurnReceipt awaitWinner(String actor, SubmitTurnCommand command, String requestHash) {
        ExistingSubmission winner = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            winner = findExisting(actor, command.conversationId(), command.clientRequestId());
            if (winner != null) {
                if (!winner.requestPayloadHash().equals(requestHash)) {
                    throw new BusinessException(
                            "TURN_SUBMISSION_CONFLICT",
                            "clientRequestId was already used with a different turn request",
                            HttpStatus.CONFLICT
                    );
                }
                if ("FAILED".equals(winner.status())) {
                    throw new BusinessException(
                            winner.errorCode() == null ? "TURN_SUBMISSION_FAILED" : winner.errorCode(),
                            winner.errorMessage() == null
                                    ? "Turn submission preparation failed" : winner.errorMessage()
                    );
                }
                if ("READY".equals(winner.status())
                        && winner.receiptJson() != null && !winner.receiptJson().isBlank()) {
                    return payloadCodec.readReceipt(winner.receiptJson()).asReused();
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(20).toNanos());
        }
        if (winner != null && winner.receiptJson() != null && !winner.receiptJson().isBlank()) {
            return payloadCodec.readReceipt(winner.receiptJson()).asReused();
        }
        throw new BusinessException(
                "TURN_SUBMISSION_IN_PROGRESS",
                "The winning turn submission is still being prepared",
                HttpStatus.CONFLICT
        );
    }

    private PreparedAnswerTurn prepareAnswerTurn(
            SubmitTurnCommand command,
            String actor,
            String requestHash
    ) {
        ConversationState conversation = requireConversationState(command.conversationId());
        workspaceAccessGuard.requirePermission(conversation.workspaceId(), WorkspacePermission.ANSWER_RUN);
        requireExpectedHead(command.expectedHistoryHeadMessageId(), conversation.activeHeadMessageId());
        String submissionId = Ids.newId();
        insertSubmission(submissionId, conversation.workspaceId(), command, actor, requestHash, "ANSWER");

        int nextSeq = messageSequence.allocatePair(command.conversationId());
        String userMessageId = Ids.newId();
        String assistantMessageId = Ids.newId();
        String assistantRequestId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode, content
                ) values (?, ?, ?, ?, 'USER', ?, ?)
                """, userMessageId, command.conversationId(), conversation.workspaceId(), nextSeq,
                command.requestedTurnMode(), command.content());
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode, content, assistant_request_id
                ) values (?, ?, ?, ?, 'ASSISTANT', ?, '', ?)
                """, assistantMessageId, command.conversationId(), conversation.workspaceId(), nextSeq + 1,
                command.requestedTurnMode(), assistantRequestId);
        String answerRunId = answerRunService.createPreparingRun(
                conversation.workspaceId(), command.conversationId(), command.requestedTurnMode(),
                userMessageId, assistantMessageId, assistantRequestId);
        TurnReceipt receipt = TurnReceipt.answerPreparing(
                submissionId, conversation.workspaceId(), userMessageId, assistantMessageId,
                assistantRequestId, answerRunId);
        attachMessagesToActivePath(receipt, conversation.activeHeadMessageId(), "ANSWER");
        advanceHistoryHead(
                command.conversationId(), conversation.workspaceId(), actor, conversation, assistantMessageId);
        jdbcTemplate.update("""
                update turn_submission
                set query_message_id = ?, answer_message_id = ?, answer_run_id = ?, receipt_json = ?,
                    preparation_json = ?, preparation_attempt = 1, updated_at = current_timestamp
                where id = ? and status = 'PREPARING'
                """, userMessageId, assistantMessageId, answerRunId, payloadCodec.writeReceipt(receipt),
                payloadCodec.preparationJson(
                        command, conversation.activeHeadMessageId(), conversation.lockVersion()), submissionId);
        return new PreparedAnswerTurn(conversation.workspaceId(), receipt);
    }

    private TurnReceipt submitResearchTurn(SubmitTurnCommand command, String actor, String requestHash) {

        ConversationState conversation = requireConversationState(command.conversationId());
        requireExpectedHead(command.expectedHistoryHeadMessageId(), conversation.activeHeadMessageId());
        String workspaceId = conversation.workspaceId();
        String submissionId = Ids.newId();
        insertSubmission(submissionId, workspaceId, command, actor, requestHash, "RESEARCH");
        PreparedResearchTurn prepared = createResearchTurn(submissionId, workspaceId, actor, command);
        TurnReceipt receipt = prepared.receipt();
        attachMessagesToActivePath(receipt, conversation.activeHeadMessageId(), "RESEARCH");
        advanceHistoryHead(command.conversationId(), workspaceId, actor, conversation, receipt.assistantMessageId());
        jdbcTemplate.update("""
                update turn_submission
                set query_message_id = ?, answer_message_id = ?, answer_run_id = ?, research_run_id = ?,
                    receipt_json = ?, preparation_json = ?, preparation_attempt = 1,
                    status = 'READY', updated_at = current_timestamp
                where id = ? and status = 'PREPARING'
                """, receipt.messageId(), receipt.assistantMessageId(), receipt.answerRunId(), receipt.researchRunId(),
                payloadCodec.writeReceipt(receipt),
                payloadCodec.preparationJson(
                        command, conversation.activeHeadMessageId(), conversation.lockVersion()), submissionId);
        runInputSnapshotService.recordResearchSnapshot(workspaceId, receipt, command,
                prepared.contextSnapshotId(), prepared.projection(), prepared.fallbackCode());
        jdbcTemplate.update("""
                update research_run
                set agent_execution_mode = 'INCREMENTAL_V1', updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, workspaceId, receipt.researchRunId());
        return receipt;
    }

    private void insertSubmission(
            String submissionId,
            String workspaceId,
            SubmitTurnCommand command,
            String actor,
            String requestHash,
            String executionKind
    ) {
        jdbcTemplate.update("""
                insert into turn_submission(
                    id, workspace_id, conversation_id, actor_user_id,
                    client_request_id, operation_type, request_payload_hash,
                    execution_kind, status
                ) values (?, ?, ?, ?, ?, 'NEW_TURN', ?, ?, 'PREPARING')
                """, submissionId, workspaceId, command.conversationId(), actor,
                command.clientRequestId(), requestHash, executionKind);
    }

    private void finalizeAnswerTurn(
            SubmitTurnCommand command,
            PreparedAnswerTurn prepared,
            ChatService.PreparedAnswerMaterial material,
            String recoveryLeaseOwner
    ) {
        if (recoveryLeaseOwner != null) {
            int activeLease = jdbcTemplate.update("""
                    update turn_submission
                    set updated_at = current_timestamp
                    where id = ? and status = 'PREPARING'
                      and recovery_lease_owner = ? and recovery_lease_until >= current_timestamp
                    """, prepared.receipt().submissionId(), recoveryLeaseOwner);
            if (activeLease != 1) {
                throw new BusinessException(
                        "TURN_RECOVERY_CLAIM_CONFLICT", "Turn submission recovery lease was lost", HttpStatus.CONFLICT);
            }
        }
        runInputSnapshotService.recordAnswerSnapshot(
                prepared.workspaceId(), prepared.receipt(), command, material);
        chatService.finalizePreparedAnswer(
                prepared.workspaceId(),
                prepared.receipt().answerRunId(),
                prepared.receipt().assistantMessageId(),
                prepared.receipt().assistantRequestId(),
                command.requestedTurnMode(),
                material
        );
        jdbcTemplate.update("""
                update conversation_message
                set content_hash = ?, context_status = 'CURRENT'
                where id = ? and context_status = 'PENDING'
                """, sha256(material.answer()), prepared.receipt().assistantMessageId());
        conversationSegmentBuildService.queueBuildForActivePrefix(prepared.workspaceId(), command.conversationId());
        if (memoryExtractionService != null) {
            memoryExtractionService.queueForUserMessage(prepared.workspaceId(), command.conversationId(),
                    prepared.receipt().messageId(), submissionActor(prepared.receipt().submissionId()));
        }
        if (recoveryLeaseOwner == null) {
            jdbcTemplate.update("""
                    update turn_submission
                    set status = 'READY', error_code = null, error_message = null,
                        updated_at = current_timestamp
                    where id = ? and status = 'PREPARING'
                    """, prepared.receipt().submissionId());
        } else {
            jdbcTemplate.update("""
                    update turn_submission
                    set status = 'READY', error_code = null, error_message = null,
                        recovery_lease_owner = null, recovery_lease_until = null,
                        updated_at = current_timestamp
                    where id = ? and status = 'PREPARING' and recovery_lease_owner = ?
                    """, prepared.receipt().submissionId(), recoveryLeaseOwner);
        }
    }

    private void failAnswerPreparation(
            PreparedAnswerTurn prepared,
            RuntimeException failure,
            String recoveryLeaseOwner
    ) {
        String errorCode = failure instanceof BusinessException business
                ? business.code() : "TURN_PREPARATION_FAILED";
        String errorMessage = failure.getMessage() == null ? "Turn preparation failed" : failure.getMessage();
        int failed;
        if (recoveryLeaseOwner == null) {
            failed = jdbcTemplate.update("""
                    update turn_submission
                    set status = 'FAILED', error_code = ?, error_message = ?, updated_at = current_timestamp
                    where id = ? and status = 'PREPARING'
                    """, errorCode, errorMessage, prepared.receipt().submissionId());
        } else {
            failed = jdbcTemplate.update("""
                    update turn_submission
                    set status = 'FAILED', error_code = ?, error_message = ?,
                        recovery_lease_owner = null, recovery_lease_until = null,
                        updated_at = current_timestamp
                    where id = ? and status = 'PREPARING' and recovery_lease_owner = ?
                    """, errorCode, errorMessage, prepared.receipt().submissionId(), recoveryLeaseOwner);
        }
        if (failed != 1) {
            return;
        }
        jdbcTemplate.update("""
                update answer_run
                set status = 'FAILED', error_code = ?, error_message = ?, updated_at = current_timestamp
                where id = ? and status = 'PREPARING'
                """, errorCode, errorMessage, prepared.receipt().answerRunId());
        jdbcTemplate.update("""
                update conversation_message
                set context_status = 'FAILED'
                where id = ? and context_status = 'PENDING'
                """, prepared.receipt().assistantMessageId());
    }

    private PreparedResearchTurn createResearchTurn(
            String submissionId,
            String workspaceId,
            String actor,
            SubmitTurnCommand command
    ) {
        int nextSeq = messageSequence.allocatePair(command.conversationId());
        String userMessageId = Ids.newId();
        String assistantMessageId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode, content
                ) values (?, ?, ?, ?, 'USER', 'DEEP_RESEARCH', ?)
                """, userMessageId, command.conversationId(), workspaceId, nextSeq, command.content());
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode,
                    content, context_status, reply_to_message_id
                ) values (?, ?, ?, ?, 'ASSISTANT', 'DEEP_RESEARCH', '', 'PENDING', ?)
                """, assistantMessageId, command.conversationId(), workspaceId,
                nextSeq + 1, userMessageId);

        ContextProjectionV2 projection = null;
        String contextSnapshotId = null;
        String executionQuestion = null;
        String fallbackCode = null;
        if (shadowSnapshots != null) {
            try {
                projection = nestedContextTransaction.execute(status ->
                        shadowSnapshots.compileResearch(workspaceId, actor,
                                command.conversationId(), nextSeq, command.content()));
                if (projection != null) {
                    contextSnapshotId = Ids.newId();
                    executionQuestion = ResearchContextV2Question.render(projection, userMessageId);
                }
            } catch (RuntimeException failure) {
                fallbackCode = failure instanceof BusinessException business
                        ? business.code() : "CONTEXT_V2_RESEARCH_FAILURE";
                log.warn("Research Context v2 freeze failed; conversationId={}, code={}",
                        command.conversationId(), fallbackCode);
                projection = null;
                contextSnapshotId = null;
                executionQuestion = null;
            }
        }
        CreateResearchRunRequest request = new CreateResearchRunRequest(
                command.content(),
                "balanced",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                List.of(),
                command.sourceScope().isEmpty() ? "WEB_ONLY" : "WEB_PLUS_SEEDS",
                command.sourceScope()
        );
        ResearchRunResponse run = projection == null
                ? researchRunService.createRun(workspaceId, request)
                : researchRunService.createConversationRun(workspaceId, request,
                executionQuestion, contextSnapshotId);
        int linked = jdbcTemplate.update("""
                update research_run
                set conversation_id = ?, query_message_id = ?, answer_message_id = ?,
                    agent_execution_mode = 'INCREMENTAL_V1', updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, command.conversationId(), userMessageId, assistantMessageId,
                workspaceId, run.researchRunId());
        if (linked != 1) {
            throw new BusinessException(
                    "RESEARCH_CONVERSATION_LINK_FAILED", "Research run could not be linked to its conversation");
        }
        jdbcTemplate.update("""
                update conversation
                set last_active_at = current_timestamp, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, actor, workspaceId, command.conversationId());
        return new PreparedResearchTurn(
                TurnReceipt.research(submissionId, userMessageId, assistantMessageId, run),
                contextSnapshotId, projection, fallbackCode);
    }

    private record PreparedResearchTurn(TurnReceipt receipt, String contextSnapshotId,
                                        ContextProjectionV2 projection, String fallbackCode) {}

    private String executionKind(String requestedTurnMode) {
        if ("DEEP_RESEARCH".equals(requestedTurnMode)) {
            return "RESEARCH";
        }
        AnswerMode.parse(requestedTurnMode);
        return "ANSWER";
    }

    private ExistingSubmission findExisting(String actor, String conversationId, String clientRequestId) {
        List<ExistingSubmission> rows = jdbcTemplate.query("""
                select request_payload_hash, receipt_json, status, error_code, error_message
                from turn_submission
                where actor_user_id = ? and conversation_id = ? and client_request_id = ?
        """, (rs, rowNum) -> new ExistingSubmission(
                rs.getString("request_payload_hash"),
                rs.getString("receipt_json"),
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getString("error_message")),
                actor, conversationId, clientRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TurnSubmissionResponse getSubmission(
            String workspaceId,
            String conversationId,
            String clientRequestId
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        List<TurnSubmissionResponse> rows = jdbcTemplate.query("""
                select id, client_request_id, execution_kind, status, preparation_attempt,
                       query_message_id, answer_message_id, answer_run_id, research_run_id,
                       error_code, error_message, created_at, updated_at
                from turn_submission
                where workspace_id = ? and conversation_id = ? and client_request_id = ?
                """, (rs, rowNum) -> new TurnSubmissionResponse(
                rs.getString("id"),
                rs.getString("client_request_id"),
                rs.getString("execution_kind"),
                rs.getString("status"),
                rs.getInt("preparation_attempt"),
                rs.getString("query_message_id"),
                rs.getString("answer_message_id"),
                rs.getString("answer_run_id"),
                rs.getString("research_run_id"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), workspaceId, conversationId, clientRequestId);
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "TURN_SUBMISSION_NOT_FOUND",
                    "Turn submission does not exist",
                    HttpStatus.NOT_FOUND
            );
        }
        return rows.get(0);
    }

    public TurnReceipt recoverPreparation(String submissionId) {
        RecoveryState recovery = transactionTemplate.execute(status -> claimRecovery(submissionId));
        try {
            ChatService.PreparedAnswerMaterial material = chatService.compilePreparedAnswer(
                    recovery.command().conversationId(),
                    recovery.receipt().messageId(),
                    recovery.workspaceId(),
                    recovery.command().toLegacyAnswerRequest()
            );
            PreparedAnswerTurn prepared = new PreparedAnswerTurn(recovery.workspaceId(), recovery.receipt());
            transactionTemplate.executeWithoutResult(
                    status -> finalizeAnswerTurn(recovery.command(), prepared, material, recovery.leaseOwner()));
            return recovery.receipt();
        } catch (RuntimeException failure) {
            transactionTemplate.executeWithoutResult(status -> failAnswerPreparation(
                    new PreparedAnswerTurn(recovery.workspaceId(), recovery.receipt()), failure, recovery.leaseOwner()));
            throw failure;
        }
    }

    private RecoveryState claimRecovery(String submissionId) {
        List<RecoveryRow> rows = jdbcTemplate.query("""
                select workspace_id, conversation_id, execution_kind, status,
                       preparation_json, receipt_json
                from turn_submission
                where id = ?
                """, (rs, rowNum) -> new RecoveryRow(
                rs.getString("workspace_id"),
                rs.getString("conversation_id"),
                rs.getString("execution_kind"),
                rs.getString("status"),
                rs.getString("preparation_json"),
                rs.getString("receipt_json")
        ), submissionId);
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "TURN_SUBMISSION_NOT_FOUND", "Turn submission does not exist", HttpStatus.NOT_FOUND);
        }
        RecoveryRow row = rows.get(0);
        if (!"ANSWER".equals(row.executionKind())) {
            throw new BusinessException(
                    "TURN_RECOVERY_UNSUPPORTED", "Only Answer preparation recovery is available in this slice");
        }
        if (!List.of("FAILED", "PREPARING").contains(row.status())) {
            throw new BusinessException(
                    "TURN_RECOVERY_NOT_REQUIRED", "Turn submission is not recoverable", HttpStatus.CONFLICT);
        }
        if (row.preparationJson() == null || row.receiptJson() == null) {
            throw new BusinessException(
                    "TURN_RECOVERY_INPUT_MISSING", "Frozen turn preparation is unavailable", HttpStatus.CONFLICT);
        }
        TurnReceipt receipt = payloadCodec.readReceipt(row.receiptJson());
        String leaseOwner = Ids.newId();
        int claimed = jdbcTemplate.update("""
                update turn_submission
                set status = 'PREPARING', preparation_attempt = preparation_attempt + 1,
                    error_code = null, error_message = null,
                    recovery_lease_owner = ?,
                    recovery_lease_until = timestampadd(second, ?, current_timestamp),
                    updated_at = current_timestamp
                where id = ? and (
                    status = 'FAILED'
                    or (status = 'PREPARING' and (
                        recovery_lease_until < current_timestamp
                        or (recovery_lease_until is null
                            and updated_at < timestampadd(second, ?, current_timestamp))
                    ))
                )
                """, leaseOwner, RECOVERY_LEASE_SECONDS, submissionId, -RECOVERY_LEASE_SECONDS);
        if (claimed != 1) {
            throw new BusinessException(
                    "TURN_RECOVERY_NOT_STALE", "Turn submission recovery lease is still active", HttpStatus.CONFLICT);
        }
        jdbcTemplate.update("""
                update answer_run
                set status = 'PREPARING', error_code = null, error_message = null,
                    updated_at = current_timestamp
                where id = ? and status = 'FAILED'
                """, receipt.answerRunId());
        jdbcTemplate.update("""
                update conversation_message
                set context_status = 'PENDING'
                where id = ? and context_status = 'FAILED'
                """, receipt.assistantMessageId());
        return new RecoveryState(row.workspaceId(), receipt, readFrozenCommand(
                row.conversationId(), row.preparationJson()), leaseOwner);
    }

    private SubmitTurnCommand readFrozenCommand(String conversationId, String preparationJson) {
        return payloadCodec.readFrozenCommand(conversationId, preparationJson);
    }

    private ConversationState requireConversationState(String conversationId) {
        List<ConversationState> rows = jdbcTemplate.query("""
                select workspace_id, active_head_message_id, lock_version
                from conversation
                where id = ?
                """, (rs, rowNum) -> new ConversationState(
                rs.getString("workspace_id"),
                rs.getString("active_head_message_id"),
                rs.getInt("lock_version")
        ), conversationId);
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "CONVERSATION_NOT_FOUND",
                    "Conversation does not exist",
                    HttpStatus.NOT_FOUND
            );
        }
        return rows.get(0);
    }

    private void requireExpectedHead(String expectedHead, String activeHead) {
        if (expectedHead != null && !expectedHead.equals(activeHead)) {
            throw new BusinessException(
                    "CONVERSATION_HEAD_CONFLICT",
                    "Conversation history advanced after the turn was composed",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void attachMessagesToActivePath(TurnReceipt receipt, String previousHead, String executionKind) {
        String userContent = jdbcTemplate.queryForObject(
                "select content from conversation_message where id = ?",
                String.class, receipt.messageId());
        String assistantContent = jdbcTemplate.queryForObject(
                "select content from conversation_message where id = ?",
                String.class, receipt.assistantMessageId());
        jdbcTemplate.update("""
                update conversation_message
                set reply_to_message_id = ?, context_status = 'CURRENT', content_hash = ?
                where id = ?
                """, previousHead, sha256(userContent), receipt.messageId());
        jdbcTemplate.update("""
                update conversation_message
                set reply_to_message_id = ?, context_status = 'PENDING', content_hash = ?
                where id = ?
                """, receipt.messageId(), sha256(assistantContent), receipt.assistantMessageId());
    }

    private void advanceHistoryHead(
            String conversationId,
            String workspaceId,
            String actor,
            ConversationState previous,
            String nextHead
    ) {
        int updated = jdbcTemplate.update("""
                update conversation
                set active_head_message_id = ?, lock_version = lock_version + 1,
                    last_active_at = current_timestamp, updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ? and lock_version = ?
                  and ((active_head_message_id is null and ? is null) or active_head_message_id = ?)
                """, nextHead, actor, workspaceId, conversationId, previous.lockVersion(),
                previous.activeHeadMessageId(), previous.activeHeadMessageId());
        if (updated != 1) {
            throw new BusinessException(
                    "CONVERSATION_HEAD_CONFLICT",
                    "Conversation history advanced while the turn was being submitted",
                    HttpStatus.CONFLICT
            );
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record ExistingSubmission(
            String requestPayloadHash,
            String receiptJson,
            String status,
            String errorCode,
            String errorMessage
    ) {
    }

    private record ConversationState(String workspaceId, String activeHeadMessageId, int lockVersion) {
    }

    private record PreparedAnswerTurn(String workspaceId, TurnReceipt receipt) {
    }

    private record RecoveryRow(
            String workspaceId,
            String conversationId,
            String executionKind,
            String status,
            String preparationJson,
            String receiptJson
    ) {
    }

    private record RecoveryState(
            String workspaceId,
            TurnReceipt receipt,
            SubmitTurnCommand command,
            String leaseOwner
    ) {
    }
}
