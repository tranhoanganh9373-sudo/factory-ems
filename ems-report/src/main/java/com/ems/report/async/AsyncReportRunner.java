package com.ems.report.async;

import com.ems.report.dto.ReportRequest;
import com.ems.report.service.ReportService;
import com.ems.report.support.CsvReportWriter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 后台执行 CSV 导出。固定 5 线程；SecurityContext 透传到 worker。 */
@Component
public class AsyncReportRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncReportRunner.class);
    private static final int MAX_CONCURRENT = 5;

    private final ReportService service;
    private final ExecutorService exec;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    public AsyncReportRunner(ReportService service) {
        this.service = service;
        this.exec = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
            Thread t = new Thread(r, "report-async");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(FileTokenStore.Entry entry, ReportRequest req) {
        var ctx = SecurityContextHolder.getContext();
        Runnable task = () -> execute(entry, req);
        exec.execute(DelegatingSecurityContextRunnable.create(task, ctx));
    }

    private void execute(FileTokenStore.Entry entry, ReportRequest req) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry.error = "interrupted while waiting for slot";
            entry.status = FileTokenStore.Status.FAILED;
            return;
        }
        try {
            entry.status = FileTokenStore.Status.RUNNING;
            Path tmp = Files.createTempFile("ems-report-", ".csv");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                CsvReportWriter.write(service.queryStream(req), out);
            }
            entry.file = tmp;
            entry.bytes = Files.size(tmp);
            entry.status = FileTokenStore.Status.READY;
        } catch (Throwable t) {
            log.warn("async report failed: token={} err={}", entry.token, t.toString());
            entry.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            entry.status = FileTokenStore.Status.FAILED;
        } finally {
            slots.release();
        }
    }

    @PreDestroy
    public void shutdown() {
        exec.shutdownNow();
    }
}
