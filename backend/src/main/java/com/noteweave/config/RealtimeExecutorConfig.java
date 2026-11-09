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

    /**
     * 回答准备线程池：发消息接口返回后，查询改写、检索和证据判定在这里执行。
     * 队列满时由调用方退回到请求线程同步准备，不丢弃用户的消息。
     */
    @Bean(name = "answerPreparationExecutor")
    public Executor answerPreparationExecutor(
            @Value("${noteweave.executors.answer-preparation.core-size:4}") int coreSize,
            @Value("${noteweave.executors.answer-preparation.max-size:16}") int maxSize,
            @Value("${noteweave.executors.answer-preparation.queue-capacity:64}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("answer-prep-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(RealtimeExecutorConfig::withCallerMdc);
        executor.initialize();
        return executor;
    }

    /** 会话前提抽取线程池：抽取不在回答关键路径上，队列满时直接放弃本轮抽取。 */
    @Bean(name = "conversationPremiseExecutor")
    public Executor conversationPremiseExecutor(
            @Value("${noteweave.executors.conversation-premise.core-size:2}") int coreSize,
            @Value("${noteweave.executors.conversation-premise.max-size:4}") int maxSize,
            @Value("${noteweave.executors.conversation-premise.queue-capacity:256}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("conversation-premise-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(RealtimeExecutorConfig::withCallerMdc);
        executor.initialize();
        return executor;
    }

    /**
     * 检索分支线程池：向量召回（远程向量化 + 向量检索）与关键词召回互不依赖，并行执行。
     * 队列满时由调用线程自己执行该分支，结果与串行执行完全相同，只是不再并行。
     */
    @Bean(name = "retrievalBranchExecutor")
    public Executor retrievalBranchExecutor(
            @Value("${noteweave.executors.retrieval-branch.core-size:8}") int coreSize,
            @Value("${noteweave.executors.retrieval-branch.max-size:32}") int maxSize,
            @Value("${noteweave.executors.retrieval-branch.queue-capacity:64}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("retrieval-branch-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(RealtimeExecutorConfig::withCallerMdc);
        executor.initialize();
        return executor;
    }

    /** 把提交线程的 MDC 带到工作线程，执行完恢复工作线程原来的 MDC。 */
    static Runnable withCallerMdc(Runnable runnable) {
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

    @Bean(destroyMethod = "shutdown", name = "answerLeaseScheduler")
    public ScheduledExecutorService answerLeaseScheduler() {
        return Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "answer-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }
}
