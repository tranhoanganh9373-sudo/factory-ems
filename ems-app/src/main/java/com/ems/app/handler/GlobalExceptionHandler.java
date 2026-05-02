package com.ems.app.handler;

import com.ems.app.observability.AppMetrics;
import com.ems.core.constant.ErrorCode;
import com.ems.core.dto.Result;
import com.ems.core.exception.*;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppMetrics appMetrics;

    public GlobalExceptionHandler(AppMetrics appMetrics) {
        this.appMetrics = appMetrics;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> biz(BusinessException ex) {
        HttpStatus s = switch (ex.getCode()) {
            case ErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case ErrorCode.CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.info("biz_ex code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(s).body(Result.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("validation failed");
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.PARAM_INVALID, msg));
    }

    /**
     * Triggered by Bean Validation constraints on {@code @RequestParam} / {@code @PathVariable}
     * when the controller is annotated with {@code @Validated} — e.g. {@code @Min(1) @Max(200) int size}.
     * Without this handler the violation falls through to the catch-all and returns 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> constraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    int dot = path.lastIndexOf('.');
                    String name = dot >= 0 ? path.substring(dot + 1) : path;
                    return name + ": " + v.getMessage();
                })
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        log.info("constraint_violation: {}", msg);
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.PARAM_INVALID, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<?>> denied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.error(ErrorCode.FORBIDDEN, "access denied"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<?>> badCred(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Result.error(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    /* ── Spring 标准 4xx ─ 不污染 ERROR 日志，info 级别足够 ───────────── */

    /** 路径找不到 controller / static resource → 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> notFound(NoResourceFoundException ex) {
        log.info("not_found path={}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Result.error(ErrorCode.NOT_FOUND, "Not Found: " + ex.getResourcePath()));
    }

    /** 路径存在但 HTTP 方法不允许 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.info("method_not_allowed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Result.error(ErrorCode.PARAM_INVALID, ex.getMessage()));
    }

    /** Content-Type 不支持 → 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> mediaType(HttpMediaTypeNotSupportedException ex) {
        log.info("unsupported_media_type: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Result.error(ErrorCode.PARAM_INVALID, ex.getMessage()));
    }

    /** 缺必填 query/form 参数 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> missingParam(MissingServletRequestParameterException ex) {
        log.info("missing_param: {}", ex.getMessage());
        return ResponseEntity.badRequest()
            .body(Result.error(ErrorCode.PARAM_INVALID,
                    "缺少必填参数: " + ex.getParameterName()));
    }

    /** 参数类型转换失败（"abc" → Long 等）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("type_mismatch param={} value={}", ex.getName(), ex.getValue());
        return ResponseEntity.badRequest()
            .body(Result.error(ErrorCode.PARAM_INVALID,
                    "参数类型错误: " + ex.getName() + "=" + ex.getValue()));
    }

    /** JSON 请求体解析失败（缺 body / malformed JSON）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> bodyUnreadable(HttpMessageNotReadableException ex) {
        log.info("body_unreadable: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
            .body(Result.error(ErrorCode.PARAM_INVALID, "请求体格式错误"));
    }

    /** 业务侧抛的 IllegalArgumentException — 当 400 处理（避免误报 500）。
     *  框架内部抛出的也归此（如 PageRequest.of(-1)）；调用方应避免，但好歹给个清晰回复。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> illegalArg(IllegalArgumentException ex) {
        log.info("illegal_arg: {}", ex.getMessage());
        return ResponseEntity.badRequest()
            .body(Result.error(ErrorCode.PARAM_INVALID, ex.getMessage()));
    }

    /** 状态机违例（已 LOCKED 不可写 / 缺前置 cost run 等）→ 409 CONFLICT。
     *  原始异常消息直接回给客户端，方便排查；详情在 info 级。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<?>> illegalState(IllegalStateException ex) {
        log.info("illegal_state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Result.error(ErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unknown(Exception ex) {
        // 必须先记日志再埋点：metrics 失败不能吞掉原异常的 stack trace（Key Invariant #2）。
        log.error("unhandled_ex", ex);
        try {
            appMetrics.incrementException(ex.getClass().getSimpleName());
        } catch (Throwable metricsEx) {
            log.warn("metrics increment failed (non-fatal): {}", metricsEx.toString());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.error(ErrorCode.INTERNAL_ERROR, "服务器错误，请联系管理员"));
    }
}
