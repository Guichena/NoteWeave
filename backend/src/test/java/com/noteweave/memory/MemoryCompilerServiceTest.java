package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MemoryCompilerServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private CurrentUserProvider currentUserProvider;
    private MemoryCompiledPackCache compiledPackCache;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-compiler-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        currentUserProvider = mock(CurrentUserProvider.class);
        compiledPackCache = mock(MemoryCompiledPackCache.class);
        when(currentUserProvider.requireUserId()).thenReturn("user-current");
        when(compiledPackCache.get(any())).thenReturn(Optional.empty());
        createSchema();
    }

    @Test
    void shouldRankByUserScopeSpecificityUtilityAndExcludeOtherUsers() {
        seed("user-exact", "USER", "user-current", 0.60,
                List.of("CHAT_QA"), "user exact", Instant.now().minusSeconds(120));
        seed("workspace-exact", "WORKSPACE", "owner", 0.95,
                List.of("CHAT_QA"), "workspace exact", Instant.now().minusSeconds(60));
        seed("workspace-broad", "WORKSPACE", "owner", 0.99,
                List.of("CHAT"), "workspace broad", Instant.now());
        seed("common", "WORKSPACE", "owner", 1.00,
                List.of("COMMON"), "common", Instant.now());
        seed("other-user", "USER", "user-other", 1.00,
                List.of("CHAT_QA"), "other user", Instant.now());

        MemoryControlPackResponse pack = service(capabilityCatalog())
                .compileChatControlPack("workspace", "QA");

        assertThat(pack.styleConstraints()).containsExactly(
                "common", "workspace broad", "workspace exact", "user exact");
        assertThat(pack.memoryReferences())
                .extracting(MemoryReferenceResponse::memoryObjectId)
                .containsExactly("common", "workspace-broad", "workspace-exact", "user-exact");
        assertThat(pack.memoryReferences()).allSatisfy(reference -> {
            assertThat(reference.memoryClass()).isEqualTo(MemoryCompilerPolicy.ALWAYS_ON);
            assertThat(reference.selectionReason()).isEqualTo(MemoryCompilerPolicy.REASON_ALWAYS_ON);
            assertThat(reference.relevance()).isNull();
            assertThat(reference.displayText()).isNotBlank();
        });
        assertThat(pack.compilationTrace().policyVersion())
                .isEqualTo("memory-compiler-policy-v2");
        assertThat(pack.compilationTrace().candidateCount()).isEqualTo(4);
        assertThat(pack.compilationTrace().truncated()).isFalse();
        assertThat(pack.compilationTrace().decisions()).hasSize(4)
                .allMatch(MemoryCompilationTraceResponse.Decision::selected);
    }

    @Test
    void shouldTruncateWholeMemoryObjectsWithinTokenBudget() {
        for (int index = 0; index < 6; index++) {
            seed(
                    "memory-" + index,
                    "WORKSPACE",
                    "owner",
                    0.99 - index * 0.05,
                    List.of("CHAT_QA"),
                    ("约束" + index).repeat(70),
                    Instant.now().minusSeconds(index)
            );
        }

        MemoryControlPackResponse pack = service(capabilityCatalog())
                .compileChatControlPack("workspace", "QA");

        assertThat(pack.compilationTrace().maximumTokens()).isEqualTo(320);
        assertThat(pack.compilationTrace().selectedTokens()).isLessThanOrEqualTo(320);
        assertThat(pack.compilationTrace().candidateCount()).isEqualTo(6);
        assertThat(pack.compilationTrace().selectedCount()).isLessThan(6);
        assertThat(pack.compilationTrace().droppedCount()).isGreaterThan(0);
        assertThat(pack.compilationTrace().truncated()).isTrue();
        assertThat(pack.memoryReferences().get(0).memoryObjectId()).isEqualTo("memory-0");
        assertThat(pack.compilationTrace().decisions())
                .filteredOn(decision -> !decision.selected())
                .isNotEmpty()
                .allMatch(decision -> MemoryCompilerPolicy.REASON_BUDGET.equals(decision.reason()));
    }

    @Test
    void shouldUseCapabilityPortAndDegradeExplicitlyWhenAdapterIsMissing() {
        MemoryControlPackResponse resolved = service(capabilityCatalog())
                .compileArtifactControlPack("workspace", "report_draft");
        assertThat(resolved.targetKey()).isEqualTo("report_draft");
        assertThat(resolved.compilationTrace().degraded()).isFalse();

        MemoryControlPackResponse degraded = service(null)
                .compileArtifactControlPack("workspace", "report-draft");
        assertThat(degraded.targetKey()).isEqualTo("report_draft");
        assertThat(degraded.compilationTrace().degraded()).isTrue();
        assertThat(degraded.compilationTrace().degradationReasons())
                .containsExactly("capability_catalog_unavailable");
    }

    @Test
    void shouldReusePackForSameFingerprintAndMissAfterUtilityChange() {
        seed("memory-cache", "WORKSPACE", "owner", 0.80,
                List.of("CHAT_QA"), "cached style", Instant.now());
        MemoryCompilerService service = service(capabilityCatalog());

        MemoryControlPackResponse first = service.compileChatControlPack("workspace", "QA");
        ArgumentCaptor<MemoryCompiledPackCache.CacheKey> firstKey =
                ArgumentCaptor.forClass(MemoryCompiledPackCache.CacheKey.class);
        verify(compiledPackCache).put(firstKey.capture(), eq(first));
        when(compiledPackCache.get(firstKey.getValue())).thenReturn(Optional.of(first));

        MemoryControlPackResponse second = service.compileChatControlPack("workspace", "QA");
        assertThat(second).isSameAs(first);

        jdbcTemplate.update("""
                update memory_item
                set utility_score = 0.25,
                    updated_at = dateadd('SECOND', 1, updated_at)
                where id = 'memory-cache'
                """);
        MemoryControlPackResponse changed = service.compileChatControlPack("workspace", "QA");
        assertThat(changed.memoryReferences()).singleElement()
                .satisfies(reference -> assertThat(reference.utilityScore()).isEqualTo(0.25));

        ArgumentCaptor<MemoryCompiledPackCache.CacheKey> requestedKeys =
                ArgumentCaptor.forClass(MemoryCompiledPackCache.CacheKey.class);
        verify(compiledPackCache, atLeast(3)).get(requestedKeys.capture());
        assertThat(requestedKeys.getAllValues().get(0).stateFingerprint())
                .isEqualTo(requestedKeys.getAllValues().get(1).stateFingerprint());
        assertThat(requestedKeys.getAllValues().get(2).stateFingerprint())
                .isNotEqualTo(requestedKeys.getAllValues().get(0).stateFingerprint());
    }

    @Test
    void shouldLeaveOnDemandMemoryOutUntilTheQuerySharesTerms() {
        seed("style", "WORKSPACE", "owner", 0.80,
                List.of("CHAT_QA"), "回答保持简洁", Instant.now());
        seedTerminology("page-number", "引用时保留页码");
        MemoryCompilerService compiler = service(capabilityCatalog());

        MemoryControlPackResponse missed = compiler.compileChatControlPack("workspace", "QA", "缓存怎么失效");
        assertThat(missed.terminologyPolicy()).isEmpty();
        assertThat(missed.styleConstraints()).containsExactly("回答保持简洁");
        assertThat(missed.compilationTrace().truncated()).isFalse();
        assertThat(missed.compilationTrace().degradationReasons())
                .containsExactly("MEMORY_RELEVANCE_VECTOR_UNAVAILABLE");
        assertThat(missed.compilationTrace().decisions())
                .anySatisfy(decision -> {
                    assertThat(decision.memoryObjectId()).isEqualTo("page-number");
                    assertThat(decision.selected()).isFalse();
                    assertThat(decision.reason()).isEqualTo(MemoryCompilerPolicy.REASON_IRRELEVANT);
                });

        MemoryControlPackResponse blank = compiler.compileChatControlPack("workspace", "QA", "  ");
        assertThat(blank.terminologyPolicy()).isEmpty();
        assertThat(blank.compilationTrace().degradationReasons())
                .containsExactly("MEMORY_RELEVANCE_QUERY_ABSENT");

        MemoryControlPackResponse hit = compiler.compileChatControlPack("workspace", "QA", "论文页码在哪里");
        assertThat(hit.terminologyPolicy()).containsExactly("引用时保留页码");
        assertThat(hit.memoryReferences()).anySatisfy(reference -> {
            assertThat(reference.memoryObjectId()).isEqualTo("page-number");
            assertThat(reference.memoryClass()).isEqualTo(MemoryCompilerPolicy.ON_DEMAND);
            assertThat(reference.relevance()).isNotNull().isGreaterThan(0);
            assertThat(reference.selectionReason()).isEqualTo(MemoryCompilerPolicy.REASON_ON_DEMAND);
            assertThat(reference.displayText()).isEqualTo("引用时保留页码");
        });
        assertThat(hit.memoryReferences()).anySatisfy(reference -> {
            assertThat(reference.memoryObjectId()).isEqualTo("style");
            assertThat(reference.memoryClass()).isEqualTo(MemoryCompilerPolicy.ALWAYS_ON);
            assertThat(reference.relevance()).isNull();
        });
    }

    @Test
    void shouldAddNormalizedQueryToCacheKeyOnlyWhenOnDemandMemoryExists() {
        seed("style", "WORKSPACE", "owner", 0.80,
                List.of("CHAT_QA"), "回答保持简洁", Instant.now());
        MemoryCompilerService compiler = service(capabilityCatalog());
        compiler.compileChatControlPack("workspace", "QA", "论文页码");
        compiler.compileChatControlPack("workspace", "QA", "  论文   页码 ");

        ArgumentCaptor<MemoryCompiledPackCache.CacheKey> alwaysOnKeys =
                ArgumentCaptor.forClass(MemoryCompiledPackCache.CacheKey.class);
        verify(compiledPackCache, atLeast(2)).get(alwaysOnKeys.capture());
        assertThat(alwaysOnKeys.getAllValues().get(0).requestFingerprint())
                .isEqualTo(alwaysOnKeys.getAllValues().get(1).requestFingerprint());

        seedTerminology("page-number", "引用时保留页码");
        compiler.compileChatControlPack("workspace", "QA", "论文页码");
        compiler.compileChatControlPack("workspace", "QA", "缓存失效");
        ArgumentCaptor<MemoryCompiledPackCache.CacheKey> demandKeys =
                ArgumentCaptor.forClass(MemoryCompiledPackCache.CacheKey.class);
        verify(compiledPackCache, atLeast(4)).get(demandKeys.capture());
        assertThat(demandKeys.getAllValues().get(2).requestFingerprint())
                .isNotEqualTo(demandKeys.getAllValues().get(3).requestFingerprint());

        MemoryCompilerService disabled = service(capabilityCatalog(), false);
        disabled.compileChatControlPack("workspace", "QA", "论文页码");
        disabled.compileChatControlPack("workspace", "QA", "缓存失效");
        ArgumentCaptor<MemoryCompiledPackCache.CacheKey> disabledKeys =
                ArgumentCaptor.forClass(MemoryCompiledPackCache.CacheKey.class);
        verify(compiledPackCache, atLeast(2)).get(disabledKeys.capture());
        List<MemoryCompiledPackCache.CacheKey> latest = disabledKeys.getAllValues();
        assertThat(latest.get(latest.size() - 2).requestFingerprint())
                .isEqualTo(latest.get(latest.size() - 1).requestFingerprint());
        assertThat(disabled.compileChatControlPack("workspace", "QA", "缓存失效").terminologyPolicy())
                .containsExactly("引用时保留页码");
    }

    private MemoryCompilerService service(CapabilityCatalogPort catalog) {
        return service(catalog, true);
    }

    private MemoryCompilerService service(CapabilityCatalogPort catalog, boolean queryInjectionEnabled) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (catalog != null) {
            beanFactory.addBean("capabilityCatalog", catalog);
        }
        return new MemoryCompilerService(
                jdbcTemplate,
                objectMapper,
                beanFactory.getBeanProvider(CapabilityCatalogPort.class),
                currentUserProvider,
                new MemoryCompilerPolicy(),
                compiledPackCache,
                new SimpleMeterRegistry(),
                new MemoryTextEmbeddingStore(jdbcTemplate,
                        (com.noteweave.retrieval.provider.EmbeddingClient) null, objectMapper),
                beanFactory.getBeanProvider(com.noteweave.retrieval.provider.EmbeddingClient.class),
                queryInjectionEnabled
        );
    }

    private CapabilityCatalogPort capabilityCatalog() {
        return key -> new CapabilityCatalogPort.CapabilityDescriptor(
                "report_draft", "REPORT");
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table memory_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    owner_user_id varchar(36) not null,
                    memory_scope varchar(32) not null,
                    current_revision_id varchar(36),
                    utility_score decimal(5,4) not null,
                    semantic_slot_key varchar(128),
                    evidence_alpha decimal(12,4),
                    evidence_beta decimal(12,4),
                    last_reinforced_at timestamp,
                    text_embedding longtext,
                    text_embedding_hash varchar(64),
                    status varchar(32) not null,
                    review_status varchar(32) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table memory_runtime_revision(
                    id varchar(36) primary key,
                    memory_item_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    confidence decimal(5,4) not null,
                    normalized_value_json clob,
                    display_text clob not null,
                    status varchar(32) not null,
                    valid_from timestamp not null,
                    valid_until timestamp
                )
                """);
    }

    private void seed(
            String id,
            String scope,
            String owner,
            double utility,
            List<String> neighborhoods,
            String style,
            Instant updatedAt
    ) {
        seed(id, scope, owner, utility, neighborhoods, style, updatedAt, null, false);
    }

    private void seedTerminology(String id, String text) {
        seed(id, "WORKSPACE", "owner", 0.90, List.of("CHAT_QA"), text, Instant.now(),
                "terminology.term", true);
    }

    private void seed(
            String id,
            String scope,
            String owner,
            double utility,
            List<String> neighborhoods,
            String style,
            Instant updatedAt,
            String slotKey,
            boolean terminology
    ) {
        String versionId = "version-" + id;
        MemorySignalService.MemoryCompileHints compileHints = terminology
                ? new MemorySignalService.MemoryCompileHints(
                        List.of(), List.of(), List.of(style), List.of(), List.of(), List.of())
                : new MemorySignalService.MemoryCompileHints(
                        List.of(style), List.of(), List.of(), List.of(), List.of(), List.of());
        String payloadJson = write(java.util.Map.of(
                "task_neighborhoods", neighborhoods,
                "compile_hints", compileHints,
                "utility_score", utility));
        jdbcTemplate.update("""
                insert into memory_item(
                    id, workspace_id, owner_user_id, memory_scope, current_revision_id,
                    utility_score, semantic_slot_key,
                    status, review_status, created_at, updated_at
                ) values (?, 'workspace', ?, ?, ?, ?, ?, 'ACTIVE', 'APPROVED', ?, ?)
                """, id, owner, scope, versionId, utility, slotKey,
                Timestamp.from(updatedAt.minusSeconds(60)), Timestamp.from(updatedAt));
        jdbcTemplate.update("""
                insert into memory_runtime_revision(
                    id, memory_item_id, workspace_id, confidence,
                    normalized_value_json, display_text, status, valid_from, valid_until
                ) values (?, ?, 'workspace', ?, ?, ?, 'ACTIVE', ?, null)
                """, versionId, id, utility, payloadJson, style,
                Timestamp.from(updatedAt.minusSeconds(120)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
