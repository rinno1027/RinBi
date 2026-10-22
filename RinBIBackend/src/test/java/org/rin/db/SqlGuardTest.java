package org.rin.db;

import org.junit.jupiter.api.Test;
import org.rin.config.AgentProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlGuardTest {

    private final SqlGuard sqlGuard = newSqlGuard();

    /**
     * 单元测试直接 new 组件，不会经过 Spring 依赖注入，需要显式给出 SQL 限制。
     */
    private static SqlGuard newSqlGuard() {
        SqlGuard guard = new SqlGuard();
        AgentProperties properties = new AgentProperties();
        properties.getSql().setMaxSqlLength(20000);
        ReflectionTestUtils.setField(guard, "properties", properties);
        return guard;
    }

    @Test
    void acceptsReadOnlyQueries() {
        assertAccepted("SELECT id, name FROM customer");
        assertAccepted("SELECT * FROM sales_order WHERE amount > 100 LIMIT 10");
        assertAccepted("SELECT COUNT(*) AS total FROM sales_order");
        assertAccepted("WITH recent AS (SELECT * FROM sales_order) SELECT * FROM recent");
        assertAccepted("SELECT * FROM customer UNION SELECT * FROM customer");
        assertAccepted("SELECT * FROM customer WHERE id IN (SELECT customer_id FROM sales_order)");
        assertAccepted("SELECT REPLACE(name, 'a', 'b') FROM customer");
        assertAccepted("SELECT update_time, created_at FROM sales_order");
        assertAccepted("SELECT * FROM customer WHERE name = 'delete'");
        assertAccepted("SELECT * FROM customer /* drop table customer */");
        assertAccepted("SELECT * FROM customer -- delete all\n");
        assertAccepted("SELECT `delete` FROM customer");
        assertAccepted("SELECT * FROM customer;");
        assertAccepted("SELECT * FROM customer;;;");
        assertAccepted("SELECT 1");
        assertAccepted("SELECT * FROM customer WHERE name = 'it''s delete'");
    }

    @Test
    void rejectsWriteAndManagementStatements() {
        assertRejected("INSERT INTO customer(id) VALUES (1)");
        assertRejected("UPDATE customer SET name = 'x'");
        assertRejected("DELETE FROM customer");
        assertRejected("DROP TABLE customer");
        assertRejected("ALTER TABLE customer ADD COLUMN x INT");
        assertRejected("CREATE TABLE x(id INT)");
        assertRejected("TRUNCATE TABLE customer");
        assertRejected("GRANT SELECT ON customer TO someone");
        assertRejected("CALL some_procedure()");
        assertRejected("SET @x = 1");
        assertRejected("EXPLAIN SELECT * FROM customer");
    }

    @Test
    void rejectsMultipleStatements() {
        assertRejected("SELECT * FROM customer; DROP TABLE customer");
        assertRejected("SELECT * FROM customer; SELECT * FROM sales_order");
    }

    @Test
    void rejectsSelectIntoAndLocking() {
        assertRejected("SELECT * INTO new_table FROM customer");
        assertRejected("SELECT * FROM customer FOR UPDATE");
        assertRejected("SELECT * FROM customer FOR SHARE");
        assertRejected("SELECT * FROM customer INTO OUTFILE '/tmp/a.txt'");
    }

    @Test
    void rejectsDangerousFunctions() {
        assertRejected("SELECT SLEEP(10)");
        assertRejected("SELECT LOAD_FILE('/etc/passwd')");
        assertRejected("SELECT pg_sleep(10)");
        assertRejected("SELECT DBMS_LOCK.SLEEP(10)");
    }

    @Test
    void rejectsMalformedOrEmptySql() {
        assertRejected("");
        assertRejected("   ");
        assertRejected("not sql at all");
        assertRejected("SELECT");
    }

    @Test
    void removesTrailingSemicolonOnly() {
        String sql = sqlGuard.validate("  SELECT 1;  ");
        assertEquals("SELECT 1", sql);
    }

    @Test
    void rejectsTooLongSql() {
        StringBuilder sql = new StringBuilder("SELECT 1 ");
        for (int i = 0; i < 20001; i++) {
            sql.append('x');
        }
        UnsafeSqlException exception = assertThrows(UnsafeSqlException.class,
                () -> sqlGuard.validate(sql.toString()));
        assertTrue(exception.getMessage().contains("长度超过限制"));
    }

    private void assertAccepted(String sql) {
        String validated = sqlGuard.validate(sql);
        assertTrue(validated.length() > 0);
    }

    private void assertRejected(String sql) {
        assertThrows(UnsafeSqlException.class, () -> sqlGuard.validate(sql));
    }
}
