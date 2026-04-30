package com.ems.alarm.exception;

import com.ems.core.exception.ConflictException;

public class AlarmStateException extends ConflictException {
    public AlarmStateException(String message) {
        super(message);
    }
}
