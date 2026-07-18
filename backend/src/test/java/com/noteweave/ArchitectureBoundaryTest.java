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
                .that().haveSimpleName("MemoryPromotionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryStatementMatcher")
                .check(CLASSES);
    }

    @Test
    void memoryPromotionMustCreateImmutableVersionsThroughVersionOwner() {
        classes()
                .that().haveSimpleName("MemoryPromotionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryVersionService")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryVersionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryPromotionService")
                .because("Memory version allocation must remain independently owned")
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
                .orShould().dependOnClassesThat().haveSimpleName("MemoryReviewService")
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
    void memoryReviewMustOrchestrateThroughPromotionAndVersionOwners() {
        classes()
                .that().haveSimpleName("MemoryReviewService")
                .should().dependOnClassesThat().haveSimpleName("MemoryPromotionService")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryVersionService")
                .andShould().dependOnClassesThat().haveSimpleName("MemoryStatementMatcher")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryPromotionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryReviewService")
                .because("Promotion remains the write owner and must not depend on review orchestration")
                .check(CLASSES);
        noClasses()
                .that().haveSimpleName("MemoryVersionService")
                .should().dependOnClassesThat().haveSimpleName("MemoryReviewService")
                .because("Version lifecycle remains independently owned")
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
                                "ResearchRunService",
                                "ElasticsearchIndexer"
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
}
