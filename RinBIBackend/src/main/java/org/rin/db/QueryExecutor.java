package org.rin.db;

import org.rin.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只读 SQL 执行器：原始 JDBC + 只读连接 + 超时 + 最大行数 + 字符上限 + 瞬时异常重试。
 */
@Service
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);
    private static final int MAX_CLOB_CHARS = 4000;

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private SqlGuard sqlGuard;

    @Retryable(
            include = {RecoverableDataAccessException.class},
            maxAttemptsExpression = "${agent.sql.retry-max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${agent.sql.retry-backoff-millis:500}",
                    multiplier = 2.0D
            )
    )
    public QueryResult execute(Long userId, String databaseKey, String rawSql) {
        // 二次校验，确保任何调用方都无法绕过只读限制。
        String sql = sqlGuard.validate(rawSql);
        AgentProperties.Sql config = properties.getSql();
        int maxRows = Math.max(1, config.getMaxRows());
        int maxChars = Math.max(1, config.getMaxResultChars());
        int queryTimeout = Math.max(1, config.getQueryTimeoutSeconds());
        long start = System.currentTimeMillis();

        DataSourceDefinition definition = databaseRegistry.requireOwned(userId, databaseKey);
        String resolvedKey = definition.getKey();
        DataSource dataSource = databaseRegistry.getDataSource(definition);
        try (Connection connection = dataSource.getConnection()) {
            setReadOnly(connection);
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(maxRows + 1);
                statement.setQueryTimeout(queryTimeout);
                try {
                    statement.setFetchSize(100);
                } catch (SQLException ignored) {
                    // 部分驱动不支持 fetchSize，不影响查询。
                }
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    return readResult(resolvedKey, sql, resultSet, maxRows, maxChars, start);
                }
            }
        } catch (SQLException e) {
            if (isRecoverable(e)) {
                throw new RecoverableDataAccessException("数据库连接/查询瞬时失败: " + safeMessage(e), e);
            }
            throw new SqlExecutionException("数据库查询失败: " + safeMessage(e), e);
        }
    }

    private QueryResult readResult(String databaseKey, String sql, ResultSet resultSet,
                                   int maxRows, int maxChars, long start) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<ColumnRef> columnRefs = new ArrayList<>(columnCount);
        Set<String> usedLabels = new HashSet<>();
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            if (label == null || label.trim().isEmpty()) {
                label = metaData.getColumnName(i);
            }
            String uniqueLabel = uniqueLabel(label, usedLabels);
            columns.add(uniqueLabel);
            columnRefs.add(new ColumnRef(uniqueLabel,
                    trimToNull(metaData.getColumnName(i)),
                    trimToNull(metaData.getTableName(i))));
        }

        long charCount = columns.size() * 2L;
        int rowCount = 0;
        boolean truncated = false;
        List<List<String>> rows = new ArrayList<>();

        while (resultSet.next()) {
            if (rowCount >= maxRows) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                String value = formatValue(readValue(resultSet, i));
                row.add(value);
                charCount += value.length() + 1L;
            }
            charCount += columnCount + 2L;
            if (charCount > maxChars) {
                return QueryResult.builder()
                        .database(databaseKey)
                        .sql(sql)
                        .columns(columns)
                        .columnRefs(columnRefs)
                        .rowCount(rowCount + 1)
                        .truncated(true)
                        .tooLarge(true)
                        .charCount((int) Math.min(charCount, Integer.MAX_VALUE))
                        .elapsedMs(System.currentTimeMillis() - start)
                        .message("查询结果超过 " + maxChars + " 字符上限")
                        .build();
            }
            rows.add(row);
            rowCount++;
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("只读查询完成: database={}, rows={}, chars={}, truncated={}, elapsedMs={}",
                databaseKey, rowCount, charCount, truncated, elapsed);
        return QueryResult.builder()
                .database(databaseKey)
                .sql(sql)
                .columns(columns)
                .columnRefs(columnRefs)
                .rows(rows)
                .rowCount(rowCount)
                .truncated(truncated)
                .tooLarge(false)
                .charCount((int) Math.min(charCount, Integer.MAX_VALUE))
                .elapsedMs(elapsed)
                .message(truncated ? "结果已按最大行数限制截断" : null)
                .build();
    }

    private void setReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException e) {
            log.warn("当前驱动不支持设置只读连接，将继续依赖 SqlGuard 进行安全校验: {}", safeMessage(e));
        }
    }

    private Object readValue(ResultSet resultSet, int index) throws SQLException {
        try {
            return resultSet.getObject(index);
        } catch (SQLException e) {
            return resultSet.getString(index);
        }
    }

    private String formatValue(Object value) throws SQLException {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return "[binary " + ((byte[]) value).length + " bytes]";
        }
        if (value instanceof Blob) {
            return "[blob " + ((Blob) value).length() + " bytes]";
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            long length = clob.length();
            int read = (int) Math.min(length, MAX_CLOB_CHARS);
            String content = clob.getSubString(1, read);
            return length > read ? content + "...(truncated)" : content;
        }
        if (value instanceof SQLXML) {
            return ((SQLXML) value).getString();
        }
        if (value instanceof Array) {
            Object array = ((Array) value).getArray();
            if (array instanceof Object[]) {
                return java.util.Arrays.deepToString((Object[]) array);
            }
            return String.valueOf(array);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return String.valueOf(value);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String uniqueLabel(String label, Set<String> usedLabels) {
        String safeLabel = label == null ? "column" : label.trim();
        if (safeLabel.isEmpty()) {
            safeLabel = "column";
        }
        String candidate = safeLabel;
        int suffix = 2;
        while (!usedLabels.add(candidate)) {
            candidate = safeLabel + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean isRecoverable(SQLException e) {
        String state = e.getSQLState();
        if (state != null) {
            // 08: 连接异常；40: 事务回滚/死锁；HYT00/HYT01: 超时；57014: PostgreSQL 取消查询
            if (state.startsWith("08") || state.startsWith("40")
                    || "HYT00".equals(state) || "HYT01".equals(state)
                    || "57014".equals(state)) {
                return true;
            }
        }
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Recover
    public QueryResult recover(RecoverableDataAccessException e, String databaseKey, String rawSql) {
        throw new SqlExecutionException("数据库查询重试后仍然失败: " + safeMessage(e), e);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
