package org.rin.web;

import org.rin.agent.dto.ApiError;
import org.rin.db.SqlExecutionException;
import org.rin.db.UnsafeSqlException;
import org.rin.exception.BadRequestException;
import org.rin.exception.ChatCancelledException;
import org.rin.exception.ForbiddenException;
import org.rin.exception.InputLimitException;
import org.rin.exception.NotFoundException;
import org.rin.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    /**
     * 智能报表输入达到上限：前端据此提示“新建会话并重新上传模板”。
     */
    @ExceptionHandler(InputLimitException.class)
    public ResponseEntity<ApiError> handleInputLimit(InputLimitException e) {
        log.info("报表会话输入达到上限: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "INPUT_LIMIT", e.getMessage());
    }

    /**
     * 用户主动停止（如智能报表这类同步接口）：不是业务错误，前端按“已停止”提示即可。
     */
    @ExceptionHandler(ChatCancelledException.class)
    public ResponseEntity<ApiError> handleCancelled(ChatCancelledException e) {
        log.info("任务已被停止: {}", e.getMessage());
        return build(HttpStatus.CONFLICT, "STOPPED", "已停止生成");
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
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "SQL_EXECUTION_ERROR", "查询执行失败");
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return build(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "上传文件超过大小限制");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "缺少上传文件或表单字段：" + e.getRequestPartName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "缺少请求参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "请求参数格式不正确：" + e.getName());
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
