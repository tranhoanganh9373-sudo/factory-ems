package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;

public interface AlarmDispatcher {
    /** 派发新触发的报警（站内 + Webhook）。 */
    void dispatch(Alarm alarm);
    /** 派发自动恢复（站内 RESOLVED 通知）。 */
    void dispatchResolved(Alarm alarm);
}
