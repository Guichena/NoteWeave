package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationMessageSequenceTest {

    private ExecutorService executor;
    private JdbcTemplate jdbcTemplate;
    private ConversationMessageSequence sequence;
    private TransactionTemplate transactionTemplate;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-sequence-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table conversation (
                    id varchar(36) primary key,
                    status varchar(32) not null,
                    next_message_seq int not null default 1,
                    updated_by varchar(80) not null default 'SYSTEM:TEST',
                    updated_at timestamp not null default current_timestamp
                )
                """);
        jdbcTemplate.update(
                "insert into conversation(id, status, next_message_seq) values (?, 'ACTIVE', 1)",
                "conversation-1"
        );
        meterRegistry = new SimpleMeterRegistry();
        sequence = new ConversationMessageSequence(jdbcTemplate, meterRegistry);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        executor = Executors.newFixedThreadPool(12);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentAllocationsShouldReserveUniqueContinuousPairs() throws Exception {
        int allocationCount = 50;
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < allocationCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return transactionTemplate.execute(status -> sequence.allocatePair("conversation-1"));
            }));
        }
        ready.await();
        start.countDown();

        List<Integer> pairStarts = new ArrayList<>();
        for (Future<Integer> future : futures) {
            pairStarts.add(future.get());
        }
        Collections.sort(pairStarts);

        assertThat(pairStarts).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, allocationCount)
                        .map(index -> index * 2 + 1)
                        .boxed()
                        .toList()
        );
        assertThat(jdbcTemplate.queryForObject(
                "select next_message_seq from conversation where id = ?",
                Integer.class,
                "conversation-1"
        )).isEqualTo(101);
        assertThat(jdbcTemplate.queryForObject(
                "select updated_by from conversation where id = ?",
                String.class,
                "conversation-1"
        )).isEqualTo("SYSTEM:CONVERSATION");
    }

    @Test
    void invalidSequenceStateShouldIncrementMetric() {
        jdbcTemplate.update("update conversation set next_message_seq = -1 where id = 'conversation-1'");

        assertThatThrownBy(() -> sequence.allocatePair("conversation-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("CONVERSATION_SEQUENCE_INVALID");
        assertThat(meterRegistry.get("noteweave.conversation.sequence.invalid").counter().count()).isEqualTo(1.0);
    }
}
