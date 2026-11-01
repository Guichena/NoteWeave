package com.noteweave.answer.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.EligibilityStatus;
import com.noteweave.source.eligibility.GeneratedDependencyAssessment;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GenerationContractGuardTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private JdbcTemplate jdbc;
    private final Set<String> blockedSources = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:guard-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table answer_run_evidence_manifest(
                    id varchar(36) primary key, workspace_id varchar(36), answer_run_id varchar(36),
                    rank_no int, evidence_id varchar(200), evidence_kind varchar(64),
                    source_id varchar(36), source_snapshot_id varchar(36), passage_id varchar(100),
                    knowledge_item_id varchar(36), knowledge_version_id varchar(36),
                    title varchar(500), excerpt clob, content_hash char(64),
                    location_info varchar(1000), fresh_at timestamp, character_cost int,
                    created_at timestamp default current_timestamp)
                """);
        jdbc.execute("create table knowledge_item(id varchar(36), workspace_id varchar(36))");
        jdbc.execute("create table knowledge_version(id varchar(36), item_id varchar(36))");
    }

    @Test
    void eligibleEvidencePassesTheGuard() {
        manifest("passage:one", "PASSAGE", "source-1", "snapshot-1", null, HASH_A);

        assertThatCode(() -> guard().requireEligible("ws", "run", contract(
                List.of("passage:one"), Map.of("passage:one", HASH_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void missingManifestRowRevokesTheRun() {
        assertThatThrownBy(() -> guard().requireEligible("ws", "run", contract(
                List.of("passage:one"), Map.of("passage:one", HASH_A))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED);
                    assertThat(error.getMessage()).contains("MISSING_FROM_MANIFEST");
                });
    }

    @Test
    void changedEvidenceTextRevokesTheRun() {
        manifest("passage:one", "PASSAGE", "source-1", "snapshot-1", null, HASH_B);

        assertThatThrownBy(() -> guard().requireEligible("ws", "run", contract(
                List.of("passage:one"), Map.of("passage:one", HASH_A))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED);
                    assertThat(error.getMessage()).contains("CONTENT_CHANGED");
                });
    }

    @Test
    void sourceThatIsNoLongerEligibleRevokesTheRun() {
        manifest("passage:one", "PASSAGE", "source-1", "snapshot-1", null, HASH_A);
        blockedSources.add("source-1");

        assertThatThrownBy(() -> guard().requireEligible("ws", "run", contract(
                List.of("passage:one"), Map.of("passage:one", HASH_A))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED);
                    assertThat(error.getMessage()).contains("SOURCE_NOT_ELIGIBLE");
                });
    }

    @Test
    void knowledgeVersionThatDisappearedRevokesTheRun() {
        manifest("knowledge-version:one", "KNOWLEDGE_VERSION", null, null, "version-1", HASH_A);

        assertThatThrownBy(() -> guard().requireEligible("ws", "run", contract(
                List.of("knowledge-version:one"), Map.of("knowledge-version:one", HASH_A))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED);
                    assertThat(error.getMessage()).contains("NOT_ACTIVE");
                });
    }

    @Test
    void knowledgeVersionThatStillExistsPasses() {
        manifest("knowledge-version:one", "KNOWLEDGE_VERSION", null, null, "version-1", HASH_A);
        jdbc.update("insert into knowledge_item(id, workspace_id) values ('item-1', 'ws')");
        jdbc.update("insert into knowledge_version(id, item_id) values ('version-1', 'item-1')");

        assertThatCode(() -> guard().requireEligible("ws", "run", contract(
                List.of("knowledge-version:one"), Map.of("knowledge-version:one", HASH_A))))
                .doesNotThrowAnyException();
    }

    @Test
    void refusalOrMissingContractSkipsTheCheck() {
        assertThatCode(() -> guard().requireEligible("ws", "run", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard().requireEligible("ws", "run", new GenerationContract(
                GenerationContract.SCHEMA_VERSION, "QA", "qa-v1", "system", true,
                List.of(), List.of(), "bundle", Map.of(), List.of())))
                .doesNotThrowAnyException();
    }

    private GenerationContract contract(List<String> allowed, Map<String, String> hashes) {
        List<CitationBinding> bindings = new java.util.ArrayList<>();
        for (int index = 0; index < allowed.size(); index++) {
            bindings.add(new CitationBinding(index + 1, allowed.get(index), List.of()));
        }
        return new GenerationContract(
                GenerationContract.SCHEMA_VERSION, "QA", "qa-v1", "system", false,
                allowed, bindings, "bundle", hashes, List.of());
    }

    private void manifest(String evidenceId, String kind, String sourceId, String snapshotId,
                          String knowledgeVersionId, String contentHash) {
        jdbc.update("""
                insert into answer_run_evidence_manifest(
                    id, workspace_id, answer_run_id, rank_no, evidence_id, evidence_kind,
                    source_id, source_snapshot_id, knowledge_version_id, content_hash, excerpt, character_cost)
                values (?, 'ws', 'run', 1, ?, ?, ?, ?, ?, ?, '摘录', 2)
                """, java.util.UUID.randomUUID().toString(), evidenceId, kind,
                sourceId, snapshotId, knowledgeVersionId, contentHash);
    }

    private GenerationContractGuard guard() {
        return new GenerationContractGuard(jdbc, new StubEligibilityPort(blockedSources));
    }

    /** 资格端口替身：只按来源编号返回可读或不可读。 */
    private record StubEligibilityPort(Set<String> blocked) implements MaterialEligibilityPort {

        @Override
        public Map<String, EligibilityDecision> assess(
                String workspaceId, List<OriginRef> origins, AccessPurpose purpose) {
            Map<String, EligibilityDecision> decisions = new LinkedHashMap<>();
            for (OriginRef origin : origins) {
                boolean allowed = !blocked.contains(origin.sourceId());
                decisions.put(origin.originKey(), allowed
                        ? EligibilityDecision.ready(origin.originKey(), 1L, origin.snapshotId())
                        : new EligibilityDecision(origin.originKey(), EligibilityStatus.USER_DELETED,
                                "SOURCE_DELETED", 1L, origin.snapshotId(), true, false, false, List.of()));
            }
            return decisions;
        }

        @Override
        public GeneratedDependencyAssessment assessGeneratedDependenciesDetailed(
                String workspaceId, String generatedBy, String generatedRefId, AccessPurpose purpose) {
            return new GeneratedDependencyAssessment(true, List.of(), Map.of());
        }
    }
}
