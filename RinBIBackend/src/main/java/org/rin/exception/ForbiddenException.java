package org.rin.exception;

/**
 * 已登录但无权访问目标资源（例如其他用户的数据源或会话）。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
