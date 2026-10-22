package org.rin.exception;

/**
 * 目标资源不存在。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
