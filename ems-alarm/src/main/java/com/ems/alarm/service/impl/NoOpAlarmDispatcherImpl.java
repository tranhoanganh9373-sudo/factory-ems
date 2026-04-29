package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.service.AlarmDispatcher;
import org.springframework.stereotype.Service;

@Service
public class NoOpAlarmDispatcherImpl implements AlarmDispatcher {
    @Override public void dispatch(Alarm alarm) { /* Phase E 替换 */ }
    @Override public void dispatchResolved(Alarm alarm) { /* Phase E 替换 */ }
}
