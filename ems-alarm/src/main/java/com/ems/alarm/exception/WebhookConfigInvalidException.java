package com.ems.alarm.exception;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;

public class WebhookConfigInvalidException extends BusinessException {
    public WebhookConfigInvalidException(String message) {
        super(ErrorCode.PARAM_INVALID, message);
    }
}
