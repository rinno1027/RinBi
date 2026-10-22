package org.rin.db;

/**
 * SQL 执行失败（配置错误、语法错误、数据库不可用等）。
 */
public class SqlExecutionException extends RuntimeException {

    public SqlExecutionException(String message) {
        super(message);
    }

    public SqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
