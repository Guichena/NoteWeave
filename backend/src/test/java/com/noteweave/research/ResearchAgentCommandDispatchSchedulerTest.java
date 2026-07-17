package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ResearchAgentCommandDispatchSchedulerTest {

    private final ResearchAgentCommandDispatcher dispatcher = mock(ResearchAgentCommandDispatcher.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ResearchAgentCommandDispatcher.class, () -> dispatcher)
            .withUserConfiguration(SchedulerConfiguration.class);

    @Test
    void shouldBePresentWhenTheDefaultKafkaTransportIsEnabled() {
        contextRunner.withPropertyValues("noteweave.kafka.enabled=true").run(context ->
                assertThat(context).hasSingleBean(ResearchAgentCommandDispatchScheduler.class));
    }

    @Test
    void shouldStayAbsentWhenKafkaTransportIsDisabled() {
        contextRunner.withPropertyValues("noteweave.kafka.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(ResearchAgentCommandDispatchScheduler.class));
    }

    @Test
    void shouldDispatchConfiguredBoundedBatch() {
        contextRunner
                .withPropertyValues(
                        "noteweave.kafka.enabled=true",
                        "noteweave.research.agent.command-dispatch-batch-size=17")
                .run(context -> {
                    var scheduler = context.getBean(ResearchAgentCommandDispatchScheduler.class);
                    scheduler.dispatchReadyCommands();
                    verify(dispatcher).dispatchReady(17);
                });
    }

    @Test
    void shouldFailFastForUnsafeBatchSize() {
        contextRunner
                .withPropertyValues(
                        "noteweave.kafka.enabled=true",
                        "noteweave.research.agent.command-dispatch-batch-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ResearchAgentCommandDispatchScheduler.class)
    static class SchedulerConfiguration { }
}
