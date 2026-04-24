package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(String reason) { super(ErrorCode.UNAUTHORIZED, reason); }
}
