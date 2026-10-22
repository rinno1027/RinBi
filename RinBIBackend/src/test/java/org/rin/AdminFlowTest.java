package org.rin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rin.auth.AuthService;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.db.mapper.UserDataSourceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员流程集成测试：注册申请、审批、数据源配置、权限校验与禁用后会话失效。
 *
 * <p>测试结束后删除创建的临时用户，数据源通过外键级联删除。</p>
 */
@SpringBootTest(classes = AppStart.class)
@AutoConfigureMockMvc
class AdminFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private DataSourceConfigMapper dataSourceConfigMapper;

    @Autowired
    private UserDataSourceMapper userDataSourceMapper;

    private String username;
    private String dataSourceKey;
    private Long userId;

    @AfterEach
    void cleanUp() {
        if (dataSourceKey != null) {
            DataSourceConfig source = dataSourceConfigMapper.selectByDbKey(dataSourceKey);
            if (source != null) {
                userDataSourceMapper.deleteByDataSource(source.getId());
                dataSourceConfigMapper.deleteById(source.getId());
            }
            dataSourceKey = null;
        }
        if (userId != null) {
            authService.revokeUserSessions(userId);
            userMapper.deleteById(userId);
            userId = null;
        }
    }

    @Test
    void registrationApprovalAndDataSourceConfigFlow() throws Exception {
        username = "it_" + System.nanoTime();
        dataSourceKey = "it_source_" + System.nanoTime();
        String password = "Test1234!";
        Map<String, Object> registerBody = new LinkedHashMap<>();
        registerBody.put("username", username);
        registerBody.put("password", password);
        registerBody.put("displayName", "集成测试用户");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(2))
                .andExpect(jsonPath("$.message", containsString("审批")));

        SysUser pending = userMapper.selectByUsername(username);
        assertNotNull(pending, "注册后应能查询到待审批用户");
        userId = pending.getId();

        // 重复注册被拒绝。
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isBadRequest());

        // 待审批账号即使密码正确也不能登录。
        login(username, password).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("审批")));

        String adminToken = authService.login("admin", "admin123").token();

        MvcResult userListResult = mockMvc.perform(get("/api/admin/users?page=1&size=10")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andReturn();
        JsonNode userList = objectMapper.readTree(
                userListResult.getResponse().getContentAsString()).path("items");
        JsonNode mine = null;
        for (JsonNode item : userList) {
            if (username.equals(item.path("username").asText())) {
                mine = item;
                break;
            }
        }
        assertNotNull(mine, "管理员用户列表应包含刚注册的账号");
        assertEquals(2, mine.path("status").asInt());
        assertEquals("待审批", mine.path("statusText").asText());

        // 未登录不能访问管理员接口。
        mockMvc.perform(post("/api/admin/users/" + userId + "/approve")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/users/" + userId + "/approve")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.statusText").value("正常"));

        MvcResult userLogin = login(username, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();
        String userToken = objectMapper.readTree(
                userLogin.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("dbKey", dataSourceKey);
        dataSource.put("name", "集成测试数据源");
        dataSource.put("description", "H2 内存库");
        dataSource.put("dbType", "h2");
        dataSource.put("jdbcUrl", "jdbc:h2:mem:it_flow;DB_CLOSE_DELAY=-1");
        dataSource.put("username", "sa");
        dataSource.put("password", "");
        dataSource.put("dialect", "h2");
        dataSource.put("readOnly", true);
        dataSource.put("enabled", true);
        dataSource.put("maxPoolSize", 2);
        dataSource.put("connectionTimeoutMillis", 5000);

        MvcResult created = mockMvc.perform(post("/api/admin/datasources")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dataSource)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbKey").value(dataSourceKey))
                .andExpect(jsonPath("$.passwordConfigured").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        JsonNode createdNode = objectMapper.readTree(created.getResponse().getContentAsString());
        long dataSourceId = createdNode.get("id").asLong();

        // 授权给刚审批通过的普通用户。
        Map<String, Object> accessBody = new LinkedHashMap<>();
        accessBody.put("userIds", List.of(userId));
        mockMvc.perform(put("/api/admin/datasources/" + dataSourceId + "/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accessBody)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/agent/databases")
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value(dataSourceKey));

        dataSource.put("name", "集成测试数据源（已更新）");
        dataSource.put("password", null);
        mockMvc.perform(put("/api/admin/datasources/" + dataSourceId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dataSource)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("集成测试数据源（已更新）"))
                .andExpect(jsonPath("$.passwordConfigured").value(true));

        mockMvc.perform(get("/api/admin/datasources?page=1&size=10&keyword=" + dataSourceKey)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dbKey").value(dataSourceKey));

        mockMvc.perform(delete("/api/admin/datasources/" + dataSourceId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/agent/databases")
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 禁用后立即吊销在线 token，后续请求返回 401。
        mockMvc.perform(post("/api/admin/users/" + userId + "/disable")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isUnauthorized());
        login(username, password).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("禁用")));
    }

    private org.springframework.test.web.servlet.ResultActions login(String name, String password)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", name);
        body.put("password", password);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
