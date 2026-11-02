package com.noteweave.answer.generation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F1资格反例使用完整真实迁移，不能用只有版本ID的知识测试表替代状态政策。 */
class F1GenerationEligibilityRegressionTest {
    JdbcTemplate jdbc;
    GenerationContractGuard guard;
    static final String HASH="a".repeat(64);
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:f1-elig-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into conversation(id,workspace_id,title,conversation_type,status) values ('c','w','测试','QA','ACTIVE')");
        jdbc.update("insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,content) values ('q','c','w',1,'USER','问题')");
        jdbc.update("insert into answer_run(id,workspace_id,conversation_id,mode,status,query_message_id,created_by,updated_by) values ('r','w','c','QA','GENERATING','q','test','test')");
        jdbc.update("insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,content) values ('answer','c','w',3,'ASSISTANT','本轮回答')");
        jdbc.update("update answer_run set answer_message_id='answer' where id='r'");
        jdbc.update("insert into citation(id,workspace_id,title,quote_text,research_run_id,research_evidence_key,owner_message_id) values ('c','w','研究来源','原文','research','evidence:0123456789abcdef','answer')");
        jdbc.update("insert into message_citation(id,message_id,citation_id,sort_order) values ('mc','answer','c',0)");
        MaterialEligibilityPort eligibility = mock(MaterialEligibilityPort.class);
        guard = new GenerationContractGuard(jdbc, eligibility,
                new com.noteweave.knowledge.KnowledgeCitationReadGate(jdbc, eligibility));
    }
    @Test void deletedWikiVersionCannotRemainGenerationEvidence() {
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status,latest_version_id) values ('item','w','WIKI','页面','DELETED','version')");
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('version','item',1,'旧正文')");
        manifest("knowledge-version:version","KNOWLEDGE_VERSION",null,"item","version");
        assertThatThrownBy(()->guard.requireEligible("w","r",contract("knowledge-version:version",HASH)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void nonexistentResearchRunCannotPassUsingOnlyFrozenHash() {
        String id="research-evidence:missing:evidence:0123456789abcdef";
        manifest(id,"RESEARCH_EVIDENCE","evidence:0123456789abcdef",null,null);
        assertThatThrownBy(()->guard.requireEligible("w","r",contract(id,HASH))).isInstanceOf(BusinessException.class);
    }
    @Test void unknownEvidenceKindFailsClosed() {
        manifest("unknown","FUTURE_KIND",null,null,null);
        assertThatThrownBy(()->guard.requireEligible("w","r",contract("unknown",HASH))).isInstanceOf(BusinessException.class);
    }
    @Test void missingHashIsInvalidInsteadOfSkippingContentCheck() {
        manifest("unknown","FUTURE_KIND",null,null,null);
        assertThatThrownBy(()->guard.requireEligible("w","r",contract("unknown",null))).isInstanceOf(BusinessException.class);
    }
    @Test void completedResearchPassesThenDeletedEvidenceIsRevoked() {
        String id=validResearch();
        org.assertj.core.api.Assertions.assertThatCode(()->guard.requireEligible("w","r",contract(id,HASH))).doesNotThrowAnyException();
        jdbc.update("delete from source_evidence where research_run_id='research'");
        assertThatThrownBy(()->guard.requireEligible("w","r",contract(id,HASH)))
                .isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED));
    }
    @Test void deletedLinkedResearchMessageRevokesFrozenEvidence() {
        String id=validResearch();
        jdbc.update("update conversation_message set context_status='DELETED' where id='research-a'");
        assertThatThrownBy(()->guard.requireEligible("w","r",contract(id,HASH)))
                .isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED));
    }
    @Test void changedResearchStatusRevokesFrozenEvidence() {
        String id=validResearch();
        jdbc.update("update research_run set status='CANCELLED' where id='research'");
        assertThatThrownBy(()->guard.requireEligible("w","r",contract(id,HASH))).isInstanceOf(BusinessException.class);
    }
    @Test void frozenResearchRecheckedAfterConcurrentLifecycleChangeBeforeModelCall() throws Exception {
        String id=validResearch();
        jdbc.update("insert into run_input_snapshot(id,workspace_id,execution_kind,research_run_id,conversation_id,query_message_id,assistant_message_id,requested_turn_mode,conversation_cutoff_seq,retrieval_config_json,snapshot_json,compiler_version,token_budget_json) values ('input','w','RESEARCH','research','c','q','research-a','DEEP_RESEARCH',1,'{}','{}','legacy-test','{}')");
        jdbc.update("update research_run set context_snapshot_id='input' where id='research'");
        String[] mutations={
                "update run_input_snapshot set replay_availability='METADATA_ONLY' where id='input'",
                "update conversation_message set context_status='DELETED' where id='research-a'",
                "delete from source_evidence where research_run_id='research'",
                "update research_run set status='CANCELLED' where id='research'"
        };
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for(String mutation:mutations) {
                jdbc.update("update run_input_snapshot set replay_availability='FULL' where id='input'");
                jdbc.update("update conversation_message set context_status='CURRENT' where id='research-a'");
                jdbc.update("update research_run set status='COMPLETED' where id='research'");
                if(jdbc.queryForObject("select count(*) from source_evidence where id='se'",Integer.class)==0)
                    jdbc.update("insert into source_evidence(id,research_run_id,evidence_key,quote_text,snapshot_status) values ('se','research','evidence:0123456789abcdef','网页原文','EXTERNAL_ARCHIVED')");
                guard.requireEligible("w","r",contract(id,HASH));
                var frozen=new java.util.concurrent.CountDownLatch(1);
                var changed=new java.util.concurrent.CountDownLatch(1);
                var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
                var generation=pool.submit(()->{
                    frozen.countDown();
                    try { if(!changed.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("资格屏障超时"); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    guard.requireEligible("w","r",contract(id,HASH));
                    modelCalls.incrementAndGet();
                });
                org.assertj.core.api.Assertions.assertThat(frozen.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                jdbc.update(mutation);
                changed.countDown();
                assertThatThrownBy(()->generation.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
                org.assertj.core.api.Assertions.assertThat(modelCalls.get()).isZero();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void researchCitationWithDifferentKeyMustBeRevoked() {
        String id = validResearch();
        org.assertj.core.api.Assertions.assertThatCode(() -> guard.requireEligible("w", "r", contract(id, HASH)))
                .doesNotThrowAnyException();
        jdbc.update("update citation set research_evidence_key='evidence:fedcba9876543210' where id='c'");
        assertThatThrownBy(() -> guard.requireEligible("w", "r", contract(id, HASH)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(GenerationContractGuard.CODE_REVOKED);
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).contains("CITATION_BINDING_IDENTITY_MISMATCH");
                });
    }

    String validResearch() {
        jdbc.update("insert into task(id,workspace_id,task_type,task_status) values ('task','w','RESEARCH','COMPLETED')");
        jdbc.update("insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,content) values ('research-a','c','w',2,'ASSISTANT','研究报告')");
        jdbc.update("insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,status,conversation_id,query_message_id,answer_message_id) values ('research','w','task','问题','balanced','[]','COMPLETED','c','q','research-a')");
        jdbc.update("insert into source_evidence(id,research_run_id,evidence_key,quote_text,snapshot_status) values ('se','research','evidence:0123456789abcdef','网页原文','EXTERNAL_ARCHIVED')");
        var eligibility=mock(MaterialEligibilityPort.class);
        org.mockito.Mockito.when(eligibility.assessGeneratedDependenciesDetailed("w","research_agent","research",com.noteweave.source.eligibility.AccessPurpose.CURRENT_EVIDENCE))
                .thenReturn(new com.noteweave.source.eligibility.GeneratedDependencyAssessment(true,List.of(),Map.of()));
        guard = new GenerationContractGuard(jdbc, eligibility,
                new com.noteweave.knowledge.KnowledgeCitationReadGate(jdbc, eligibility));
        String id="research-evidence:research:evidence:0123456789abcdef";
        manifest(id,"RESEARCH_EVIDENCE","evidence:0123456789abcdef",null,null);
        return id;
    }
    GenerationContract contract(String id,String hash) {
        return new GenerationContract(GenerationContract.SCHEMA_VERSION,"QA","v1","system",false,
                List.of(id),List.of(new CitationBinding(1,id,List.of("c"))),"bundle",hash==null?Map.of():Map.of(id,hash),List.of());
    }
    void manifest(String id,String kind,String passage,String item,String version) {
        jdbc.update("insert into answer_run_evidence_manifest(id,workspace_id,answer_run_id,rank_no,evidence_id,evidence_kind,passage_id,knowledge_item_id,knowledge_version_id,excerpt,content_hash,character_cost) values ('m','w','r',1,?,?,?,?,?,'摘录',?,2)",id,kind,passage,item,version,HASH);
    }
}
