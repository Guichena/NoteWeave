package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.GeneratedDependencyAssessment;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 完整依赖、同源多代快照和旧自动页的当前资格政策。 */
class F1KnowledgeEligibilityRegressionTest {
    JdbcTemplate jdbc;
    KnowledgeCitationReadGate gate;
    Map<String,String> current=new HashMap<>();
    Set<String> revoked=new java.util.HashSet<>();
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:f1-knowledge-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into file_object(id,workspace_id,object_key,sha256,file_size) values ('file','w','key',?,1)","a".repeat(64));
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status,latest_version_id,wiki_page_state) values ('item','w','WIKI','页面','ACTIVE','v','MANUAL')");
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('v','item',1,'正文')");
        gate=new KnowledgeCitationReadGate(jdbc,new MaterialEligibilityPort() {
            public Map<String,EligibilityDecision> assess(String ws,List<OriginRef> origins,AccessPurpose purpose) {
                if(origins.size()>MAX_ORIGINS_PER_ASSESS) throw new AssertionError("超过资格批次上限");
                Map<String,EligibilityDecision> decisions=new HashMap<>();
                for(var origin:origins) if(!revoked.contains(origin.sourceId()))
                    decisions.put(origin.originKey(),EligibilityDecision.ready(origin.originKey(),1L,current.get(origin.sourceId())));
                return decisions;
            }
            public GeneratedDependencyAssessment assessGeneratedDependenciesDetailed(String w,String by,String id,AccessPurpose purpose) {
                return new GeneratedDependencyAssessment(true,List.of(),Map.of());
            }
        });
    }
    @Test void sixthDependencyCannotBeHiddenBehindFivePreviewCitations() {
        for(int i=0;i<6;i++) dependency("s"+i,"ss"+i,"c"+i,i);
        assertThatCode(()->gate.requireCurrentVersionEligible("w","item","v")).doesNotThrowAnyException();
        revoked.add("s5");
        assertThatThrownBy(()->gate.requireCurrentVersionEligible("w","item","v")).isInstanceOf(BusinessException.class);
    }
    @Test void sameSourceOldSnapshotCannotBeOverwrittenByCurrentSnapshotDecision() {
        dependency("s","old","c1",1);
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values ('new','s','file',2,'key',?,'PARSED','INDEXED')","b".repeat(64));
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,title,quote_text) values ('c2','w','s','new','来源','正文')");
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values ('k2','v','c2',2)");
        current.put("s","new");
        assertThatThrownBy(()->gate.requireCurrentVersionEligible("w","item","v")).isInstanceOf(BusinessException.class);
    }
    @Test void automaticVersionWithoutTrustedProvenanceIsNotCurrentEvidence() {
        dependency("s","ss","c",1);
        jdbc.update("update knowledge_item set wiki_page_state='AUTO' where id='item'");
        assertThatThrownBy(()->gate.requireCurrentVersionEligible("w","item","v"))
                .isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("KNOWLEDGE_PROVENANCE_UNVERIFIED"));
    }
    @Test void oldVersionIsRejectedAfterLatestVersionChanges() {
        dependency("s","ss","c",1);
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('v2','item',2,'新版')");
        jdbc.update("update knowledge_item set latest_version_id='v2' where id='item'");
        assertThatThrownBy(()->gate.requireCurrentVersionEligible("w","item","v")).isInstanceOf(BusinessException.class);
    }
    void dependency(String source,String snapshot,String citation,int sort) {
        jdbc.update("insert into source(id,workspace_id,file_object_id,title,source_type,status,parse_status,index_status) values (?,'w','file','来源','TEXT','READY','PARSED','INDEXED')",source);
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values (?,?,'file',1,'key',?,'PARSED','INDEXED')",snapshot,source,"a".repeat(64));
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,title,quote_text) values (?,'w',?,?,'来源','正文')",citation,source,snapshot);
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values (?,'v',?,?)","k-"+citation,citation,sort);
        current.put(source,snapshot);
    }
}
