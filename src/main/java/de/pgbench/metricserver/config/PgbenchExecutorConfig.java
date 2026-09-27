package de.pgbench.metricserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Single threaded executor, pgbench runs are never executed concurrently. */
@Configuration(proxyBeanMethods = false)
public class PgbenchExecutorConfig {

    @Bean(name = "pgbenchExecutor")
    public ThreadPoolTaskExecutor pgbenchExecutor(PgbenchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("pgbench-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) Math.min(Integer.MAX_VALUE, properties.getTimeout().toSeconds() + 30));
        executor.initialize();
        return executor;
    }
}
