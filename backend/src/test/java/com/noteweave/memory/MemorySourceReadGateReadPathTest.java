package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Memory 读路径的来源可读性校验。
 *
 * <p>条目通过 memory_runtime_revision -> memory_candidate -> memory_signal 绑定来源。
 * source 为 READY 时条目可见，source 为 DELETED 时只在读路径排除，数据库记录保留。
 */
class MemorySourceReadGateReadPathTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private WorkspaceAccessGuard workspaceAccessGuard;
    private CurrentUserProvider currentUserProvider;
    private MemoryCompiledPackCache compiledPackCache;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-source-readability-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        workspaceAccessGuard = mock(WorkspaceAccessGuard.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        compiledPackCache = mock(MemoryCompiledPackCache.class);
        when(currentUserProvider.requireUserId()).thenReturn("user-current");
        when(compiledPackCache.get(any())).thenReturn(Optional.empty());
        createSchema();
    }

    @Test
    void listItemsShouldExcludeMemoryWhoseSourceIsDeletedAndKeepReadySource() {
        seedLinkedMemory("memory-ready", "source-ready", "READY");
        seedLinkedMemory("memory-deleted", "source-deleted", "DELETED");

        MemoryItemQueryService service = queryService();

        assertThat(service.listItems("workspace"))
                .extracting(MemoryItemResponse::memoryItemId)
                .containsExactly("memory-ready");

        jdbcTemplate.update("update source set status = 'READY' where id = 'source-deleted'");
        assertThat(service.listItems("workspace"))
                .extracting(MemoryItemResponse::memoryItemId)
                .containsExactlyInAnyOrder("memory-ready", "memory-deleted");

        jdbcTemplate.update("update source set status = 'DELETED' where id = 'source-ready'");
        assertThat(service.listItems("workspace"))
                .extracting(MemoryItemResponse::memoryItemId)
                .containsExactly("memory-deleted");

        // 读路径过滤不物理删除 memory_item。
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from memory_item where id = 'memory-ready'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void compileShouldExcludeMemoryWhoseSourceIsDeletedAndKeepReadySource() {
        seedLinkedMemory("memory-ready", "source-ready", "READY");
        seedLinkedMemory("memory-deleted", "source-deleted", "DELETED");

        MemoryCompilerService compiler = compilerService();

        MemoryControlPackResponse pack = compiler.compileChatControlPack("workspace", "QA");
        assertThat(pack.memoryReferences())
                .extracting(MemoryReferenceResponse::memoryObjectId)
                .containsExactly("memory-ready");
        assertThat(pack.styleConstraints()).containsExactly("source constraint");

        jdbcTemplate.update("update source set status = 'READY' where id = 'source-deleted'");
        assertThat(compiler.compileChatControlPack("workspace", "QA").memoryReferences())
                .extracting(MemoryReferenceResponse::memoryObjectId)
                .containsExactlyInAnyOrder("memory-ready", "memory-deleted");
    }

    @Test
    void runtimeRecallShouldExcludeMemoryWhoseSourceIsDeleted() {
        seedLinkedMemory("memory-ready", "source-ready", "READY");
        seedLinkedMemory("memory-deleted", "source-deleted", "DELETED");

        CanonicalMemoryRuntime runtime = new CanonicalMemoryRuntime(
                jdbcTemplate, objectMapper, currentUserProvider, new MemoryCompilerPolicy());

        MemoryRuntimePack pack = runtime.recall(new MemoryRuntimeQuery("workspace"));

        assertThat(pack.memoryReferences())
                .extracting(MemoryReferenceResponse::memoryObjectId)
                .containsExactly("memory-ready");
    }

    private MemoryItemQueryService queryService() {
        return new MemoryItemQueryService(
                jdbcTemplate,
                objectMapper,
                workspaceAccessGuard,
                currentUserProvider,
                new MemoryCandidatePolicy());
    }

    private MemoryCompilerService compilerService() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        return new MemoryCompilerService(
                jdbcTemplate,
                objectMapper,
                beanFactory.getBeanProvider(CapabilityCatalogPort.class),
                currentUserProvider,
                new MemoryCompilerPolicy(),
                compiledPackCache,
                new SimpleMeterRegistry(),
                new MemoryTextEmbeddingStore(
                        jdbcTemplate,
                        (com.noteweave.retrieval.provider.EmbeddingClient) null,
                        objectMapper),
                beanFactory.getBeanProvider(com.noteweave.retrieval.provider.EmbeddingClient.class),
                false);
    }

    private void seedLinkedMemory(String itemId, String sourceId, String sourceStatus) {
        String candidateId = "candidate-" + itemId;
        String signalId = "signal-" + itemId;
        String revisionId = "revision-" + itemId;
        String payload = write(Map.of(
                "task_neighborhoods", List.of("CHAT_QA"),
                "compile_hints", new MemorySignalService.MemoryCompileHints(
                        List.of("source constraint"), List.of(), List.of(), List.of(), List.of(), List.of()),
                "utility_score", 0.90));
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                insert into source(id, workspace_id, status)
                values (?, 'workspace', ?)
                """, sourceId, sourceStatus);
        jdbcTemplate.update("""
                insert into memory_signal(id, workspace_id, source_type, source_id, confidence_score)
                values (?, 'workspace', 'SOURCE', ?, 0.90)
                """, signalId, sourceId);
        jdbcTemplate.update("""
                insert into memory_candidate(
                    id, workspace_id, evidence_gate_status, marginal_utility_score, risk_score,
                    scope_status, conflict_status, policy_version, created_from_signal_ids_json
                ) values (?, 'workspace', 'PASS', 0.90, 0.10, 'VALID', 'NO_CONFLICT',
                          'memory-candidate-policy-v2', ?)
                """, candidateId, "[\"" + signalId + "\"]");
        jdbcTemplate.update("""
                insert into memory_item(
                    id, workspace_id, owner_user_id, memory_scope, status, review_status,
                    current_revision_id, utility_score, application_count, semantic_slot_key,
                    evidence_alpha, evidence_beta, reinforcement_count, created_at, updated_at
                ) values (?, 'workspace', 'user-current', 'WORKSPACE', 'ACTIVE', 'APPROVED',
                          ?, 0.90, 0, null, 1.50, 1.50, 0, ?, ?)
                """, itemId, revisionId, now, now);
        jdbcTemplate.update("""
                insert into memory_runtime_revision(
                    id, memory_item_id, workspace_id, version_no, status, confidence,
                    normalized_value_json, display_text, provenance_type, provenance_ref,
                    created_at, valid_from, valid_until
                ) values (?, ?, 'workspace', 1, 'ACTIVE', 0.90, ?, 'source constraint',
                          'MEMORY_CANDIDATE', ?, ?, ?, null)
                """, revisionId, itemId, payload, candidateId, now, now);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table memory_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    owner_user_id varchar(36),
                    memory_scope varchar(32) not null,
                    status varchar(32) not null,
                    review_status varchar(32) not null,
                    current_revision_id varchar(36),
                    utility_score decimal(5,4) not null,
                    application_count int not null default 0,
                    last_confirmed_at timestamp,
                    semantic_slot_key varchar(256),
                    evidence_alpha decimal(12,4),
                    evidence_beta decimal(12,4),
                    reinforcement_count int not null default 0,
                    last_reinforced_at timestamp,
                    text_embedding clob,
                    text_embedding_hash varchar(64),
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table memory_runtime_revision(
                    id varchar(36) primary key,
                    memory_item_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    version_no int not null,
                    status varchar(32) not null,
                    confidence decimal(5,4),
                    normalized_value_json clob,
                    display_text clob not null,
                    provenance_type varchar(64) not null,
                    provenance_ref varchar(128),
                    created_at timestamp not null,
                    valid_from timestamp not null,
                    valid_until timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table memory_candidate(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    evidence_gate_status varchar(32),
                    marginal_utility_score decimal(5,4),
                    risk_score decimal(5,4),
                    scope_status varchar(32),
                    conflict_status varchar(32),
                    policy_version varchar(64),
                    created_from_signal_ids_json clob,
                    conflict_type varchar(32),
                    conflicting_item_id varchar(36),
                    conflicting_text clob,
                    slot_key varchar(256)
                )
                """);
        jdbcTemplate.execute("""
                create table memory_signal(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_type varchar(64) not null,
                    source_id varchar(128),
                    confidence_score decimal(5,4)
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null
                )
                """);
    }
}
