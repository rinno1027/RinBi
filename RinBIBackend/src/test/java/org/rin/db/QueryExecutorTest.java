package org.rin.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rin.config.AgentProperties;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}

