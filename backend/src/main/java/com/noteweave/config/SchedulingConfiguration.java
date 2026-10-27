package com.noteweave.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfiguration {

    @Bean(name = "taskScheduler")
    TaskScheduler taskScheduler(
            @Value("${noteweave.executors.scheduling.pool-size:4}") int poolSize
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, poolSize));
        scheduler.setThreadNamePrefix("noteweave-scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = "artifactDispatchExecutor")
    ThreadPoolTaskExecutor artifactDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("artifact-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * 检索索引全量重建的专用线程池（IDX-04）：重建要跑很久，必须离开请求线程，
     * 又不能和实时问答抢线程；队列满就拒绝，让调用方拿到明确的「稍后再试」而不是无限堆积。
     */
    @Bean(name = "retrievalRebuildExecutor")
    ThreadPoolTaskExecutor retrievalRebuildExecutor(
            @Value("${noteweave.executors.retrieval-rebuild.core-size:1}") int coreSize,
            @Value("${noteweave.executors.retrieval-rebuild.max-size:2}") int maxSize,
            @Value("${noteweave.executors.retrieval-rebuild.queue-capacity:8}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(1, maxSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("retrieval-rebuild-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
