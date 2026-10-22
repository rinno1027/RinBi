package org.rin.db;

/**
 * SQL 未通过只读安全校验。
 */
public class UnsafeSqlException extends RuntimeException {

    public UnsafeSqlException(String message) {
        super(message);
    }

    public UnsafeSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
