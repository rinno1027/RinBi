package org.rin.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rin.config.AgentProperties;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryExecutorTest {

    private QueryExecutor queryExecutor;
    private AgentProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:query-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE demo_customer(id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))");
            statement.execute("INSERT INTO demo_customer VALUES (1, 'Alice', 12.50)");
            statement.execute("INSERT INTO demo_customer VALUES (2, 'Bob', 20.00)");
            statement.execute("INSERT INTO demo_customer VALUES (3, NULL, 30.00)");
        }

        DataSourceDefinition definition = DataSourceDefinition.builder()
                .id(1L)
                .userId(1L)
                .key("demo")
                .build();
        DatabaseRegistry registry = mock(DatabaseRegistry.class);
        when(registry.requireOwned(1L, "demo")).thenReturn(definition);
        when(registry.getDataSource(definition)).thenReturn((DataSource) dataSource);
        properties = new AgentProperties();
        // 单元测试直接 new 配置对象，不会经过 Spring 绑定，需要显式给出 SQL 限制默认值。
        properties.getSql().setMaxRows(500);
        properties.getSql().setMaxResultChars(30000);
        properties.getSql().setQueryTimeoutSeconds(30);
        properties.getSql().setMaxSqlLength(20000);
        queryExecutor = new QueryExecutor();
        SqlGuard sqlGuard = new SqlGuard();
        ReflectionTestUtils.setField(sqlGuard, "properties", properties);
        ReflectionTestUtils.setField(queryExecutor, "databaseRegistry", registry);
        ReflectionTestUtils.setField(queryExecutor, "properties", properties);
        ReflectionTestUtils.setField(queryExecutor, "sqlGuard", sqlGuard);
    }

    @Test
    void executesSelectAndFormatsValues() {
        QueryResult result = queryExecutor.execute(1L, "demo",
                "SELECT id, name, amount FROM demo_customer ORDER BY id");

        assertFalse(result.isTooLarge());
        assertFalse(result.isTruncated());
        assertEquals(3, result.getRowCount());
        assertEquals(3, result.getColumns().size());
        assertEquals("1", result.getRows().get(0).get(0));
        assertEquals("Alice", result.getRows().get(0).get(1));
        assertEquals("12.50", result.getRows().get(0).get(2));
        assertEquals("", result.getRows().get(2).get(1));
    }

    @Test
    void truncatesWhenRowLimitIsReached() {
        properties.getSql().setMaxRows(2);

        QueryResult result = queryExecutor.execute(1L, "demo",
                "SELECT id FROM demo_customer ORDER BY id");

        assertEquals(2, result.getRowCount());
        assertTrue(result.isTruncated());
        assertTrue(result.getMessage().contains("截断"));
    }

    @Test
    void rejectsWhenCharacterLimitIsExceeded() {
        properties.getSql().setMaxResultChars(10);

        QueryResult result = queryExecutor.execute(1L, "demo",
                "SELECT name FROM demo_customer ORDER BY id");

        assertTrue(result.isTooLarge());
        assertEquals(0, result.getRows().size());
        assertEquals(1, result.getRowCount());
        assertTrue(result.getMessage().contains("字符上限"));
    }

    @Test
    void makesDuplicateLabelsUnique() {
        QueryResult result = queryExecutor.execute(1L, "demo",
                "SELECT id, id FROM demo_customer WHERE id = 1");

        assertEquals(2, result.getColumns().size());
        assertTrue(result.getColumns().get(0).equalsIgnoreCase("id"));
        assertTrue(result.getColumns().get(1).toLowerCase().startsWith("id_"));
    }

    @Test
    void rejectsUnsafeSql() {
        assertThrows(UnsafeSqlException.class, () -> queryExecutor.execute(1L, "demo",
                "UPDATE demo_customer SET name = 'x'"));
    }

    @Test
    void wrapsSqlErrors() {
        assertThrows(SqlExecutionException.class, () -> queryExecutor.execute(1L, "demo",
                "SELECT * FROM table_that_does_not_exist"));
    }

    /**
     * 回归保护：Spring Retry 只有在 @Recover 方法参数为「异常 + 原方法全部参数」时才能匹配到恢复方法，
     * 否则重试耗尽后会抛 ExhaustedRetryException("Cannot locate recovery method")，掩盖真实数据库异常。
     */
    @Test
    void recoverMethodSignatureMatchesRetryableMethod() throws Exception {
        Method execute = QueryExecutor.class.getMethod("execute", Long.class, String.class, String.class);
        Retryable retryable = execute.getAnnotation(Retryable.class);
        assertNotNull(retryable, "execute 必须标注 @Retryable");

        Method recover = Arrays.stream(QueryExecutor.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Recover.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 @Recover 恢复方法"));

        Class<?>[] recoverParameters = recover.getParameterTypes();
        assertEquals(1 + execute.getParameterCount(), recoverParameters.length,
                "@Recover 参数个数必须是 1(异常) + 原方法参数个数");
        assertTrue(Arrays.asList(retryable.include()).contains(recoverParameters[0]),
                "@Recover 第一个参数必须是 @Retryable.include 中声明的异常类型");
        assertEquals(Arrays.asList(execute.getParameterTypes()),
                Arrays.asList(Arrays.copyOfRange(recoverParameters, 1, recoverParameters.length)),
                "@Recover 其余参数必须与原方法参数类型、顺序完全一致");
    }

    @Test
    void recoverWrapsRetryExhaustionAndKeepsCause() {
        SQLException databaseError = new SQLException("connection reset", "08S01");
        RecoverableDataAccessException failure =
                new RecoverableDataAccessException("数据库连接/查询瞬时失败: connection reset", databaseError);

        SqlExecutionException thrown = assertThrows(SqlExecutionException.class,
                () -> queryExecutor.recover(failure, 1L, "demo", "SELECT 1"));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains("重试后仍然失败"));
    }
}

