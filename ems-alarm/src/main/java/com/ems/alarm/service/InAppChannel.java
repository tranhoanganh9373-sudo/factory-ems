package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;

public interface InAppChannel {
    void sendTriggered(Alarm a);
    void sendResolved(Alarm a);
}
