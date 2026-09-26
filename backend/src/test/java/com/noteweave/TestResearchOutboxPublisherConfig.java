package com.noteweave;

import com.noteweave.worker.ArtifactOutboxPublisher;
import com.noteweave.worker.ArtifactWorkerControlClient;
import com.noteweave.worker.ArtifactWorkerExecutionResponse;
import com.noteweave.worker.ArtifactWorkerResumeRequest;
import com.noteweave.worker.ArtifactAcquisitionAckRequest;
import com.noteweave.worker.ArtifactAcquisitionAckResponse;
import com.noteweave.worker.ArtifactAcquisitionDeliveryAttemptResponse;
import com.noteweave.worker.ArtifactAcquisitionOperationResponse;
import com.noteweave.worker.ArtifactAcquisitionReceiptResponse;
import com.noteweave.worker.WorkerCompleteRequest;
import com.noteweave.worker.WorkerProgressRequest;
import com.noteweave.worker.WorkerTaskCallbackService;
import com.noteweave.artifact.ArtifactWorkerExportClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@TestConfiguration
class TestResearchOutboxPublisherConfig {

    @Bean
    @Primary
    ArtifactWorkerExportClient artifactWorkerExportClient() {
        return (taskId, fileName) -> {
            if (fileName.startsWith("missing-")) {
                throw new com.noteweave.common.BusinessException(
                        "ARTIFACT_EXPORT_FETCH_FAILED", "worker export unavailable",
                        org.springframework.http.HttpStatus.BAD_GATEWAY);
            }
            try (org.apache.pdfbox.pdmodel.PDDocument document =
                         new org.apache.pdfbox.pdmodel.PDDocument();
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                document.save(output);
                return output.toByteArray();
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("test PDF generation failed", ex);
            }
        };
    }

    @Bean
    OncePerRequestFilter artifactWorkerCallbackHeaders(JdbcTemplate jdbcTemplate) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain
            ) throws ServletException, IOException {
                String prefix = "/internal/worker/tasks/";
                if (!request.getRequestURI().startsWith(prefix)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String remainder = request.getRequestURI().substring(prefix.length());
                String taskId = remainder.contains("/")
                        ? remainder.substring(0, remainder.indexOf('/'))
                        : remainder;
                String suppliedDeliveryToken = request.getHeader("X-NoteWeave-Outbox-Delivery-Token");
                String deliveryToken = suppliedDeliveryToken == null
                        ? claimArtifactDelivery(jdbcTemplate, taskId)
                        : suppliedDeliveryToken;
                if (deliveryToken.isBlank()) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String callbackToken = taskCallbackToken(taskId);
                HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("X-NoteWeave-Outbox-Delivery-Token".equalsIgnoreCase(name)) {
                            return deliveryToken;
                        }
                        if ("X-NoteWeave-Task-Callback-Token".equalsIgnoreCase(name)
                                && super.getHeader(name) == null) {
                            return callbackToken;
                        }
                        return super.getHeader(name);
                    }

                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        String value = getHeader(name);
                        return value == null
                                ? Collections.emptyEnumeration()
                                : Collections.enumeration(List.of(value));
                    }

                    @Override
                    public Enumeration<String> getHeaderNames() {
                        LinkedHashSet<String> names = new LinkedHashSet<>();
                        Enumeration<String> existing = super.getHeaderNames();
                        while (existing.hasMoreElements()) {
                            names.add(existing.nextElement());
                        }
                        names.add("X-NoteWeave-Outbox-Delivery-Token");
                        if (super.getHeader("X-NoteWeave-Task-Callback-Token") == null) {
                            names.add("X-NoteWeave-Task-Callback-Token");
                        }
                        return Collections.enumeration(names);
                    }
                };
                filterChain.doFilter(wrapped, response);
            }
        };
    }

    private static String claimArtifactDelivery(JdbcTemplate jdbcTemplate, String taskId) {
        List<String> existing = jdbcTemplate.queryForList("""
                select lease_owner from task_outbox
                where task_id = ? and topic = 'noteweave.artifact.job'
                  and status in ('PROCESSING', 'SENT') and lease_owner is not null
                order by created_at desc, id desc limit 1
                """, String.class, taskId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String token = "test-delivery:" + java.util.UUID.randomUUID();
        int claimed = jdbcTemplate.update("""
                update task_outbox
                set status = 'PROCESSING', claimed_at = current_timestamp,
                    lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1
                where task_id = ? and topic = 'noteweave.artifact.job' and status = 'READY'
                """,
                token,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(4200)),
                taskId
        );
        return claimed == 1 ? token : "";
    }

    private static String taskCallbackToken(String taskId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "test-artifact-callback-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] digest = mac.doFinal(
                    ("noteweave-worker-callback:v1:ARTIFACT_JOB:" + taskId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Bean
    @Primary
    RecordingArtifactOutboxPublisher recordingArtifactOutboxPublisher() {
        return new RecordingArtifactOutboxPublisher();
    }

    @Bean
    @Primary
    RecordingArtifactWorkerControlClient recordingArtifactWorkerControlClient(
            WorkerTaskCallbackService workerTaskCallbackService
    ) {
        return new RecordingArtifactWorkerControlClient(workerTaskCallbackService);
    }

    record PublishedMessage(String topic, String messageKey, String payloadJson, String deliveryToken) {
    }

    static class RecordingArtifactOutboxPublisher implements ArtifactOutboxPublisher {

        private final List<PublishedMessage> messages = new ArrayList<>();
        private boolean failNextPublish;

        @Override
        public void publish(String topic, String messageKey, String payloadJson, String deliveryToken) {
            if (failNextPublish) {
                failNextPublish = false;
                throw new IllegalStateException("simulated artifact worker outage");
            }
            messages.add(new PublishedMessage(topic, messageKey, payloadJson, deliveryToken));
        }

        List<PublishedMessage> messages() {
            return messages;
        }

        void reset() {
            messages.clear();
            failNextPublish = false;
        }

        void failNextPublish() {
            failNextPublish = true;
        }
    }

    static class RecordingArtifactWorkerControlClient implements ArtifactWorkerControlClient {

        private final WorkerTaskCallbackService workerTaskCallbackService;
        private final List<ResumeInvocation> resumeInvocations = new ArrayList<>();
        private final List<ArtifactAcquisitionAckRequest> acquisitionAckRequests = new ArrayList<>();
        private final Map<String, ResumeStub> resumeStubs = new HashMap<>();
        private final Map<String, AcquisitionAckStub> acquisitionAckStubs = new HashMap<>();
        private final Map<String, Map<String, Object>> pendingRuntimeTraceByTaskId = new HashMap<>();

        RecordingArtifactWorkerControlClient(WorkerTaskCallbackService workerTaskCallbackService) {
            this.workerTaskCallbackService = workerTaskCallbackService;
        }

        @Override
        public ArtifactWorkerExecutionResponse resumeTask(String taskId, ArtifactWorkerResumeRequest request) {
            resumeInvocations.add(new ResumeInvocation(taskId, request));
            ResumeStub stub = resumeStubs.get(taskId);
            if (stub == null) {
                return new ArtifactWorkerExecutionResponse(taskId, "WAITING_FOR_PROVIDER", 0, "");
            }
            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("markdown", stub.markdown());
            resultPayload.put("export_trace", Map.of(
                    "status", "COMPILED", "file_name", "worker-course-notes.pdf"));
            Map<String, Object> pendingRuntimeTrace = pendingRuntimeTraceByTaskId.remove(taskId);
            if (pendingRuntimeTrace != null && !pendingRuntimeTrace.isEmpty()) {
                resultPayload.putAll(pendingRuntimeTrace);
            } else if (!blankIfNull(request.requestId()).isBlank()) {
                String requestId = blankIfNull(request.requestId());
                        resultPayload.putAll(
                                buildAcquisitionCallbackRuntimeTrace(
                                        requestId,
                                        taskId,
                                        "src-bili-1",
                                        "provider-job-builtin-bilibili-mcp-" + requestId,
                                        "",
                                        1,
                                        List.of(Map.ofEntries(
                                                Map.entry("delivery_id", "acq-delivery-" + requestId + "-1"),
                                                Map.entry("dispatch_count", 1),
                                                Map.entry("callback_token", ""),
                                                Map.entry("dispatched_at", "2026-07-07T05:58:00Z"),
                                                Map.entry("callback_deadline_at", "2026-07-07T06:05:00Z"),
                                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-" + requestId),
                                                Map.entry("provider_receipt_id", "provider-receipt-" + requestId),
                                                Map.entry("input_digest", "artifact-input-digest-" + requestId),
                                                Map.entry("ack_status", "ACKNOWLEDGED"),
                                                Map.entry("callback_received_at", "2026-07-07T06:00:00Z"),
                                                Map.entry("result_locator", "bilibili://subtitle/BV1NoteWeaveDemo"),
                                                Map.entry("error_code", ""),
                                                Map.entry("error_message", "")
                                        ))
                                )
                        );
            }
            workerTaskCallbackService.progress(taskId, new WorkerProgressRequest(
                    "COMPOSING",
                    81,
                    stub.progressMessage(),
                    Map.of("chapters", 6),
                    Map.of()
            ));
            workerTaskCallbackService.complete(taskId, new WorkerCompleteRequest(
                    "MARKDOWN",
                    stub.resultTitle(),
                    resultPayload,
                    stub.traceSummary(),
                    stub.citations()
            ), "test-artifact-complete:" + taskId);
            return new ArtifactWorkerExecutionResponse(taskId, "COMPLETED", 1, stub.resultTitle());
        }

        @Override
        public ArtifactAcquisitionAckResponse acknowledgeAcquisition(ArtifactAcquisitionAckRequest request) {
            acquisitionAckRequests.add(request);
            AcquisitionAckStub stub = acquisitionAckStubs.get(request.callbackToken());
            if (stub == null) {
                return new ArtifactAcquisitionAckResponse(
                        new ArtifactAcquisitionOperationResponse("", "", "", "", "", "", "", "", "", "", "", "", "", "", 0, List.of()),
                        new ArtifactAcquisitionReceiptResponse(
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                request.callbackToken(),
                                "IGNORED",
                                "IGNORED",
                                "",
                                "",
                                blankIfNull(request.resultLocator()),
                                "",
                                "",
                                "",
                                0
                        ),
                        List.of()
                );
            }
            if ("FAILED".equalsIgnoreCase(stub.finalStatus())) {
                List<ArtifactAcquisitionDeliveryAttemptResponse> deliveryAttempts = buildDeliveryAttempts(stub);
                ArtifactAcquisitionDeliveryAttemptResponse latestAttempt = deliveryAttempts.isEmpty()
                        ? buildDeliveryAttempt(stub.requestId(), request.callbackToken(), stub.providerJobId(), "FAILED")
                        : deliveryAttempts.get(deliveryAttempts.size() - 1);
                return new ArtifactAcquisitionAckResponse(
                        new ArtifactAcquisitionOperationResponse(
                                stub.requestId(),
                                stub.taskId(),
                                stub.capabilityName(),
                                "builtin-bilibili-mcp",
                                "builtin-bilibili-mcp",
                                "get_bilibili_subtitle",
                                stub.providerJobId(),
                                latestAttempt.providerReceiptId(),
                                latestAttempt.deliveryId(),
                                request.callbackToken(),
                                "UNAVAILABLE",
                                "HEALTHY",
                                "FAILED",
                                "FAILED",
                                stub.dispatchCount(),
                                deliveryAttempts
                        ),
                        new ArtifactAcquisitionReceiptResponse(
                                "acq-callback-" + stub.requestId() + "-failed",
                                stub.requestId(),
                                stub.taskId(),
                                "src-bili-1",
                                "EXTRACT_TRANSCRIPT",
                                latestAttempt.deliveryId(),
                                request.callbackToken(),
                                "FAILED",
                                "FAILED",
                                latestAttempt.providerReceiptId(),
                                stub.providerJobId(),
                                blankIfNull(request.resultLocator()),
                                "2026-07-07T06:00:00Z",
                                blankIfNull(request.errorCode()),
                                blankIfNull(request.errorMessage()),
                                stub.dispatchCount()
                        ),
                        List.of()
                );
            }
            pendingRuntimeTraceByTaskId.put(
                    stub.taskId(),
                    buildAcquisitionCallbackRuntimeTrace(
                            stub.requestId(),
                            stub.taskId(),
                            "src-bili-1",
                            stub.providerJobId(),
                            request.callbackToken(),
                            stub.dispatchCount(),
                            stub.providerDeliveryAttempts()
                    )
            );
            ArtifactWorkerExecutionResponse resumed = resumeTask(
                    stub.taskId(),
                    new ArtifactWorkerResumeRequest(stub.requestId(), "")
            );
            List<ArtifactAcquisitionDeliveryAttemptResponse> deliveryAttempts = buildDeliveryAttempts(stub);
            ArtifactAcquisitionDeliveryAttemptResponse latestAttempt = deliveryAttempts.isEmpty()
                    ? buildDeliveryAttempt(stub.requestId(), request.callbackToken(), stub.providerJobId(), "ACKNOWLEDGED")
                    : deliveryAttempts.get(deliveryAttempts.size() - 1);
            return new ArtifactAcquisitionAckResponse(
                    new ArtifactAcquisitionOperationResponse(
                            stub.requestId(),
                            stub.taskId(),
                            stub.capabilityName(),
                            "builtin-bilibili-mcp",
                            "builtin-bilibili-mcp",
                            "get_bilibili_subtitle",
                            stub.providerJobId(),
                            latestAttempt.providerReceiptId(),
                            latestAttempt.deliveryId(),
                            request.callbackToken(),
                            "AVAILABLE",
                            "HEALTHY",
                            "SUCCEEDED",
                            "ACKNOWLEDGED",
                            stub.dispatchCount(),
                            deliveryAttempts
                    ),
                    new ArtifactAcquisitionReceiptResponse(
                            "acq-callback-" + stub.requestId() + "-acknowledged",
                            stub.requestId(),
                            stub.taskId(),
                            "src-bili-1",
                            "EXTRACT_TRANSCRIPT",
                            latestAttempt.deliveryId(),
                            request.callbackToken(),
                            "SUCCEEDED",
                            "ACKNOWLEDGED",
                            latestAttempt.providerReceiptId(),
                            stub.providerJobId(),
                            blankIfNull(request.resultLocator()),
                            "2026-07-07T06:00:00Z",
                            "",
                            "",
                            stub.dispatchCount()
                    ),
                    List.of(resumed)
            );
        }

        void stubResume(
                String taskId,
                String resultTitle,
                String markdown,
                String traceSummary,
                List<Map<String, Object>> citations
        ) {
            resumeStubs.put(taskId, new ResumeStub(resultTitle, markdown, traceSummary, citations, "provider callback 已到达，开始整理讲义内容"));
        }

        void stubAcquisitionAck(
                String callbackToken,
                String taskId,
                String requestId,
                String capabilityName,
                String providerJobId
        ) {
            acquisitionAckStubs.put(
                    callbackToken,
                    new AcquisitionAckStub(
                            taskId,
                            requestId,
                            capabilityName,
                            providerJobId,
                            "ACKNOWLEDGED",
                            1,
                            List.of(Map.ofEntries(
                                    Map.entry("delivery_id", "acq-delivery-" + requestId + "-1"),
                                    Map.entry("dispatch_count", 1),
                                    Map.entry("callback_token", callbackToken),
                                    Map.entry("dispatched_at", "2026-07-07T05:58:00Z"),
                                    Map.entry("callback_deadline_at", "2026-07-07T06:05:00Z"),
                                    Map.entry("provider_job_id", providerJobId),
                                    Map.entry("provider_receipt_id", "provider-receipt-" + requestId),
                                    Map.entry("input_digest", "artifact-input-digest-" + requestId),
                                    Map.entry("ack_status", "ACKNOWLEDGED"),
                                    Map.entry("callback_received_at", "2026-07-07T06:00:00Z"),
                                    Map.entry("result_locator", "bilibili://subtitle/BV1NoteWeaveDemo"),
                                    Map.entry("error_code", ""),
                                    Map.entry("error_message", "")
                            ))
                    )
            );
        }

        void stubFailedAcquisitionAck(
                String callbackToken,
                String taskId,
                String requestId,
                String capabilityName,
                String providerJobId
        ) {
            acquisitionAckStubs.put(
                    callbackToken,
                    new AcquisitionAckStub(
                            taskId,
                            requestId,
                            capabilityName,
                            providerJobId,
                            "FAILED",
                            1,
                            List.of(Map.ofEntries(
                                    Map.entry("delivery_id", "acq-delivery-" + requestId + "-1"),
                                    Map.entry("dispatch_count", 1),
                                    Map.entry("callback_token", callbackToken),
                                    Map.entry("dispatched_at", "2026-07-07T05:58:00Z"),
                                    Map.entry("callback_deadline_at", "2026-07-07T06:05:00Z"),
                                    Map.entry("provider_job_id", providerJobId),
                                    Map.entry("provider_receipt_id", "provider-receipt-" + requestId),
                                    Map.entry("input_digest", "artifact-input-digest-" + requestId),
                                    Map.entry("ack_status", "FAILED"),
                                    Map.entry("callback_received_at", "2026-07-07T06:00:00Z"),
                                    Map.entry("result_locator", ""),
                                    Map.entry("error_code", "PROVIDER_TIMEOUT"),
                                    Map.entry("error_message", "subtitle provider timed out before returning transcript")
                            ))
                    )
            );
        }

        void stubAcquisitionAckWithAttemptHistory(
                String callbackToken,
                String taskId,
                String requestId,
                String capabilityName,
                String providerJobId,
                int dispatchCount,
                List<Map<String, Object>> providerDeliveryAttempts
        ) {
            acquisitionAckStubs.put(
                    callbackToken,
                    new AcquisitionAckStub(
                            taskId,
                            requestId,
                            capabilityName,
                            providerJobId,
                            "ACKNOWLEDGED",
                            dispatchCount,
                            providerDeliveryAttempts
                    )
            );
        }

        List<ResumeInvocation> resumeInvocations() {
            return resumeInvocations;
        }

        List<ArtifactAcquisitionAckRequest> acquisitionAckRequests() {
            return acquisitionAckRequests;
        }

        void reset() {
            resumeInvocations.clear();
            acquisitionAckRequests.clear();
            resumeStubs.clear();
            acquisitionAckStubs.clear();
            pendingRuntimeTraceByTaskId.clear();
        }

        private String blankIfNull(String value) {
            return value == null ? "" : value;
        }

        private Map<String, Object> buildAcquisitionCallbackRuntimeTrace(
                String requestId,
                String taskId,
                String sourceId,
                String providerJobId,
                String callbackToken,
                int dispatchCount,
                List<Map<String, Object>> providerDeliveryAttempts
        ) {
            List<Map<String, Object>> deliveryAttemptTraces = providerDeliveryAttempts.stream()
                    .map(this::buildDeliveryAttemptTrace)
                    .toList();
            Map<String, Object> latestAttempt = providerDeliveryAttempts.isEmpty()
                    ? Map.ofEntries(
                            Map.entry("delivery_id", "acq-delivery-" + requestId + "-1"),
                            Map.entry("provider_receipt_id", "provider-receipt-" + requestId),
                            Map.entry("result_locator", "bilibili://subtitle/BV1NoteWeaveDemo")
                    )
                    : providerDeliveryAttempts.get(providerDeliveryAttempts.size() - 1);
            return Map.of(
                    "acquisition_callback_trace", Map.of(
                            "status", "ATTACHED",
                            "receipt", Map.ofEntries(
                                    Map.entry("receipt_id", "acq-callback-" + requestId + "-acknowledged"),
                                    Map.entry("request_id", requestId),
                                    Map.entry("task_id", taskId),
                                    Map.entry("source_id", sourceId),
                                    Map.entry("operation_key", "EXTRACT_TRANSCRIPT"),
                                    Map.entry("delivery_id", stringValue(latestAttempt.get("delivery_id"))),
                                    Map.entry("callback_token", callbackToken),
                                    Map.entry("provider_job_status", "SUCCEEDED"),
                                    Map.entry("callback_status", "ACKNOWLEDGED"),
                                    Map.entry("provider_receipt_id", stringValue(latestAttempt.get("provider_receipt_id"))),
                                    Map.entry("provider_job_id", providerJobId),
                                    Map.entry("result_locator", stringValue(latestAttempt.get("result_locator"))),
                                    Map.entry("completed_at", "2026-07-07T06:00:00Z"),
                                    Map.entry("dispatch_count", dispatchCount)
                            ),
                            "operation", Map.ofEntries(
                                    Map.entry("request_id", requestId),
                                    Map.entry("provider_job_id", providerJobId),
                                    Map.entry("capability_name", "EXTRACT_TRANSCRIPT"),
                                    Map.entry("provider_id", "builtin-bilibili-mcp"),
                                    Map.entry("server_id", "builtin-bilibili-mcp"),
                                    Map.entry("tool_name", "get_bilibili_subtitle"),
                                    Map.entry("callback_token", callbackToken),
                                    Map.entry("provider_status", "AVAILABLE"),
                                    Map.entry("health_status", "HEALTHY"),
                                    Map.entry("provider_job_status", "SUCCEEDED"),
                                    Map.entry("callback_status", "ACKNOWLEDGED"),
                                    Map.entry("delivery_id", stringValue(latestAttempt.get("delivery_id"))),
                                    Map.entry("provider_receipt_id", stringValue(latestAttempt.get("provider_receipt_id"))),
                                    Map.entry("dispatch_count", dispatchCount),
                                    Map.entry("provider_delivery_attempts", deliveryAttemptTraces)
                            )
                    )
            );
        }

        private ArtifactAcquisitionDeliveryAttemptResponse buildDeliveryAttempt(
                String requestId,
                String callbackToken,
                String providerJobId,
                String ackStatus
        ) {
            return new ArtifactAcquisitionDeliveryAttemptResponse(
                    "acq-delivery-" + requestId + "-1",
                    1,
                    callbackToken,
                    "2026-07-07T05:58:00Z",
                    "2026-07-07T06:05:00Z",
                    providerJobId,
                    "provider-receipt-" + requestId,
                    "artifact-input-digest-" + requestId,
                    ackStatus,
                    "2026-07-07T06:00:00Z",
                    "ACKNOWLEDGED".equalsIgnoreCase(ackStatus) ? "bilibili://subtitle/BV1NoteWeaveDemo" : "",
                    "FAILED".equalsIgnoreCase(ackStatus) ? "PROVIDER_TIMEOUT" : "",
                    "FAILED".equalsIgnoreCase(ackStatus) ? "subtitle provider timed out before returning transcript" : ""
            );
        }

        private List<ArtifactAcquisitionDeliveryAttemptResponse> buildDeliveryAttempts(AcquisitionAckStub stub) {
            return stub.providerDeliveryAttempts().stream()
                    .map(attempt -> new ArtifactAcquisitionDeliveryAttemptResponse(
                            stringValue(attempt.get("delivery_id")),
                            intValue(attempt.get("dispatch_count")),
                            stringValue(attempt.get("callback_token")),
                            stringValue(attempt.get("dispatched_at")),
                            stringValue(attempt.get("callback_deadline_at")),
                            stringValue(attempt.get("provider_job_id")),
                            stringValue(attempt.get("provider_receipt_id")),
                            stringValue(attempt.get("input_digest")),
                            stringValue(attempt.get("ack_status")),
                            stringValue(attempt.get("callback_received_at")),
                            stringValue(attempt.get("result_locator")),
                            stringValue(attempt.get("error_code")),
                            stringValue(attempt.get("error_message"))
                    ))
                    .toList();
        }

        private Map<String, Object> buildDeliveryAttemptTrace(
                Map<String, Object> attempt
        ) {
            return Map.ofEntries(
                    Map.entry("delivery_id", stringValue(attempt.get("delivery_id"))),
                    Map.entry("dispatch_count", intValue(attempt.get("dispatch_count"))),
                    Map.entry("callback_token", stringValue(attempt.get("callback_token"))),
                    Map.entry("dispatched_at", stringValue(attempt.get("dispatched_at"))),
                    Map.entry("callback_deadline_at", stringValue(attempt.get("callback_deadline_at"))),
                    Map.entry("provider_job_id", stringValue(attempt.get("provider_job_id"))),
                    Map.entry("provider_receipt_id", stringValue(attempt.get("provider_receipt_id"))),
                    Map.entry("input_digest", stringValue(attempt.get("input_digest"))),
                    Map.entry("ack_status", stringValue(attempt.get("ack_status"))),
                    Map.entry("callback_received_at", stringValue(attempt.get("callback_received_at"))),
                    Map.entry("result_locator", stringValue(attempt.get("result_locator"))),
                    Map.entry("error_code", stringValue(attempt.get("error_code"))),
                    Map.entry("error_message", stringValue(attempt.get("error_message")))
            );
        }

        private String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private Integer intValue(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null) {
                return 0;
            }
            return Integer.parseInt(String.valueOf(value));
        }
    }

    record ResumeInvocation(String taskId, ArtifactWorkerResumeRequest request) {
    }

    record ResumeStub(
            String resultTitle,
            String markdown,
            String traceSummary,
            List<Map<String, Object>> citations,
            String progressMessage
    ) {
    }

    record AcquisitionAckStub(
            String taskId,
            String requestId,
            String capabilityName,
            String providerJobId,
            String finalStatus,
            int dispatchCount,
            List<Map<String, Object>> providerDeliveryAttempts
    ) {
    }
}
