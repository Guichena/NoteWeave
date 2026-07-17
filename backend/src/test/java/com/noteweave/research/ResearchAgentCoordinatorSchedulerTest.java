package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ResearchAgentCoordinatorSchedulerTest {

    private final ResearchAgentLifecycleService lifecycle = mock(ResearchAgentLifecycleService.class);
    private final ResearchAgentCoordinatorRunScanner scanner = mock(ResearchAgentCoordinatorRunScanner.class);
    private final ResearchAgentCoordinatorTickService tick = mock(ResearchAgentCoordinatorTickService.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ResearchAgentLifecycleService.class, () -> lifecycle)
            .withBean(ResearchAgentCoordinatorRunScanner.class, () -> scanner)
            .withBean(ResearchAgentCoordinatorTickService.class, () -> tick)
            .withUserConfiguration(SchedulerConfiguration.class);

    @Test
    void shouldBePresentByDefaultAndReapBeforeTickingEligibleIncrementalRuns() {
        when(scanner.findEligibleRunIds(25)).thenReturn(List.of("run-b", "run-c"));
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ResearchAgentCoordinatorScheduler.class);
            context.getBean(ResearchAgentCoordinatorScheduler.class).recoverEligibleRuns();
            InOrder order = inOrder(lifecycle, scanner, tick);
            order.verify(lifecycle).reapExpiredLeases();
            order.verify(scanner).findEligibleRunIds(25);
            order.verify(tick).tick("run-b", "local-coordinator");
            order.verify(tick).tick("run-c", "local-coordinator");
            verifyNoMoreInteractions(lifecycle, scanner, tick);
        });
    }

    @Test
    void shouldBeAbsentWhenAnIsolatedTestOrMaintenanceModeExplicitlyDisablesIt() {
        contextRunner.withPropertyValues("noteweave.research.agent.coordinator-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ResearchAgentCoordinatorScheduler.class));
    }

    @Test
    void shouldFailFastForUnsafeRunBatchSize() {
        contextRunner.withPropertyValues("noteweave.research.agent.coordinator-run-batch-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void shouldContinueWithOtherRunsWhenOneDurableTickFails() {
        when(scanner.findEligibleRunIds(25)).thenReturn(List.of("broken-run", "recoverable-run"));
        doThrow(new IllegalStateException("injected failure")).when(tick).tick("broken-run", "local-coordinator");
        contextRunner.run(context -> {
            context.getBean(ResearchAgentCoordinatorScheduler.class).recoverEligibleRuns();
            InOrder order = inOrder(tick);
            order.verify(tick).tick("broken-run", "local-coordinator");
            order.verify(tick).tick("recoverable-run", "local-coordinator");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ResearchAgentCoordinatorScheduler.class)
    static class SchedulerConfiguration { }
}
