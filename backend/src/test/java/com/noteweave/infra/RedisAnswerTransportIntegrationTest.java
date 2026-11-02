package com.noteweave.infra;

import static org.assertj.core.api.Assertions.*;
import com.noteweave.answer.AnswerLiveEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 只允许显式指定的隔离loopback Redis；所有key使用随机前缀，绝不FLUSHDB。 */
@EnabledIfEnvironmentVariable(named="NOTEWEAVE_REDIS_TRANSPORT_TEST_PORT",matches="[0-9]+")
class RedisAnswerTransportIntegrationTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisAnswerRealtimeBridge bridge;
    final String namespace="f1-transport-"+UUID.randomUUID();
    @BeforeEach void start() {
        int port=Integer.parseInt(System.getenv("NOTEWEAVE_REDIS_TRANSPORT_TEST_PORT"));
        if(port==6379) throw new IllegalArgumentException("禁止使用默认业务Redis端口");
        factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port));
        factory.afterPropertiesSet(); factory.start();
        redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
        bridge=new RedisAnswerRealtimeBridge(redis,new SimpleMeterRegistry(),64,60);
    }
    @AfterEach void stop() {
        if(redis!=null) {
            var keys=redis.keys("noteweave:v1:*"+namespace+"*");
            if(keys!=null&&!keys.isEmpty()) redis.delete(keys);
        }
        if(factory!=null) factory.destroy();
    }
    AnswerLiveEvent event(long seq,String data) { return new AnswerLiveEvent(seq,"answer.delta",data,Instant.now()); }

    @Test void physicalRedactionKeepsOtherRunAndNeverReusesConversationSequence() {
        String c=namespace+"-c",a=namespace+"-a",b=namespace+"-b";
        bridge.publish(a,event(1,"secret"));
        long first=bridge.publishConversation(c,a,event(1,"secret")).sequence();
        long second=bridge.publishConversation(c,b,event(1,"safe")).sequence();
        bridge.redact(c,a);
        assertThat(redis.hasKey("noteweave:v1:stream:answer:"+a)).isFalse();
        assertThat(bridge.isReplayRevoked(a)).isTrue();
        assertThat(bridge.readConversationAfter(c,0,Duration.ZERO)).extracting(e->e.data()).containsExactly("safe");
        assertThatThrownBy(()->bridge.publish(a,event(2,"late-secret"))).isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThatThrownBy(()->bridge.publishConversation(c,a,event(2,"late-secret"))).isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThat(redis.getExpire("noteweave:v1:seq:conversation:"+c)).isEqualTo(-1L);
        redis.delete("noteweave:v1:stream:conversation:"+c);
        assertThat(bridge.publishConversation(c,b,event(2,"safe2")).sequence()).isGreaterThan(second).isGreaterThan(first);
    }

    @Test void publishAndDeleteRaceCannotRepopulateSensitiveStreams() throws Exception {
        String c=namespace+"-race-c",r=namespace+"-race-r";
        ExecutorService executor=Executors.newFixedThreadPool(3);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(int worker=0;worker<2;worker++) {
                final int offset=worker*100;
                jobs.add(executor.submit(()->{ start.await(); for(int i=1;i<=50;i++) {
                    try { bridge.publish(r,event(offset+i,"secret")); bridge.publishConversation(c,r,event(offset+i,"secret")); }
                    catch(com.noteweave.common.BusinessException expected) { break; }
                } return null; }));
            }
            jobs.add(executor.submit(()->{ start.await(); bridge.redact(c,r); return null; }));
            start.countDown();
            for(Future<?> job:jobs) job.get(10,TimeUnit.SECONDS);
            assertThat(redis.hasKey("noteweave:v1:stream:answer:"+r)).isFalse();
            assertThat(bridge.readConversationAfter(c,0,Duration.ZERO)).isEmpty();
            assertThat(bridge.isReplayRevoked(r)).isTrue();
        } finally { executor.shutdownNow(); }
    }

    @Test void trimmedOrExpiredRunHistoryRequiresExplicitRecoveryIncludingZeroCursor() {
        String r=namespace+"-trim";
        for(int i=1;i<=70;i++) bridge.publish(r,event(i,"part"));
        assertThatThrownBy(()->bridge.readAfter(r,0,Duration.ZERO)).isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThat(bridge.readAfter(r,6,Duration.ZERO)).hasSize(64);
        redis.delete("noteweave:v1:stream:answer:"+r);
        assertThatThrownBy(()->bridge.readAfter(r,6,Duration.ZERO)).isInstanceOf(com.noteweave.common.BusinessException.class);
    }
    @Test void newPublishAfterStreamExpirationStillDetectsMissingPrefix() {
        String r=namespace+"-expire-republish";
        bridge.publish(r,event(1,"old"));
        redis.delete("noteweave:v1:stream:answer:"+r);
        bridge.publish(r,event(2,"new"));
        assertThatThrownBy(()->bridge.readAfter(r,0,Duration.ZERO)).isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThat(bridge.readAfter(r,1,Duration.ZERO)).extracting(AnswerLiveEvent::data).containsExactly("new");
    }

}
