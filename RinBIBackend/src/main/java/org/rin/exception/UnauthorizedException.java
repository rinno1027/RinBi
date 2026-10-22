package org.rin.exception;

/**
 * 未登录或登录已过期。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
