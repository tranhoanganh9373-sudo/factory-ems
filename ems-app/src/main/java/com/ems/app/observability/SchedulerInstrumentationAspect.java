package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class SchedulerInstrumentationAspect {

    private final MeterRegistry registry;

    public SchedulerInstrumentationAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object instrumentScheduled(ProceedingJoinPoint pjp) throws Throwable {
        String declaring = pjp.getSignature().getDeclaringTypeName();
        String simpleClass = declaring.substring(declaring.lastIndexOf('.') + 1);
        String task = simpleClass + "." + pjp.getSignature().getName();

        Timer.Sample sample = Timer.start(registry);
        try {
            return pjp.proceed();
        } finally {
            sample.stop(Timer.builder("ems.app.scheduled.duration")
                    .description("@Scheduled 任务耗时")
                    .tag("task", task)
                    .register(registry));
        }
    }
}
