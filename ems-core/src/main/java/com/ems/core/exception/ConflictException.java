package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class ConflictException extends BusinessException {
    public ConflictException(String reason) { super(ErrorCode.CONFLICT, reason); }
}
