package com.noteweave.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RealtimeExecutorConfig {

    @Bean(name = "answerIoExecutor")
    public Executor answerIoExecutor(
            @Value("${noteweave.executors.answer-io.core-size:4}") int coreSize,
            @Value("${noteweave.executors.answer-io.max-size:16}") int maxSize,
            @Value("${noteweave.executors.answer-io.queue-capacity:64}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("answer-io-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(runnable -> {
            java.util.Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                java.util.Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (captured == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(captured);
                    }
                    runnable.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }

    @Bean(name = "sseDispatchExecutor")
    public Executor sseDispatchExecutor(
            @Value("${noteweave.executors.sse-dispatch.core-size:2}") int coreSize,
            @Value("${noteweave.executors.sse-dispatch.max-size:8}") int maxSize,
            @Value("${noteweave.executors.sse-dispatch.queue-capacity:128}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-dispatch-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "sseConnectionExecutor")
    public Executor sseConnectionExecutor(
            @Value("${noteweave.executors.sse-connection.core-size:4}") int coreSize,
            @Value("${noteweave.executors.sse-connection.max-size:32}") int maxSize,
            @Value("${noteweave.executors.sse-connection.queue-capacity:0}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-connection-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "answerBridgeExecutor")
    public Executor answerBridgeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("answer-bridge-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(16);
        // Blocking XREAD tasks must grow to max threads before queueing, otherwise two pumps starve all peers.
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown", name = "answerEventScheduler")
    public ScheduledExecutorService answerEventScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "answer-event-batch");
            thread.setDaemon(true);
            return thread;
        });
    }
}
