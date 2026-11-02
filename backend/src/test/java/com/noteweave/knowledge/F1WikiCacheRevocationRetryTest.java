package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class F1WikiCacheRevocationRetryTest {
    @Test @SuppressWarnings("unchecked")
    void redisFailureRetriesFromSameKeyAndCursorAdvancesBeyondFirstBatch() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:f1-cache-retry-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into file_object(id,workspace_id,object_key,sha256,file_size) values ('file','w','key',?,1)","a".repeat(64));
        jdbc.update("insert into source(id,workspace_id,file_object_id,title,source_type,status,parse_status,index_status,delete_reason) values ('s','w','file','来源','TEXT','DELETED','DELETED','DELETED','COMPLIANCE')");
        jdbc.update("insert into source_snapshot(id,source_id,file_object_id,version_no,object_key,sha256,parse_status,index_status) values ('ss','s','file',1,'key',?,'PARSED','INDEXED')","a".repeat(64));
        jdbc.update("insert into citation(id,workspace_id,source_id,source_snapshot_id,title,quote_text) values ('cite','w','s','ss','来源','')");
        jdbc.update("insert into knowledge_item(id,workspace_id,item_type,title,status) values ('item','w','WIKI','页面','ACTIVE')");
        for(int i=0;i<101;i++) {
            String id="v"+String.format("%03d",i);
            jdbc.update("insert into knowledge_version(id,item_id,version_no,content) values (?,'item',?,'')",id,i+1);
            jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values (?,?,'cite',0)","k"+i,id);
        }
        var redis=mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        var unavailable=new AtomicBoolean(true);
        List<String> purged=new ArrayList<>();
        when(redis.execute(any(RedisScript.class),anyList())).thenAnswer(call->{
            if(unavailable.get()) throw new IllegalStateException("Redis暂不可用");
            List<String> keys=call.getArgument(1);
            purged.add(keys.get(0));
            return 1L;
        });
        var cache=new WikiPageVersionCache(provider,new ObjectMapper(),new SimpleMeterRegistry(),true,600,0);
        cache.setJdbcTemplate(jdbc);
        cache.retryComplianceInvalidation();
        assertThat(purged).isEmpty();
        unavailable.set(false);
        cache.retryComplianceInvalidation();
        assertThat(purged).hasSize(100);
        cache.retryComplianceInvalidation();
        assertThat(purged).hasSize(101).contains("noteweave:v1:cache:wiki_page_version:w:item:v100");
    }
}
