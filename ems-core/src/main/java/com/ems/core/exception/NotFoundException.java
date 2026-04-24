package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class NotFoundException extends BusinessException {
    public NotFoundException(String resource, Object id) {
        super(ErrorCode.NOT_FOUND, resource + " not found: " + id);
    }
}
