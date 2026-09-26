package com.noteweave;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.noteweave");

    @Test
    void legacyKnowledgeFacadeMustRemainDeleted() {
        assertThat(CLASSES.stream().map(javaClass -> javaClass.getSimpleName()))
                .doesNotContain("KnowledgeService");
    }

    @Test
    void memoryCandidateDecisionsMustUseVersionedPolicyAndSharedMatcher() {
        classes()
                .that().haveSimpleName("MemorySignalService")
                .should().dependOnClassesThat().haveSimpleName("MemoryCandidatePolicy")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("MemoryCandidateService")
                .should().dependOnClassesThat().haveSimpleName("MemoryCandidateGate")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryCandidatePolicy")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryStatementMatcher")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("CanonicalMemoryReviewService")
                .should().dependOnClassesThat().haveSimpleName("MemoryStatementMatcher")
                .check(CLASSES);
    }

    @Test
    void memoryPromotionMustCreateCanonicalRevisionsThroughCanonicalOwner() {
        classes()
                .that().haveSimpleName("MemoryPromotionService")
                .should().dependOnClassesThat().haveSimpleName("CanonicalMemoryReviewService")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryVersionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryPromotionService")
                .because("legacy version compatibility must remain outside canonical promotion")
                .check(CLASSES);
    }

    @Test
    void memoryOutcomeDecisionsMustUseVersionedOutcomePolicy() {
        classes()
                .that().haveSimpleName("MemoryOutcomeService")
                .should().dependOnClassesThat().haveSimpleName("MemoryOutcomePolicy")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryOutcomePolicy")
                .should().dependOnClassesThat().haveSimpleName("MemoryOutcomeService")
                .because("Outcome thresholds and lifecycle decisions must remain independently testable")
                .check(CLASSES);
    }

    @Test
    void memoryCompilerMustUseCapabilityPortAndVersionedCompilerPolicy() {
        noClasses()
                .that().resideInAPackage("com.noteweave.memory..")
                .should().dependOnClassesThat().resideInAPackage("com.noteweave.artifact..")
                .because("Memory compilation must use the generic capability catalog port")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("MemoryCompilerService")
                .should().dependOnClassesThat().haveSimpleName("CapabilityCatalogPort")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryCompilerPolicy")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryCompiledPackCache")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryCompiledPackCache")
                .should().dependOnClassesThat().haveSimpleName("MemoryPromotionService")
                .orShould().dependOnClassesThat().haveSimpleName("MemoryVersionService")
                .orShould().dependOnClassesThat().haveSimpleName("CanonicalMemoryReviewService")
                .orShould().dependOnClassesThat().haveSimpleName("MemoryOutcomeService")
                .because("compiled pack cache is a read adapter and owns no Memory lifecycle writes")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("ArtifactSkillCatalogService")
                .should().implement("com.noteweave.capability.CapabilityCatalogPort")
                .check(CLASSES);
    }

    @Test
    void productionMemoryCompilerMustNotReadLegacyMemoryTables() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/noteweave/memory/MemoryCompilerService.java"));

        assertThat(source.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("from memory_object")
                .doesNotContain("join memory_object")
                .doesNotContain("from memory_version")
                .doesNotContain("join memory_version");
    }

    @Test
    void kafkaPipelineMustExposeOnlyRealConsumersAndDisableThemWithKafka() throws IOException {
        String properties = Files.readString(Path.of(
                "src/main/java/com/noteweave/config/NoteWeaveProperties.java"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String consumer = Files.readString(Path.of(
                "src/main/java/com/noteweave/infra/KafkaTaskConsumer.java"));
        String dispatcher = Files.readString(Path.of(
                "src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java"));

        assertThat(properties).doesNotContain("noteweave.source.chunk", "noteweave.generated.ingest",
                "noteweave.source.index");
        assertThat(application).doesNotContain(
                "source-chunk:", "generated-ingest:", "source-index:",
                "legacy-research-outbox-enabled", "noteweave.research.run"
        );
        assertThat(dispatcher).doesNotContain("source.index", "sourceIndex");
        assertThat(consumer)
                .contains("@ConditionalOnProperty(name = \"noteweave.kafka.enabled\"")
                .contains("${noteweave.kafka.topics.conversation-summary}")
                .doesNotContain("onSourceChunk", "onGeneratedIngest");
        assertThat(CLASSES.stream().map(javaClass -> javaClass.getSimpleName()))
                .doesNotContain(
                        "ResearchOutboxDispatchScheduler",
                        "ResearchOutboxDispatcherService",
                        "ResearchOutboxDispatcherController",
                        "KafkaResearchOutboxPublisher",
                        "ResearchOutboxPublisher"
                );
    }

    @Test
    void elasticsearchHealthMustFollowTheElasticsearchFeatureFlag() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application)
                .contains("enabled: ${NOTEWEAVE_ES_ENABLED:true}")
                .contains("management:\n  health:\n    elasticsearch:\n      enabled: ${NOTEWEAVE_ES_ENABLED:true}");
    }

    @Test
    void retrievalAndChatProvidersMustDefaultOnWithEmptyCredentials() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application)
                .contains("enabled: ${NOTEWEAVE_EMBEDDING_ENABLED:true}")
                .contains("api-key: ${NOTEWEAVE_EMBEDDING_API_KEY:}")
                .contains("enabled: ${NOTEWEAVE_RERANK_ENABLED:true}")
                .contains("api-key: ${NOTEWEAVE_RERANK_API_KEY:}")
                .contains("enabled: ${NOTEWEAVE_LLM_ENABLED:true}")
                .contains("api-key: ${NOTEWEAVE_LLM_API_KEY:}");
    }

    @Test
    void durableOutboxDispatchersMustShareClaimAndRetryPolicy() throws IOException {
        String durableDispatcher = Files.readString(Path.of(
                "src/main/java/com/noteweave/infra/outbox/DurableOutboxDispatcher.java"));
        String taskDispatcher = Files.readString(Path.of(
                "src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java"));
        String artifactDispatcher = Files.readString(Path.of(
                "src/main/java/com/noteweave/worker/ArtifactOutboxDispatcherService.java"));
        String agentDispatcher = Files.readString(Path.of(
                "src/main/java/com/noteweave/research/ResearchAgentCommandDispatcher.java"));

        assertThat(CLASSES.stream().map(javaClass -> javaClass.getSimpleName()))
                .contains(
                        "TaskOutboxDispatcherService",
                        "ArtifactOutboxDispatcherService",
                        "ResearchAgentCommandDispatcher"
                )
                .doesNotContain(
                        "ResearchOutboxDispatcherService",
                        "ResearchOutboxDispatchScheduler"
                );
        assertThat(java.util.List.of(taskDispatcher, artifactDispatcher, agentDispatcher))
                .allSatisfy(source -> assertThat(source)
                        .contains("DurableOutboxDispatcher")
                        .doesNotContain(
                                "set status = 'PROCESSING'",
                                "attempt_count = attempt_count + 1",
                                "set status = 'DEAD_LETTER'"
                        ));
        assertThat(durableDispatcher).contains(
                "dispatchTaskMessages(",
                "dispatchAgentCommands(",
                "acknowledgeTaskMessage(",
                "renewTaskMessage(",
                "attempt_count = attempt_count + 1",
                "where id = ? and attempt_count = ?",
                "String deliveryToken = dispatcherId + \":\" + UUID.randomUUID()",
                "where topic = ? and task_id = ? and lease_owner = ?"
        );
    }

    @Test
    void legacyResearchWholeRunWorkerSurfaceMustRemainDeleted() throws IOException {
        assertThat(Path.of("src/main/java/com/noteweave/research/ResearchWorkerInputController.java"))
                .doesNotExist();
        assertThat(Path.of("src/main/java/com/noteweave/research/ResearchWorkerInputResponse.java"))
                .doesNotExist();
        assertThat(Path.of("src/main/java/com/noteweave/research/ResearchWorkerInputPayload.java"))
                .doesNotExist();
        assertThat(Path.of("src/test/java/com/noteweave/research/ResearchRuntimeCheckpointIntegrationTest.java"))
                .doesNotExist();
        assertThat(Path.of("src/test/java/com/noteweave/research/ResearchIncrementalProjectionTest.java"))
                .doesNotExist();

        String callbackService = Files.readString(
                Path.of("src/main/java/com/noteweave/worker/WorkerTaskCallbackService.java"));
        String callbackAuthenticator = Files.readString(
                Path.of("src/main/java/com/noteweave/worker/WorkerTaskCallbackAuthenticator.java"));
        String researchRunService = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunService.java"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(callbackService).doesNotContain("RESEARCH_RUN", "validateResearchCallback");
        assertThat(callbackAuthenticator).doesNotContain("RESEARCH_RUN", "researchSecret");
        assertThat(researchRunService).doesNotContain(
                "completeFromWorker(",
                "persistRuntimeCheckpoint(",
                "persistClosedLoopTraces(",
                "persistClosedLoopState(",
                "persistExecutionCheckpoint(",
                "public void markRunning(",
                "public void markWaiting(",
                "public void markFailed(");
        assertThat(application).doesNotContain("research-callback-secret", "NOTEWEAVE_RESEARCH_CALLBACK_SECRET");
    }

    @Test
    void legacyResearchSplitResultSurfaceMustRemainDeleted() throws IOException {
        for (String file : new String[] {
                "ResearchAgentEvidenceInternalController.java",
                "ResearchAgentCandidateInternalController.java",
                "ResearchAgentEvidenceIngestionService.java",
                "ResearchAgentCandidateIngressService.java",
                "ResearchAgentLegacyResultRawBodyFilter.java",
                "ResearchAgentLegacyResultRouteGuardInterceptor.java",
                "ResearchAgentLegacyResultRouteGuardWebConfig.java"
        }) {
            assertThat(Path.of("src/main/java/com/noteweave/research", file)).doesNotExist();
        }
        assertThat(Path.of("src/test/java/com/noteweave/research/ResearchAgentLegacyResultRouteGuardTest.java"))
                .doesNotExist();
        assertThat(Path.of("src/test/java/com/noteweave/research/ResearchAgentCandidateIngressServiceTest.java"))
                .doesNotExist();

        String taskController = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentTaskInternalController.java"));
        String taskService = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentTaskService.java"));
        assertThat(taskController).doesNotContain("/{taskId}/submit", "SubmitRequest");
        assertThat(taskService).doesNotContain("submitExecution(", "SubmitCommand", "ExecutionReceipt");

        Path researchRoot = Path.of("src/main/java/com/noteweave/research");
        String researchSources;
        try (java.util.stream.Stream<Path> files = Files.walk(researchRoot)) {
            researchSources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        assertThat(researchSources).doesNotContain("workspace-evidence-batches", "candidate-batches");
    }

    @Test
    void researchArtifactPersistenceMustStayOutsideTheRunFacade() throws IOException {
        String runFacade = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunService.java"));
        String queryService = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunQueryService.java"));
        String provenanceEnricher = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchSourceProvenanceEnricher.java"));
        String sourceScopeLoader = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchSourceScopeLoader.java"));
        String artifactService = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchArtifactService.java"));
        String readModelMapper = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchReadModelMapper.java"));
        String checkpointStore = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchCheckpointStore.java"));
        String commandService = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunCommandService.java"));

        assertThat(runFacade).doesNotContain(
                "insert into source(",
                "insert into file_object(",
                "from research_evidence_manifest_item",
                "SourceParseService",
                "WikiIngestService"
        );
        assertThat(runFacade).contains(
                "ResearchRunCommandService commandService",
                "ResearchRunQueryService queryService",
                "ResearchArtifactService artifactService"
        );
        assertThat(runFacade).doesNotContain(
                "JdbcTemplate",
                "ObjectMapper",
                "WorkspaceService",
                "ObjectStorage",
                "private ResearchCheckpointSnapshotSummaryResponse readCheckpointSnapshotSummaryResponse(",
                "private ResearchStateLedgerResponse readStateLedgerResponse(",
                "private ResearchArtifactCandidateResponse readResearchArtifactCandidateResponse(",
                "private ResearchCounterfactualSummaryResponse readCounterfactualSummary(",
                "from research_execution_checkpoint",
                "join research_run rr on rr.id = rec.research_run_id",
                "insert into research_run(",
                "MemoryCompilerService",
                "ResearchAgentRunBootstrapService",
                "ResearchAgentExternalEvidencePolicy"
        );
        assertThat(queryService).contains(
                "import static com.noteweave.research.ResearchReadModelMapper.*;",
                "ResearchCheckpointStore researchCheckpointStore",
                "ResearchSourceProvenanceEnricher sourceProvenanceEnricher",
                "ResearchSourceScopeLoader sourceScopeLoader",
                "List<ResearchRunSummaryResponse> listRuns(",
                "ResearchRunDetailResponse getRunDetail(",
                "List<ResearchCheckpointSummaryResponse> listCheckpoints(",
                "ResearchCheckpointResponse getCheckpoint("
        ).doesNotContain(
                "ResearchRunCommandService",
                "ResearchRunResponse createRun(",
                "ResearchRunResponse resumeFromCheckpoint(",
                "void setAgentExecutionMode(",
                "collectSourceIds(",
                "loadSourceOrigins(",
                "enrichResearchSourceProvenance(",
                "loadSourceScopeItem(",
                "readSourceScopeIds(",
                "where s.workspace_id = ? and s.id = ?"
        );
        assertThat(provenanceEnricher).contains(
                "void enrich(Object value)",
                "collectSourceIds(",
                "loadSourceOrigins(",
                "where id in (%s)"
        );
        assertThat(sourceScopeLoader).contains(
                "List<WorkerSourceScopeItemResponse> load(",
                "int count(",
                "and s.id in (%s)",
                "sourceIds.stream().map(itemsById::get)"
        ).doesNotContain("where s.workspace_id = ? and s.id = ?");
        assertThat(readModelMapper).contains(
                "readCheckpointSnapshotSummaryResponse(",
                "readStateLedgerResponse(",
                "readResearchArtifactCandidateResponse(",
                "readCounterfactualSummary("
        );
        assertThat(checkpointStore).contains(
                "List<ResearchCheckpointRecord> findAll(",
                "ResearchCheckpointRecord get(",
                "from research_execution_checkpoint",
                "join research_run rr on rr.id = rec.research_run_id"
        );
        assertThat(commandService).contains(
                "ResearchRunResponse createRun(",
                "ResearchRunResponse resumeFromCheckpoint(",
                "void setAgentExecutionMode(",
                "insert into research_run(",
                "MemoryCompilerService",
                "ResearchAgentRunBootstrapService",
                "ResearchAgentExternalEvidencePolicy"
        );
        assertThat(artifactService).contains(
                "saveReportAsSource(",
                "evidenceManifest(",
                "buildResearchArtifact("
        );
    }

    @Test
    void largeServiceReadAndEventSqlMustStayBehindDedicatedPersistenceSeams() throws IOException {
        String researchFacade = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunQueryService.java"));
        String researchReadRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunReadRepository.java"));
        String researchClosedLoopAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchClosedLoopStateAssembler.java"));
        String researchCounterfactualAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchCounterfactualSummaryAssembler.java"));
        String researchCheckpointProcessAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchCheckpointProcessAssembler.java"));
        String researchVerifierGatedAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchVerifierGatedSummaryAssembler.java"));
        String researchReportAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchReportReadModelAssembler.java"));
        String researchPayloadReader = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchRunQueryPayloadReader.java"));
        String artifactFacade = Files.readString(
                Path.of("src/main/java/com/noteweave/artifact/ArtifactJobService.java"));
        String artifactReadRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/artifact/ArtifactJobReadRepository.java"));
        String artifactWriteRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/artifact/ArtifactJobWriteRepository.java"));
        String artifactPayloadAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/artifact/ArtifactPayloadReadModelAssembler.java"));
        String taskService = Files.readString(
                Path.of("src/main/java/com/noteweave/task/TaskService.java"));
        String taskReadRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/task/TaskReadRepository.java"));
        String taskStateRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/task/TaskStateRepository.java"));
        String taskEventRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/task/TaskEventRepository.java"));
        String taskWaitAssembler = Files.readString(
                Path.of("src/main/java/com/noteweave/task/TaskWaitContextAssembler.java"));
        String completionCommitter = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentCompletionCommitter.java"));
        String completionWriteRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentCompletionWriteRepository.java"));
        String completionMergePlanner = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentCompletionMergePlanner.java"));
        String completionReceiptBuilder = Files.readString(
                Path.of("src/main/java/com/noteweave/research/ResearchAgentCompletionReceiptPayloadBuilder.java"));

        assertThat(researchFacade)
                .doesNotContain(
                        "where se.research_run_id in (%s)",
                        "where rce.research_run_id in (%s)",
                        "jdbcTemplate.query",
                        "jdbcTemplate.queryForObject",
                        "jdbcTemplate.update"
                );
        assertThat(researchReadRepository)
                .contains("ResearchRunReadBundle loadBatch(", "where research_run_id in (%s)", "void requireRun(");
        assertThat(researchFacade)
                .doesNotContain("new ResearchClosedLoopStateResponse(", "private Map<String, Object> mergeStateLedgerSnapshot(")
                .contains("researchClosedLoopStateAssembler.build(");
        assertThat(researchClosedLoopAssembler)
                .contains("ResearchClosedLoopStateResponse build(", "mergeStateLedgerSnapshot(", "readStateLedgerResponse(");
        assertThat(researchCounterfactualAssembler)
                .contains("ResearchCounterfactualSummaryResponse build(", "COUNTERFACTUAL_RECHECK");
        assertThat(researchFacade)
                .doesNotContain(
                        "private ResearchProcessSummaryResponse buildCheckpointProcessSummary(",
                        "private Map<String, Object> buildVerifierGatedSummary(",
                        "private List<Map<String, Object>> buildCheckpointRowsByPredicate("
                );
        assertThat(researchCheckpointProcessAssembler)
                .contains("ResearchProcessSummaryResponse buildProcessSummary(", "buildAuditSummary(")
                .contains("buildCounterfactualSummary(", "buildRecoveryTargetsFromStopContract(");
        assertThat(researchVerifierGatedAssembler)
                .contains("Map<String, Object> build(", "matchesRecoveryTargets(", "blocked_row_samples");
        assertThat(researchFacade)
                .doesNotContain(
                        "private ResearchReportStructureResponse buildReportStructure(",
                        "private ResearchArtifactCandidateResponse buildResearchArtifactCandidate(",
                        "private ResearchResumeContextSummaryResponse buildResumeContextSummary(",
                        "JsonProcessingException",
                        "TypeReference<",
                        "private Map<String, Object> extractTracePayload(",
                        "private Map<String, Object> extractRecoveryTargets("
                );
        assertThat(researchFacade).contains("ResearchRunQueryPayloadReader", "payloadReader.readPayloadMap(");
        assertThat(researchPayloadReader)
                .contains(
                        "readPayloadMap(",
                        "extractTracePayload(",
                        "extractRecoveryTargets(",
                        "RESEARCH_TRACE_PAYLOAD_PARSE_FAILED"
                );
        assertThat(researchReportAssembler)
                .contains(
                        "ResearchReportStructureResponse buildReportStructure(",
                        "ResearchArtifactCandidateResponse buildResearchArtifactCandidate(",
                        "ResearchResumeContextSummaryResponse buildResumeContextSummary("
                );
        assertThat(artifactFacade)
                .doesNotContain(
                        "join task t on t.id = aj.task_id",
                        "coalesce(av.result_payload_json",
                        "insert into artifact_job(",
                        "update artifact_job",
                        "insert into artifact_version(",
                        "insert into task_outbox(",
                        "jdbcTemplate.query",
                        "jdbcTemplate.queryForObject",
                        "jdbcTemplate.update"
                );
        assertThat(artifactReadRepository)
                .contains(
                        "List<ArtifactJobSummaryRow> listJobs(",
                        "ArtifactVersionDetailRow getVersionDetail(",
                        "ArtifactJobTaskRow findByTaskId(",
                        "ArtifactRegenerationRow loadRegenerationRow(",
                        "ArtifactRollbackRow loadRollbackRow(",
                        "boolean validUpstreamRef(",
                        "loadSourceScopeItem(",
                        "loadVersionForSource("
                );
        assertThat(artifactFacade)
                .doesNotContain(
                        "private ArtifactRuntimeTraceResponse readRuntimeTrace(",
                        "private <T> T convertTraceValue(",
                        "private Map<String, Object> normalizeAndStripLegacyActionKeys("
                );
        assertThat(artifactPayloadAssembler)
                .contains(
                        "ArtifactRuntimeTraceResponse readRuntimeTrace(",
                        "MemoryControlPackResponse readControlPack(",
                        "ARTIFACT_RUNTIME_OUTPUT_CONTRACT_PARSE_FAILED"
                );
        assertThat(artifactWriteRepository)
                .contains("persistInitialJob(", "persistRegeneration(", "appendCompletedVersion(", "insert into task_outbox(");
        assertThat(taskService).doesNotContain("insert into task_event", "insert into task(", "update task");
        assertThat(taskService)
                .contains("TaskWaitContextAssembler waitContextAssembler", "waitContextAssembler.build(")
                .doesNotContain(
                        "private WaitProviderJobResponse toWaitProviderJob(",
                        "private WaitApprovalRequestResponse toWaitApprovalRequest(",
                        "private WaitReasonResponse toWaitReason(",
                        "private List<WaitProviderDeliveryAttemptResponse> readProviderDeliveryAttempts("
                );
        assertThat(taskWaitAssembler)
                .contains(
                        "WaitContextResponse build(",
                        "Map<String, WaitContextResponse> buildAll(",
                        "TASK_EVENT_PAYLOAD_PARSE_FAILED"
                );
        assertThat(taskReadRepository).contains("TaskService.TaskStreamPoll poll(", "from task_event");
        assertThat(taskEventRepository).contains("insert into task_event");
        assertThat(taskStateRepository).contains("insert into task(", "update task", "int redrive(");
        assertThat(completionCommitter).doesNotContain("insert into source_evidence(", "insert into research_agent_candidate(");
        assertThat(completionCommitter)
                .contains("ResearchAgentCompletionMergePlanner mergePlanner", "mergePlanner.planSingle(", "mergePlanner.planQuorum(")
                .contains("receiptPayloadBuilder.build(")
                .doesNotContain(
                        "private MergeOutcome planQuorumOutcome(",
                        "private MergeOutcome quorumOutcome(",
                        "private Map<String, Object> receiptPayload(",
                        "private Map<String, Object> mergeReceiptPayload("
                );
        assertThat(completionMergePlanner)
                .contains("MergeOutcome planSingle(", "MergeOutcome planQuorum(", "MergeOutcome quorumOutcome(")
                .doesNotContain("JdbcTemplate", "jdbcTemplate.");
        assertThat(completionReceiptBuilder)
                .contains("Map<String, Object> build(", "receipt_digest", "accepted_merges");
        assertThat(completionWriteRepository)
                .contains("insert into source_evidence(", "insert into research_agent_candidate(");
    }

    @Test
    void conversationTurnPayloadMustStayOutsideSubmissionTransactionFacade() throws IOException {
        String turnModule = Files.readString(
                Path.of("src/main/java/com/noteweave/conversation/ConversationTurnModule.java"));
        String payloadCodec = Files.readString(
                Path.of("src/main/java/com/noteweave/conversation/ConversationTurnPayloadCodec.java"));

        assertThat(turnModule)
                .contains("ConversationTurnPayloadCodec payloadCodec")
                .doesNotContain(
                        "private String requestHash(",
                        "private String preparationJson(",
                        "private String writeReceipt(",
                        "private TurnReceipt readReceipt(",
                        "objectMapper.writeValueAsString(",
                        "objectMapper.readValue("
                );
        assertThat(payloadCodec)
                .contains(
                        "String requestHash(",
                        "String preparationJson(",
                        "SubmitTurnCommand readFrozenCommand(",
                        "String writeReceipt(",
                        "TurnReceipt readReceipt("
                );
    }

    @Test
    void productionQaRetrievalMustRemainFailClosed() throws IOException {
        String retriever = Files.readString(
                Path.of("src/main/java/com/noteweave/chat/QaPassageRetriever.java"));
        String guard = Files.readString(
                Path.of("src/main/java/com/noteweave/config/ProductionConfigurationGuard.java"));

        assertThat(retriever)
                .contains("QA_RETRIEVAL_PROVIDER_UNAVAILABLE")
                .doesNotContain("hybridRetriever, true", "retrievalHydrator, null, true");
        assertThat(guard)
                .contains("qaMysqlFallbackEnabled")
                .contains("Production must disable NOTEWEAVE_QA_MYSQL_FALLBACK_ENABLED");
    }

    @Test
    void disabledRetrievalProjectionMustRemainFailClosed() throws IOException {
        String writer = Files.readString(Path.of(
                "src/main/java/com/noteweave/retrieval/index/NoOpRetrievalProjectionWriter.java"));

        assertThat(writer)
                .contains("RETRIEVAL_PROJECTION_PROVIDER_DISABLED")
                .doesNotContain("/** No-op projection writer", "{\n    }", "{\r\n    }");
    }

    @Test
    void distributedQuotaAndCancellationMustRemainFailClosed() throws IOException {
        String quota = Files.readString(
                Path.of("src/main/java/com/noteweave/quota/WorkloadQuotaService.java"));
        String realtime = Files.readString(
                Path.of("src/main/java/com/noteweave/infra/RedisAnswerRealtimeBridge.java"));
        String guard = Files.readString(
                Path.of("src/main/java/com/noteweave/config/ProductionConfigurationGuard.java"));

        assertThat(quota)
                .contains("WORKLOAD_QUOTA_UNAVAILABLE")
                .doesNotContain("leaseSeconds, true, clock");
        assertThat(realtime)
                .contains("Redis cancellation read failed")
                .contains("return true;");
        assertThat(guard)
                .contains("quotaLocalFallbackEnabled")
                .contains("Production must disable NOTEWEAVE_QUOTA_LOCAL_FALLBACK_ENABLED");
    }

    @Test
    void productionAnswerGenerationMustNotReplayTemplatesAsModelOutput() throws IOException {
        String adapter = Files.readString(
                Path.of("src/main/java/com/noteweave/chat/ChatAnswerGenerationAdapter.java"));
        String guard = Files.readString(
                Path.of("src/main/java/com/noteweave/config/ProductionConfigurationGuard.java"));

        assertThat(adapter)
                .contains("ANSWER_LLM_CONFIGURATION_REQUIRED")
                .contains("noteweave.llm.template-fallback-enabled:false");
        assertThat(guard)
                .contains("llmTemplateFallbackEnabled")
                .contains("Production must disable NOTEWEAVE_LLM_TEMPLATE_FALLBACK_ENABLED");
    }

    @Test
    void userFacingArtifactAndWaitDtosMustNotExposeCallbackTokens() throws IOException {
        for (String path : new String[] {
                "src/main/java/com/noteweave/task/WaitProviderJobResponse.java",
                "src/main/java/com/noteweave/task/WaitProviderDeliveryAttemptResponse.java",
                "src/main/java/com/noteweave/artifact/ArtifactAcquisitionCallbackReceiptTraceResponse.java",
                "src/main/java/com/noteweave/artifact/ArtifactAcquisitionOperationTraceResponse.java",
                "src/main/java/com/noteweave/artifact/ArtifactAcquisitionDeliveryAttemptTraceResponse.java"
        }) {
            assertThat(Files.readString(Path.of(path)))
                    .doesNotContain("callbackToken", "adapterCallbackToken");
        }
    }

    @Test
    void researchModuleMustNotWriteConversationMessagesDirectly() throws IOException {
        Path researchRoot = Path.of("src/main/java/com/noteweave/research");
        String sources;
        try (java.util.stream.Stream<Path> files = Files.walk(researchRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .collect(java.util.stream.Collectors.joining("\n"))
                    .toLowerCase(java.util.Locale.ROOT);
        }

        assertThat(sources)
                .doesNotContain("insert into conversation_message")
                .doesNotContain("update conversation_message");
    }

    @Test
    void canonicalMemoryReviewMustOwnPromotionProjectionAndConflictResolution() {
        classes()
                .that().haveSimpleName("CanonicalMemoryReviewService")
                .should().dependOnClassesThat().haveSimpleName("MemoryStatementMatcher")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("MemoryPromotionService")
                .should().dependOnClassesThat().haveSimpleName("CanonicalMemoryReviewService")
                .because("candidate promotion must project directly into the canonical runtime")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryVersionService")
                .should().dependOnClassesThat().haveSimpleName("CanonicalMemoryReviewService")
                .because("legacy version compatibility must not own canonical review")
                .check(CLASSES);
    }

    @Test
    void controllersMustNotUseConcreteMiddlewareOrJdbc() {
        noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc.core..",
                        "org.springframework.kafka.core..",
                        "org.springframework.data.redis..",
                        "co.elastic.clients..",
                        "io.minio.."
                )
                .because("Controller 只能调用应用用例，不能直接编排数据库或中间件")
                .check(CLASSES);
    }

    @Test
    void workspaceModuleMustUsePortsInsteadOfKnowledgeOrInfrastructureImplementations() {
        noClasses()
                .that().resideInAPackage("com.noteweave.workspace..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.noteweave.knowledge..",
                        "com.noteweave.source..",
                        "com.noteweave.infra.."
                )
                .because("Workspace 模块通过端口发出 Wiki 命令，不依赖实现模块")
                .check(CLASSES);
    }

    @Test
    void sourceControllerMustStayInsideItsApplicationBoundary() {
        noClasses()
                .that().haveSimpleName("SourceController")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.noteweave.knowledge..",
                        "com.noteweave.workspace..",
                        "com.noteweave.artifact..",
                        "com.noteweave.research..",
                        "com.noteweave.memory.."
                )
                .because("Source Controller 只面向 Source 应用 API")
                .check(CLASSES);
    }

    @Test
    void securityMustNotDependOnBusinessModules() {
        noClasses()
                .that().resideInAPackage("com.noteweave.security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.noteweave.workspace..",
                        "com.noteweave.source..",
                        "com.noteweave.knowledge..",
                        "com.noteweave.memory..",
                        "com.noteweave.research..",
                        "com.noteweave.artifact.."
                )
                .because("统一身份与授权内核不能反向依赖业务模块")
                .check(CLASSES);
    }

    @Test
    void sourceAndUploadApplicationsMustNotDependOnInfrastructureImplementations() {
        noClasses()
                .that().resideInAnyPackage("com.noteweave.source..", "com.noteweave.upload..")
                .should().dependOnClassesThat().resideInAPackage("com.noteweave.infra..")
                .because("Source 与 Upload 只能依赖存储、消息和任务端口")
                .check(CLASSES);
    }

    @Test
    void generatedSourceMustNotDependOnKnowledgeImplementation() {
        noClasses()
                .that().haveSimpleName("GeneratedSourceService")
                .should().dependOnClassesThat().resideInAPackage("com.noteweave.knowledge..")
                .because("生成资料通过 SourceWikiCommandPort 发命令")
                .check(CLASSES);
    }

    @Test
    void sourceCatalogWritersMustAdvanceTheSharedCacheVersion() {
        classes()
                .that(new DescribedPredicate<>("Source catalog writers") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return Set.of(
                                "SourceService",
                                "SourceParseService",
                                "GeneratedSourceService",
                                "UploadService",
                                "ResearchArtifactService"
                        ).contains(javaClass.getSimpleName());
                    }
                })
                .should().dependOnClassesThat().haveSimpleName("SourceCatalogVersionService")
                .because("every visible Source catalog mutation must advance the versioned Redis read model")
                .check(CLASSES);
    }

    @Test
    void knowledgeQueryMustCacheOnlyImmutableWikiVersionSnapshots() {
        classes()
                .that().haveSimpleName("KnowledgeQueryService")
                .should().dependOnClassesThat().haveSimpleName("WikiPageVersionCache")
                .andShould().dependOnClassesThat().haveSimpleName("KnowledgePageVersionSnapshot")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("WikiPageVersionCache")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeCommandService")
                .orShould().dependOnClassesThat().haveSimpleName("KnowledgeVersionService")
                .because("the Redis adapter stores immutable read snapshots and owns no mutation lifecycle")
                .check(CLASSES);
    }

    @Test
    void longRunningQuotaLeaseMustBeOwnedByTaskLifecycle() {
        classes()
                .that().haveSimpleName("TaskService")
                .should().dependOnClassesThat().haveSimpleName("WorkloadQuotaService")
                .check(CLASSES);
        classes()
                .that().haveSimpleName("ChatService")
                .should().dependOnClassesThat().haveSimpleName("WorkloadQuotaService")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("WorkloadQuotaService")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.noteweave.artifact..",
                        "com.noteweave.research..",
                        "com.noteweave.chat..")
                .because("quota is infrastructure policy and must not depend on workload implementations")
                .check(CLASSES);
    }

    @Test
    void knowledgeMustNotDependOnWorkspaceServiceImplementation() {
        noClasses()
                .that().resideInAPackage("com.noteweave.knowledge..")
                .should().dependOnClassesThat().haveSimpleName("WorkspaceService")
                .because("Knowledge 只依赖 WorkspaceQueryPort")
                .check(CLASSES);
    }

    @Test
    void chatRetrievalMustUseSearchPortInsteadOfInfrastructureImplementation() {
        noClasses()
                .that().resideInAPackage("com.noteweave.chat..")
                .should().dependOnClassesThat().resideInAPackage("com.noteweave.infra..")
                .because("Chat retrieval depends on the search capability port, not Elasticsearch infrastructure")
                .check(CLASSES);
    }

    @Test
    void answerRunCoreMustNotDependOnChatOrInfrastructureImplementations() {
        noClasses()
                .that().resideInAPackage("com.noteweave.answer..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.noteweave.chat..",
                        "com.noteweave.infra..",
                        "com.noteweave.knowledge.."
                )
                .because("AnswerRun lifecycle is an application boundary and must not depend on mode or middleware implementations")
                .check(CLASSES);
    }

    @Test
    void chatFacadeMustNotReabsorbAnswerStreamingInfrastructure() {
        for (String forbiddenType : new String[] {
                "SessionEventMux",
                "ConversationEventMux",
                "AnswerCancellationRegistry",
                "BufferedAnswerDeltaEmitter",
                "ChatLlmClient",
                "AnswerRunService"
        }) {
            noClasses()
                    .that().haveSimpleName("ChatService")
                    .should().dependOnClassesThat().haveSimpleName(forbiddenType)
                    .because("ChatService is a message/retrieval facade; Answer orchestration belongs to the Answer module")
                    .check(CLASSES);
        }
    }

    @Test
    void chatFacadeMustDispatchModesOnlyThroughStrategyRegistry() {
        for (String forbiddenType : new String[] {
                "NoteRetrievalService",
                "KnowledgeService",
                "QaPassageEvidenceRetriever",
                "NoteEvidenceRetriever",
                "WikiEvidenceRetriever"
        }) {
            noClasses()
                    .that().haveSimpleName("ChatService")
                    .should().dependOnClassesThat().haveSimpleName(forbiddenType)
                    .because("ChatService must dispatch Answer modes through AnswerModeStrategyRegistry")
                    .check(CLASSES);
        }
    }

    @Test
    void chatConversationContextHeuristicsMustStayOutsideFacadeAndInfrastructure() {
        classes()
                .that().haveSimpleName("ChatService")
                .should().dependOnClassesThat().haveSimpleName("ConversationRetrievalContextAssembler")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("ConversationRetrievalContextAssembler")
                .should().dependOnClassesThat().haveSimpleName("JdbcTemplate")
                .because("conversation retrieval context assembly is deterministic projection logic")
                .check(CLASSES);
    }

    @Test
    void qaEvidenceAdapterMustUseDedicatedRetriever() {
        noClasses()
                .that().haveSimpleName("QaPassageEvidenceRetriever")
                .should().dependOnClassesThat().haveSimpleName("NoteRetrievalService")
                .because("QA evidence adaptation must not depend on the Note retrieval boundary")
                .check(CLASSES);
    }

    @Test
    void wikiEvidenceAdapterMustUseKnowledgeReadPortInsteadOfKnowledgeFacade() {
        noClasses()
                .that().haveSimpleName("WikiEvidenceRetriever")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("Wiki answer retrieval must use the dedicated Knowledge read port")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("KnowledgeQueryService")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("Knowledge read models must not depend back on the legacy Knowledge facade")
                .check(CLASSES);
    }

    @Test
    void knowledgeGraphServiceMustNotDependOnLegacyKnowledgeFacade() {
        noClasses()
                .that().haveSimpleName("KnowledgeGraphService")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("Knowledge graph traversal must remain independently replaceable")
                .check(CLASSES);
    }

    @Test
    void knowledgeVersionServiceMustNotDependOnLegacyKnowledgeFacade() {
        noClasses()
                .that().haveSimpleName("KnowledgeVersionService")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("immutable version allocation must have a single independent owner")
                .check(CLASSES);
    }

    @Test
    void knowledgeCommandServiceMustNotDependOnLegacyKnowledgeFacade() {
        noClasses()
                .that().haveSimpleName("KnowledgeCommandService")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("Knowledge create, rename, delete and upsert commands need one independent owner")
                .check(CLASSES);
    }

    @Test
    void knowledgeGovernanceServiceMustNotDependOnLegacyKnowledgeFacade() {
        noClasses()
                .that().haveSimpleName("KnowledgeGovernanceService")
                .should().dependOnClassesThat().haveSimpleName("KnowledgeService")
                .because("Wiki diagnostics and repair orchestration must remain independently replaceable")
                .check(CLASSES);
    }

    @Test
    void noteRecallOrchestrationMustStayInDedicatedRetriever() {
        noClasses()
                .that().haveSimpleName("NoteRetrievalService")
                .should().dependOnClassesThat().haveSimpleName("NoteRecallRetriever")
                .because("NoteRetrievalService now owns metadata/window reads, not Recall orchestration")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("NoteRecallRetriever")
                .should().dependOnClassesThat().haveSimpleName("JdbcTemplate")
                .because("NoteRecallRetriever must orchestrate through NoteRecallRepository")
                .check(CLASSES);
    }

    @Test
    void qaShadowExportMustKeepReadAndArtifactValidationBehindDedicatedSeams() throws IOException {
        String exportService = Files.readString(
                Path.of("src/main/java/com/noteweave/retrieval/eval/QaAnswerRunShadowExportService.java"));
        String readRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/retrieval/eval/QaAnswerRunShadowExportReadRepository.java"));
        String artifactValidator = Files.readString(
                Path.of("src/main/java/com/noteweave/retrieval/eval/QaAnswerRunShadowArtifactValidator.java"));

        assertThat(exportService)
                .doesNotContain("JdbcTemplate", "jdbcTemplate.", "select ")
                .contains("readRepository.loadRuns(", "artifactValidator.readBundle(");
        assertThat(readRepository)
                .contains("Map<String, RunRow> loadRuns(", "select r.id", "loadCitations(");
        assertThat(artifactValidator)
                .contains("readBundle(", "readSummary(", "validateBundleShape(", "validateTraceShape(");
    }

    @Test
    void noteMetadataFacadeMustKeepRelationQueriesBehindDedicatedService() throws IOException {
        String noteFacade = Files.readString(
                Path.of("src/main/java/com/noteweave/chat/NoteRetrievalService.java"));
        String relationService = Files.readString(
                Path.of("src/main/java/com/noteweave/chat/NoteRelatedEntryService.java"));

        assertThat(noteFacade)
                .doesNotContain("jdbcTemplate.query", "select s.id", "select c1.source_id")
                .contains("relatedEntryService.relatedEntriesForSources(");
        assertThat(relationService)
                .contains("Map<String, List<RelatedEntryPreview>> relatedEntriesForSources(")
                .contains("select s.id", "select c1.source_id", "relatedReason(");
    }

    @Test
    void memoryCompilerMustKeepPersistedReadModelBehindRepository() throws IOException {
        String compilerService = Files.readString(
                Path.of("src/main/java/com/noteweave/memory/MemoryCompilerService.java"));
        String readRepository = Files.readString(
                Path.of("src/main/java/com/noteweave/memory/MemoryCompilerReadRepository.java"));

        assertThat(compilerService)
                .doesNotContain("r.normalized_value_json", "r.valid_from <= current_timestamp")
                .contains("readRepository.loadStateRows(", "readRepository.loadObjectRows(");
        assertThat(readRepository)
                .contains("List<StateRow> loadStateRows(", "List<ObjectRow> loadObjectRows(")
                .contains("readCanonicalPayload(", "hydrateReferences(");
    }
}
