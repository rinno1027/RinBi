package org.rin;

import org.junit.jupiter.api.Test;
import org.rin.agent.AgentController;
import org.rin.auth.AuthUser;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.db.DatabaseRegistry;
import org.rin.db.SchemaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动完整 Spring 上下文，验证内置演示库、schema 读取和接口装配。
 */
@SpringBootTest(classes = AppStart.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentContextTest {

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private AgentController agentController;

    @Autowired
    private SysUserMapper userMapper;

    private AuthUser admin() {
        SysUser user = userMapper.selectByUsername("admin");
        assertNotNull(user, "bootstrap 应创建默认管理员 admin");
        return AuthUser.from(user);
    }

    @Test
    void loadsDemoDatabaseAndSchema() {
        AuthUser admin = admin();
        // 开发库可能还配置了其他数据源，这里只验证 bootstrap 演示数据源已授权。
        assertTrue(databaseRegistry.getKeys(admin.id()).contains("demo"));
        assertTrue(agentController.databases(admin).stream()
                .anyMatch(database -> "demo".equals(database.getKey())));

        String schema = schemaService.getSchema(admin.id(), "demo");
        assertTrue(schema.contains("customer"));
        assertTrue(schema.contains("customer_name"));
        assertTrue(schema.contains("sales_order"));
        assertTrue(schema.contains("amount"));
        assertTrue(schema.contains("客户名称"), "schema 应包含字段注释");

        org.rin.db.SchemaMetadata metadata = schemaService.getMetadata(admin.id(), "demo");
        assertEquals("所属地区", metadata.columnComment("customer", "region"));
        assertEquals("订单金额", metadata.columnComment("sales_order", "amount"));
        assertEquals("客户名称", metadata.columnComment("customer", "customer_name"));
    }

    @Test
    void healthEndpointReportsUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(org.rin.auth.AuthService.AUTH_USER_ATTRIBUTE, admin());
        assertEquals("UP", agentController.health(request).get("status"));
    }
}
