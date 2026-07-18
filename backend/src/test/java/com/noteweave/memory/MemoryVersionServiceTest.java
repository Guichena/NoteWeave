package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.WorkspaceAccessGuard;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MemoryVersionServiceTest {

    private JdbcTemplate jdbcTemplate;
    private MemoryVersionService versionService;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-version-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        createSchema();
        versionService = new MemoryVersionService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                mock(WorkspaceAccessGuard.class),
                mock(LegacyMemoryRuntimeBridge.class));
    }

    @Test
    void shouldSerializeConcurrentAppendsAndPreserveImmutableContent() throws Exception {
        seedObject("memory-concurrent", "initial statement");
        MemoryVersionResponse initial = versionService.createInitialVersion(
                new MemoryVersionService.InitialVersionCommand(
                        "workspace",
                        "memory-concurrent",
                        "initial statement",
                        List.of("CHAT_QA"),
                        hints("initial style"),
                        List.of(),
                        "candidate-initial",
                        "memory-candidate-policy-v1",
                        0.10,
                        "VALID"
                ));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return transactionTemplate.execute(status -> versionService.appendVersion(
                        "workspace",
                        "memory-concurrent",
                        request("second statement", "second style")));
            });
            var second = executor.submit(() -> {
                start.await();
                return transactionTemplate.execute(status -> versionService.appendVersion(
                        "workspace",
                        "memory-concurrent",
                        request("third statement", "third style")));
            });
            start.countDown();
            assertThat(first.get()).isNotNull();
            assertThat(second.get()).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForList("""
                select version_no from memory_version
                where memory_object_id = 'memory-concurrent'
                order by version_no
                """, Integer.class)).containsExactly(1, 2, 3);
        assertThat(jdbcTemplate.queryForList("""
                select status from memory_version
                where memory_object_id = 'memory-concurrent'
                order by version_no
                """, String.class)).containsExactly("SUPERSEDED", "SUPERSEDED", "ACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                select canonical_statement from memory_version where id = ?
                """, String.class, initial.memoryVersionId())).isEqualTo("initial statement");
        assertThat(jdbcTemplate.queryForList("""
                select canonical_statement from memory_version
                where memory_object_id = 'memory-concurrent' and version_no in (2, 3)
                """, String.class)).containsExactlyInAnyOrder("second statement", "third statement");
        assertThat(jdbcTemplate.queryForObject("""
                select current_version_no from memory_object where id = 'memory-concurrent'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                select v.version_no
                from memory_object o
                join memory_version v on v.id = o.latest_version_id
                where o.id = 'memory-concurrent'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from memory_version newer
                join memory_version older on older.id = newer.supersedes_version_id
                where newer.memory_object_id = 'memory-concurrent'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldBootstrapLegacyObjectBeforeAppendingNewVersion() {
        seedObject("memory-legacy", "legacy statement");

        MemoryVersionResponse appended = transactionTemplate.execute(status ->
                versionService.appendVersion(
                        "workspace",
                        "memory-legacy",
                        request("new statement", "new style")));

        assertThat(appended).isNotNull();
        assertThat(appended.versionNo()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                select version_no, policy_version, status, canonical_statement
                from memory_version
                where memory_object_id = 'memory-legacy'
                order by version_no
                """))
                .containsExactly(
                        java.util.Map.of(
                                "VERSION_NO", 1,
                                "POLICY_VERSION", "memory-legacy-bootstrap-v1",
                                "STATUS", "SUPERSEDED",
                                "CANONICAL_STATEMENT", "legacy statement"),
                        java.util.Map.of(
                                "VERSION_NO", 2,
                                "POLICY_VERSION", "memory-lifecycle-policy-v1",
                                "STATUS", "ACTIVE",
                                "CANONICAL_STATEMENT", "new statement")
                );
    }

    private AppendMemoryVersionRequest request(String statement, String style) {
        return new AppendMemoryVersionRequest(
                statement,
                List.of("CHAT_QA"),
                List.of(style),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private MemorySignalService.MemoryCompileHints hints(String style) {
        return new MemorySignalService.MemoryCompileHints(
                List.of(style),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table memory_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    canonical_statement clob not null,
                    task_neighborhood_json clob not null,
                    compile_policy_json clob,
                    forbidden_pattern_json clob,
                    latest_version_id varchar(36),
                    current_version_no int not null default 0,
                    status varchar(32) not null,
                    review_status varchar(32) not null default 'APPROVED',
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table memory_version(
                    id varchar(36) primary key,
                    memory_object_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    version_no int not null,
                    canonical_statement clob not null,
                    task_neighborhood_json clob not null,
                    compile_policy_json clob,
                    forbidden_pattern_json clob,
                    status varchar(32) not null,
                    supersedes_version_id varchar(36),
                    valid_from timestamp not null,
                    valid_to timestamp,
                    created_from_candidate_id varchar(36),
                    policy_version varchar(64) not null,
                    risk_score decimal(5,4) not null,
                    scope_status varchar(32) not null,
                    created_at timestamp default current_timestamp,
                    unique(memory_object_id, version_no)
                )
                """);
    }

    private void seedObject(String memoryObjectId, String statement) {
        jdbcTemplate.update("""
                insert into memory_object(
                    id, workspace_id, canonical_statement, task_neighborhood_json,
                    compile_policy_json, forbidden_pattern_json, latest_version_id,
                    current_version_no, status
                ) values (?, 'workspace', ?, '["CHAT_QA"]',
                          '{"styleConstraints":["legacy style"],"structureConstraints":[],"terminologyPolicy":[],"forbiddenPatterns":[],"interactionPolicy":[],"reviewChecklist":[]}',
                          '[]', null, 0, 'ACTIVE')
                """, memoryObjectId, statement);
    }
}
