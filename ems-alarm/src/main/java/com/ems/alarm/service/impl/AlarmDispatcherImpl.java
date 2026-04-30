package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.InAppChannel;
import com.ems.alarm.service.WebhookChannel;
import org.springframework.stereotype.Service;

@Service
public class AlarmDispatcherImpl implements AlarmDispatcher {

    private final InAppChannel inApp;
    private final WebhookChannel webhook;

    public AlarmDispatcherImpl(InAppChannel inApp, WebhookChannel webhook) {
        this.inApp = inApp;
        this.webhook = webhook;
    }

    @Override
    public void dispatch(Alarm a) {
        inApp.sendTriggered(a);
        webhook.sendTriggered(a);
    }

    @Override
    public void dispatchResolved(Alarm a) {
        inApp.sendResolved(a);
        // 首版恢复事件不发 webhook
    }
}
