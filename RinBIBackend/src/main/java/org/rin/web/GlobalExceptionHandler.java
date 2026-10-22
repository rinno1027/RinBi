package org.rin.web;

import org.rin.agent.dto.ApiError;
import org.rin.db.SqlExecutionException;
import org.rin.db.UnsafeSqlException;
import org.rin.exception.BadRequestException;
import org.rin.exception.ForbiddenException;
import org.rin.exception.NotFoundException;
import org.rin.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.RejectedExecutionException;

/**
 * 统一异常响应，避免把堆栈暴露给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException e) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException e) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UnsafeSqlException.class)
    public ResponseEntity<ApiError> handleUnsafeSql(UnsafeSqlException e) {
        return build(HttpStatus.BAD_REQUEST, "UNSAFE_SQL", e.getMessage());
    }

    @ExceptionHandler(SqlExecutionException.class)
    public ResponseEntity<ApiError> handleSqlExecution(SqlExecutionException e) {
        log.warn("SQL 执行异常: {}", e.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "SQL_EXECUTION_ERROR", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_JSON", "请求体不是合法的 JSON");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().isEmpty()
                ? "请求参数不合法"
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<ApiError> handleRejectedExecution(RejectedExecutionException e) {
        log.warn("线程池已满，拒绝任务: {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_BUSY", "服务器繁忙，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        ApiError body = ApiError.builder()
                .code(code)
                .message(message == null ? status.getReasonPhrase() : message)
                .timestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT))
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
