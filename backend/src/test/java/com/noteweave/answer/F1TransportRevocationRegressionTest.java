package com.noteweave.answer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class F1TransportRevocationRegressionTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<AnswerRealtimeBridge> noBridge() { return mock(ObjectProvider.class); }

    @Test void runPendingQueueChecksPersistentRevocationImmediatelyBeforeDispatch() throws Exception {
        BlockingQueue<Runnable> dispatch=new LinkedBlockingQueue<>();
        AtomicBoolean readable=new AtomicBoolean(true);
        AnswerReplayAccessGuard guard=mock(AnswerReplayAccessGuard.class);
        when(guard.isReadable("run")).thenAnswer(invocation->readable.get());
        SessionEventMux mux=new SessionEventMux(dispatch::add,Runnable::run,noBridge(),new SimpleMeterRegistry(),guard,64,16,900,10000);
        mux.publish("run","answer.delta","secret");
        List<AnswerLiveEvent> received=new CopyOnWriteArrayList<>();
        Thread follower=new Thread(()->mux.follow("run",0,received::add,Duration.ofSeconds(2)));
        follower.start();
        Runnable scheduled=dispatch.poll(1,TimeUnit.SECONDS);
        assertThat(scheduled).isNotNull();
        readable.set(false); // 另一实例提交删除，不调用本实例redact。
        scheduled.run();
        follower.join(1000);
        assertThat(received).isEmpty();
        assertThat(follower.isAlive()).isFalse();
        assertThatThrownBy(()->mux.publish("run","answer.delta","late")).isInstanceOf(com.noteweave.common.BusinessException.class);
    }

    @Test void conversationPendingQueueDoesNotLeakDeletedRunAndKeepsOtherRun() throws Exception {
        BlockingQueue<Runnable> dispatch=new LinkedBlockingQueue<>();
        AtomicBoolean readable=new AtomicBoolean(true);
        AnswerReplayAccessGuard guard=mock(AnswerReplayAccessGuard.class);
        when(guard.isRevoked("deleted")).thenAnswer(invocation->!readable.get());
        when(guard.isRevoked("safe")).thenReturn(false);
        ConversationEventMux mux=new ConversationEventMux(noBridge(),Runnable::run,dispatch::add,new SimpleMeterRegistry(),guard,64,16,900,10000);
        mux.publish("c","deleted",new AnswerLiveEvent(1,"answer.delta","secret",Instant.now()));
        mux.publish("c","safe",new AnswerLiveEvent(2,"answer.completed","safe",Instant.now()));
        List<ConversationLiveEvent> received=new CopyOnWriteArrayList<>();
        Thread follower=new Thread(()->mux.follow("c",0,received::add,Duration.ofSeconds(2),true));
        follower.start();
        Runnable scheduled=dispatch.poll(1,TimeUnit.SECONDS);
        assertThat(scheduled).isNotNull();
        readable.set(false);
        scheduled.run();
        follower.join(1000);
        assertThat(received).extracting(ConversationLiveEvent::data).containsExactly("safe");
        assertThat(follower.isAlive()).isFalse();
    }
}
