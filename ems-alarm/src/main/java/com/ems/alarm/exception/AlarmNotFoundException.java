package com.ems.alarm.exception;

import com.ems.core.exception.NotFoundException;

public class AlarmNotFoundException extends NotFoundException {
    public AlarmNotFoundException(Long id) {
        super("Alarm", id);
    }
}
