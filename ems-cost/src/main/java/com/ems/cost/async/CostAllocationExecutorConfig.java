package com.ems.cost.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Executor;

/**
 * 成本分摊专用线程池：core=1, max=2, 队列 20。
 * 与 reportExportExecutor 隔离，避免一次 cost run 跑 30s 把报表导出挤掉。
 *
 * 通过 TaskDecorator 把当前线程的 SecurityContext 透传到 worker，
 * 否则 @PreAuthorize / 审计 actor 在 worker 里看不到 principal。
 */
@Configuration
public class CostAllocationExecutorConfig {

    public static final String BEAN_NAME = "costAllocationExecutor";

    @Bean(name = BEAN_NAME)
    public Executor costAllocationExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(20);
        ex.setThreadNamePrefix("cost-alloc-");
        ex.setTaskDecorator(runnable -> {
            SecurityContext ctx = SecurityContextHolder.getContext();
            return () -> {
                SecurityContext prev = SecurityContextHolder.getContext();
                SecurityContextHolder.setContext(ctx);
                try {
                    runnable.run();
                } finally {
                    SecurityContextHolder.setContext(prev);
                }
            };
        });
        ex.initialize();
        return ex;
    }
}
