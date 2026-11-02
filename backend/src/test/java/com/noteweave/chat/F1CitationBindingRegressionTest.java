package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.conversation.ConversationMessageDeletionService;
import com.noteweave.conversation.ConversationService;
import com.noteweave.conversation.RunReplayRedactionService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 真正父表/FK和两个数据库连接验证绑定复制与消息删除的仲裁。 */
class F1CitationBindingRegressionTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    CitationBindingService bindings;
    ConversationMessageDeletionService deletion;
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:f1-binding-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into conversation(id,workspace_id,title,conversation_type,status) values ('conv','w','测试','QA','ACTIVE')");
        jdbc.update("insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,content) values ('q','conv','w',1,'USER','问题'),('a','conv','w',2,'ASSISTANT','答案'),('target','conv','w',3,'ASSISTANT','目标')");
        jdbc.update("insert into answer_run(id,workspace_id,conversation_id,mode,status,query_message_id,answer_message_id,created_by,updated_by) values ('run','w','conv','QA','COMPLETED','q','a','test','test')");
        jdbc.update("insert into file_object(id,workspace_id,object_key,sha256,file_size) values ('file','w','key',?,1)","a".repeat(64));
        jdbc.update("insert into source(id,workspace_id,file_object_id,title,source_type,status,parse_status,index_status) values ('s','w','file','来源','TEXT','READY','PARSED','INDEXED')");
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values ('ss','s','file',1,'key',?,'PARSED','INDEXED')","a".repeat(64));
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,title,quote_text,owner_message_id) values ('cite','w','s','ss','来源','引用正文','a')");
        jdbc.update("insert into message_citation(id,message_id,citation_id,sort_order) values ('mc','a','cite',0)");
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status,latest_version_id) values ('note','w','NOTE','笔记','ACTIVE','v')");
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('v','note',1,'笔记正文')");
        bindings=new CitationBindingService(jdbc);
        deletion=new ConversationMessageDeletionService(jdbc,mock(ConversationService.class),new RunReplayRedactionService(jdbc,new ObjectMapper()));
    }
    @Test void copyToKnowledgeVersionKeepsOriginAndSurvivesOwnerDeletion() {
        var result=tx.execute(s->bindings.bindToKnowledgeVersion("w","v",List.of("cite","cite")));
        assertThat(result.citationIds()).hasSize(1).doesNotContain("cite");
        assertThat(result.copiedIds()).containsEntry("cite",result.citationIds().get(0));
        tx.executeWithoutResult(s->deletion.delete("w","conv","a"));
        assertThat(jdbc.queryForObject("select quote_text from citation where id=?",String.class,result.citationIds().get(0))).isEqualTo("引用正文");
        assertThat(jdbc.queryForObject("select source_snapshot_id from citation where id=?",String.class,result.citationIds().get(0))).isEqualTo("ss");
        assertThat(jdbc.queryForObject("select owner_message_id from citation where id=?",String.class,result.citationIds().get(0))).isNull();
    }
    @Test void unfinishedOwnerCannotBeCopiedEvenWhenMessageIsCurrent() {
        jdbc.update("update answer_run set status='GENERATING' where id='run'");
        assertThatThrownBy(()->tx.execute(s->bindings.bindToKnowledgeVersion("w","v",List.of("cite"))))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_version_citation",Integer.class)).isZero();
    }
    @Test void copyingToAnotherMessageAssignsTargetOwner() {
        var result=tx.execute(s->bindings.bindToMessage("w","target",List.of("cite"),3));
        assertThat(jdbc.queryForObject("select owner_message_id from citation where id=?",String.class,result.citationIds().get(0))).isEqualTo("target");
        assertThat(jdbc.queryForObject("select sort_order from message_citation where message_id='target'",Integer.class)).isEqualTo(3);
    }
    @Test void saveBeforeDeleteSerializesAndPreservesTheCopiedCitation() throws Exception {
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var deleteStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var saving=pool.submit(()->tx.execute(s->{
                var result=bindings.bindToKnowledgeVersion("w","v",List.of("cite"));
                locked.countDown();
                await(release);
                return result;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var deleting=pool.submit(()->{
                deleteStarted.countDown();
                return tx.execute(s->deletion.delete("w","conv","a"));
            });
            assertThat(deleteStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->deleting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var result=saving.get(5,TimeUnit.SECONDS);
            deleting.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select quote_text from citation where id=?",String.class,result.citationIds().get(0))).isEqualTo("引用正文");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void deleteBeforeSaveRejectsLateBindingWithoutAnOrphan() {
        tx.executeWithoutResult(s->deletion.delete("w","conv","a"));
        assertThatThrownBy(()->tx.execute(s->bindings.bindToKnowledgeVersion("w","v",List.of("cite"))))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_version_citation",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from citation",Integer.class)).isEqualTo(1);
    }
    @Test void complianceRedactsDerivedWikiAndRunningAnswerCopies() {
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values ('kvc','v','cite',0)");
        jdbc.update("update knowledge_item set item_type='WIKI' where id='note'");
        jdbc.update("insert into answer_run_evidence_manifest(id,workspace_id,answer_run_id,rank_no,evidence_id,evidence_kind,knowledge_item_id,knowledge_version_id,excerpt,content_hash,character_cost) values ('manifest','w','run',1,'knowledge-version:v','KNOWLEDGE_VERSION','note','v','敏感摘录',?,4)","b".repeat(64));
        jdbc.update("update source set status='DELETED',delete_reason='COMPLIANCE' where id='s'");
        tx.executeWithoutResult(status -> new RunReplayRedactionService(jdbc,new ObjectMapper()).redactDeletedSource("w","s"));
        assertThat(jdbc.queryForObject("select status from answer_run where id='run'",String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select content from knowledge_version where id='v'",String.class)).isEmpty();
        assertThat(jdbc.queryForObject("select excerpt from answer_run_evidence_manifest where id='manifest'",String.class)).isEmpty();
    }

    @Test void userDeletionPreservesLegitimateCompletedKnowledgeContent() {
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values ('kvc','v','cite',0)");
        jdbc.update("update source set status='DELETED',delete_reason='USER' where id='s'");
        tx.executeWithoutResult(status -> new RunReplayRedactionService(jdbc,new ObjectMapper()).redactDeletedSource("w","s"));
        assertThat(jdbc.queryForObject("select content from knowledge_version where id='v'",String.class)).isEqualTo("笔记正文");
        assertThat(jdbc.queryForObject("select quote_text from citation where id='cite'",String.class)).isEqualTo("引用正文");
    }

    @Test void complianceParentLockWinsBeforeCopyThenPreventsSensitiveBinding() throws Exception {
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var copyStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var purging=pool.submit(()->tx.execute(s->{
                jdbc.queryForObject("select id from source where id='s' for update",String.class);
                jdbc.update("update source set status='DELETED',delete_reason='COMPLIANCE' where id='s'");
                locked.countDown();
                await(release);
                new RunReplayRedactionService(jdbc,new ObjectMapper()).redactDeletedSource("w","s");
                jdbc.update("update citation set quote_text='' where source_id='s'");
                return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var copying=pool.submit(()->{
                copyStarted.countDown();
                return tx.execute(s->bindings.bindToKnowledgeVersion("w","v",List.of("cite")));
            });
            assertThat(copyStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->copying.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            purging.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->copying.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("select count(*) from knowledge_version_citation",Integer.class)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void copyWinsBeforeComplianceThenEveryCopiedDependencyIsPurged() throws Exception {
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var purgeStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var copying=pool.submit(()->tx.execute(status->{
                var result=bindings.bindToKnowledgeVersion("w","v",List.of("cite"));
                locked.countDown();
                await(release);
                return result;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var purging=pool.submit(()->{
                purgeStarted.countDown();
                return tx.execute(status->{
                    jdbc.queryForObject("select id from source where id='s' for update",String.class);
                    jdbc.update("update source set status='DELETED',delete_reason='COMPLIANCE' where id='s'");
                    new RunReplayRedactionService(jdbc,new ObjectMapper()).redactDeletedSource("w","s");
                    new com.noteweave.source.SourceCompliancePurgeService(jdbc,
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),tx).purge("w","s");
                    return null;
                });
            });
            assertThat(purgeStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->purging.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var result=copying.get(5,TimeUnit.SECONDS);
            purging.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select quote_text from citation where id=?",String.class,result.citationIds().get(0))).isEmpty();
            assertThat(jdbc.queryForObject("select content from knowledge_version where id='v'",String.class)).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("屏障等待超时"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
