package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class ForbiddenException extends BusinessException {
    public ForbiddenException(String reason) { super(ErrorCode.FORBIDDEN, reason); }
}
