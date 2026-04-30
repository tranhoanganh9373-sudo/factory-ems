package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.service.impl.AlarmDispatcherImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AlarmDispatcherImplTest {

    private final InAppChannel inApp = mock(InAppChannel.class);
    private final WebhookChannel webhook = mock(WebhookChannel.class);
    private final AlarmDispatcherImpl dispatcher = new AlarmDispatcherImpl(inApp, webhook);

    @Test
    void dispatch_callsBothInAppAndWebhookOnce() {
        Alarm a = newAlarm(1L);

        dispatcher.dispatch(a);

        verify(inApp, times(1)).sendTriggered(a);
        verify(webhook, times(1)).sendTriggered(a);
    }

    @Test
    void dispatchResolved_callsInAppOnly_skipsWebhook() {
        Alarm a = newAlarm(2L);

        dispatcher.dispatchResolved(a);

        verify(inApp, times(1)).sendResolved(a);
        verifyNoInteractions(webhook);
    }

    @Test
    void dispatch_inAppExceptionPropagates() {
        Alarm a = newAlarm(3L);
        doThrow(new RuntimeException("inApp failure")).when(inApp).sendTriggered(a);

        assertThatThrownBy(() -> dispatcher.dispatch(a))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("inApp failure");

        verify(webhook, never()).sendTriggered(a);
    }

    private Alarm newAlarm(Long id) {
        Alarm a = new Alarm();
        a.setId(id);
        return a;
    }
}
