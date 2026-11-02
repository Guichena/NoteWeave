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
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('ws','local-user','测试','ACTIVE')");
        jdbc.update("insert into conversation(id,workspace_id,title,conversation_type,status) values ('conv','ws','测试','QA','ACTIVE')");
        jdbc.update("insert into conversation_message(id,workspace_id,conversation_id,message_seq,role,content) values ('q','ws','conv',1,'USER','问题')");
        jdbc.update("insert into answer_run(id,workspace_id,conversation_id,mode,status,query_message_id,created_by,updated_by) values ('run','ws','conv','QA','GENERATING','q','test','test')");
        jdbc.update("insert into file_object(id,workspace_id,object_key,sha256,file_size) values ('file','ws','key',?,1)",HASH_A);
        jdbc.update("insert into source(id,workspace_id,file_object_id,title,source_type,status,parse_status,index_status) values ('source-1','ws','file','来源','TEXT','READY','PARSED','INDEXED')");
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values ('snapshot-1','source-1','file',1,'key',?,'PARSED','INDEXED')",HASH_A);
        jdbc.update("insert into source_chunk(id,workspace_id,source_id,source_snapshot_id,chunk_no,content,token_estimate) values ('passage-1','ws','source-1','snapshot-1',0,'正文',1)");

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
                    assertThat(error.getMessage()).contains("KNOWLEDGE_CITATION_REVOKED");
                });
    }

    @Test
    void knowledgeVersionThatStillExistsPasses() {
        manifest("knowledge-version:one", "KNOWLEDGE_VERSION", null, null, "version-1", HASH_A);
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status,latest_version_id,wiki_page_state) values ('item-1','ws','WIKI','页面','ACTIVE','version-1','MANUAL')");
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('version-1','item-1',1,'正文')");
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,source_chunk_id,title,quote_text) values ('c','ws','source-1','snapshot-1','passage-1','来源','正文')");
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values ('kvc','version-1','c',0)");

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
            bindings.add(new CitationBinding(index + 1, allowed.get(index), List.of("c")));
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
                    source_id, source_snapshot_id, passage_id, knowledge_item_id, knowledge_version_id, content_hash, excerpt, character_cost)
                values (?, 'ws', 'run', 1, ?, ?, ?, ?, 'passage-1', 'item-1', ?, ?, '摘录', 2)
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
