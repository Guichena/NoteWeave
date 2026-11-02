package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiPageVersionCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class F1WikiCacheRedactionTransactionTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    RunReplayRedactionService service;
    WikiPageVersionCache cache;
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:f1-cache-tx-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into file_object(id,workspace_id,object_key,sha256,file_size) values ('file','w','key',?,1)","a".repeat(64));
        jdbc.update("insert into source(id,workspace_id,file_object_id,title,source_type,status,parse_status,index_status,delete_reason) values ('s','w','file','来源','TEXT','DELETED','DELETED','DELETED','COMPLIANCE')");
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values ('ss','s','file',1,'key',?,'PARSED','INDEXED')","a".repeat(64));
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,title,quote_text) values ('cite','w','s','ss','来源','正文')");
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status,latest_version_id) values ('item','w','WIKI','页面','ACTIVE','v')");
        jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values ('v','item',1,'正文')");
        jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values ('kvc','v','cite',0)");
        cache=mock(WikiPageVersionCache.class);
        service=new RunReplayRedactionService(jdbc,new ObjectMapper());
        service.setWikiPageVersionCache(cache);
    }
    @Test void rollbackDoesNotInvalidateLegitimateCache() {
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            service.redactDeletedSource("w","s");
            verify(cache,never()).invalidate("w","item","v");
            throw new IllegalStateException("强制回滚");
        })).isInstanceOf(IllegalStateException.class);
        verify(cache,never()).invalidate("w","item","v");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select content from knowledge_version where id='v'",String.class)).isEqualTo("正文");
    }
    @Test void committedRedactionInvalidatesOnlyAfterCommit() {
        tx.executeWithoutResult(status->{
            service.redactDeletedSource("w","s");
            verify(cache,never()).invalidate("w","item","v");
        });
        verify(cache).invalidate("w","item","v");
    }
}
