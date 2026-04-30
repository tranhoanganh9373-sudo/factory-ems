package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SchedulerInstrumentationAspectTest {

    @Test
    void instrumentScheduled_recordsDuration() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerInstrumentationAspect aspect = new SchedulerInstrumentationAspect(registry);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.getDeclaringTypeName()).thenReturn("com.ems.app.SampleJob");
        when(sig.getName()).thenReturn("run");
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(null);

        aspect.instrumentScheduled(pjp);

        var timer = registry.find("ems.app.scheduled.duration").tag("task", "SampleJob.run").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void instrumentScheduled_propagatesExceptionAndStillRecordsDuration() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerInstrumentationAspect aspect = new SchedulerInstrumentationAspect(registry);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.getDeclaringTypeName()).thenReturn("com.x.Y");
        when(sig.getName()).thenReturn("z");
        when(pjp.getSignature()).thenReturn(sig);
        RuntimeException boom = new RuntimeException("boom");
        when(pjp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.instrumentScheduled(pjp))
            .isSameAs(boom);

        var timer = registry.find("ems.app.scheduled.duration").tag("task", "Y.z").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }
}
