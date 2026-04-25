package com.ems.report.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Executor;

/**
 * 报表异步导出线程池：core=2, max=4, 队列 50。
 * 通过 TaskDecorator 把当前线程的 SecurityContext 透传到 worker，
 * 否则 @PreAuthorize / DashboardSupport 在 worker 里看不到 principal。
 */
@Configuration
public class ReportExportExecutorConfig {

    public static final String BEAN_NAME = "reportExportExecutor";

    @Bean(name = BEAN_NAME)
    public Executor reportExportExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("report-export-");
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
