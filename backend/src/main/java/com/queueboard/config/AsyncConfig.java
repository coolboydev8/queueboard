package com.queueboard.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables the scheduling infrastructure used by the simulated processor and defines a
 * dedicated, bounded executor for job processing so simulated work never starves the
 * web thread pool. Threads are named for readable logs ({@code job-worker-N}; the
 * claim-loop tick runs on {@code job-scheduler-N}, see application.yml).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProcessingProperties.class)
public class AsyncConfig {

    @Bean(name = "jobProcessingExecutor")
    ThreadPoolTaskExecutor jobProcessingExecutor(ProcessingProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerConcurrency());
        executor.setMaxPoolSize(properties.workerConcurrency());
        // No invisible backlog: a job is claimed only when a worker can take it now.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
