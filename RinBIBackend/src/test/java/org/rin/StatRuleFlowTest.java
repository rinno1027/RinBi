package org.rin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rin.agent.AgentService;
import org.rin.agent.dto.ChatRequest;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.entity.UserDataSource;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.db.mapper.UserDataSourceMapper;
import org.rin.exception.BadRequestException;
import org.rin.rule.StatRuleService;
import org.rin.rule.dto.StatRuleInputFieldRequest;
import org.rin.rule.dto.StatRuleRequest;
import org.rin.rule.entity.StatRule;
import org.rin.rule.mapper.StatRuleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计规则集成测试：CRUD、管理员维护权限、对话规则选项与规划提示词注入。
 *
 * <p>bootstrap 会创建管理员 admin 与 H2 演示数据源 demo，并把 demo 授权给管理员。
 * 测试创建的规则和临时用户在结束后清理，避免影响其他用例。</p>
 */
@SpringBootTest(classes = AppStart.class)
@AutoConfigureMockMvc
class StatRuleFlowTest {

    private static final String DEMO_KEY = "demo";

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

    @Autowired
    private StatRuleMapper statRuleMapper;

    @Autowired
    private StatRuleService statRuleService;

    @Autowired
    private AgentService agentService;

    private final List<String> ruleCodes = new ArrayList<>();
    private String adminToken;
    private Long adminId;
    private AuthUser adminUser;
    private Long tempUserId;

    @BeforeEach
    void login() {
        AuthService.LoginResult result = authService.login("admin", "admin123");
        adminToken = result.token();
        adminId = result.user().id();
        adminUser = result.user();
    }

    @AfterEach
    void cleanUp() {
        for (String ruleCode : ruleCodes) {
            statRuleMapper.delete(new LambdaQueryWrapper<StatRule>()
                    .eq(StatRule::getRuleCode, ruleCode));
        }
        ruleCodes.clear();
        if (tempUserId != null) {
            userDataSourceMapper.delete(new LambdaQueryWrapper<UserDataSource>()
                    .eq(UserDataSource::getUserId, tempUserId));
            authService.revokeUserSessions(tempUserId);
            userMapper.deleteById(tempUserId);
            tempUserId = null;
        }
    }

    @Test
    void createsListsAndDeletesRuleWithInputFields() throws Exception {
        String ruleCode = uniqueCode("CRUD");
        Map<String, Object> body = ruleBody(ruleCode, "按地区统计客户数", List.of(
                field("customer", "region", "所属地区"),
                field("customer", "id", "客户编号")));

        MvcResult created = mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value(ruleCode))
                .andExpect(jsonPath("$.ruleName").value("按地区统计客户数"))
                .andExpect(jsonPath("$.dataSourceKey").value(DEMO_KEY))
                .andExpect(jsonPath("$.dataSourceName").value("演示数据库"))
                .andExpect(jsonPath("$.versionNo").isNotEmpty())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.createdByName").value("admin"))
                .andExpect(jsonPath("$.inputFields.length()").value(2))
                .andExpect(jsonPath("$.inputFields[0].sourceTable").value("customer"))
                .andExpect(jsonPath("$.inputFields[0].fieldName").value("region"))
                .andExpect(jsonPath("$.inputFields[1].fieldName").value("id"))
                .andReturn();
        JsonNode createdNode = objectMapper.readTree(created.getResponse().getContentAsString());
        long ruleId = createdNode.path("id").asLong();
        ruleCodes.add(ruleCode);
        assertTrue(ruleId > 0);

        // 列表只返回规则头信息，输入字段在详情接口加载。
        mockMvc.perform(get("/api/rules")
                        .header("Authorization", bearer(adminToken))
                        .param("dataSourceKey", DEMO_KEY)
                        .param("keyword", ruleCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ruleId))
                .andExpect(jsonPath("$.items[0].inputFields.length()").value(0));

        // 对话页候选项按数据源过滤，包含刚创建的规则。
        MvcResult options = mockMvc.perform(get("/api/rules/options")
                        .header("Authorization", bearer(adminToken))
                        .param("dataSourceKey", DEMO_KEY))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode optionArray = objectMapper.readTree(options.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode option : optionArray) {
            if (ruleCode.equals(option.path("ruleCode").asText())) {
                found = true;
                assertEquals(ruleId, option.path("id").asLong());
                break;
            }
        }
        assertTrue(found, "规则选项应包含刚创建的规则");

        mockMvc.perform(get("/api/rules/" + ruleId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputFields.length()").value(2))
                .andExpect(jsonPath("$.inputFields[0].fieldDescription").value("所属地区"));

        // 规则代码全局唯一。
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("规则代码已存在")));

        // 输入字段重复会被拒绝。
        Map<String, Object> duplicateFieldBody = ruleBody(uniqueCode("DUP"), "重复字段规则", List.of(
                field("customer", "region", "所属地区"),
                field("CUSTOMER", "REGION", "重复地区")));
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateFieldBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("输入字段重复")));

        mockMvc.perform(delete("/api/rules/" + ruleId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/rules/" + ruleId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsSharedReadButOnlyAdminCanModify() throws Exception {
        // 创建并审批一个普通用户，再授予 demo 数据源权限。
        String username = "rule_" + System.nanoTime();
        String password = "Test1234!";
        SysUser other = authService.register(username, password, "规则访客");
        tempUserId = other.getId();
        mockMvc.perform(post("/api/admin/users/" + tempUserId + "/approve")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        DataSourceConfig demo = dataSourceConfigMapper.selectByDbKey(DEMO_KEY);
        assertNotNull(demo, "bootstrap 应创建 demo 数据源");
        UserDataSource grant = new UserDataSource();
        grant.setUserId(tempUserId);
        grant.setDataSourceId(demo.getId());
        userDataSourceMapper.insert(grant);
        String otherToken = authService.login(username, password).token();

        // 普通用户即使有数据源权限也不能新增规则。
        String deniedCode = uniqueCode("DENY");
        ruleCodes.add(deniedCode);
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ruleBody(
                                deniedCode, "普通用户规则", List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("仅管理员可以维护统计规则")));

        String ruleCode = uniqueCode("ADMIN");
        Map<String, Object> createBody = ruleBody(ruleCode, "管理员权限规则", List.of(
                field("customer", "region", "所属地区")));
        MvcResult created = mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdNode = objectMapper.readTree(created.getResponse().getContentAsString());
        long ruleId = createdNode.path("id").asLong();
        String originalVersion = createdNode.path("versionNo").asText();
        ruleCodes.add(ruleCode);

        // 有数据源权限的其他用户可以看到规则，但不能修改/删除。
        mockMvc.perform(get("/api/rules")
                        .header("Authorization", bearer(otherToken))
                        .param("keyword", ruleCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ruleId))
                .andExpect(jsonPath("$.items[0].canEdit").value(false));

        mockMvc.perform(get("/api/rules/" + ruleId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.createdByName").value("admin"));

        Map<String, Object> hijackBody = ruleBody(ruleCode, "被篡改的规则名", List.of(
                field("customer", "id", "客户编号")));
        mockMvc.perform(put("/api/rules/" + ruleId)
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("仅管理员可以维护统计规则")));

        mockMvc.perform(delete("/api/rules/" + ruleId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("仅管理员可以维护统计规则")));

        // 管理员可以更新规则，字段被整体替换且版本号推进。
        Map<String, Object> updateBody = ruleBody(ruleCode, "管理员权限规则（已更新）", List.of(
                field("customer", "id", "客户编号"),
                field("customer", "name", "客户名称")));
        updateBody.put("formula", "COUNT(DISTINCT customer.id)");
        mockMvc.perform(put("/api/rules/" + ruleId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("管理员权限规则（已更新）"))
                .andExpect(jsonPath("$.formula").value("COUNT(DISTINCT customer.id)"))
                .andExpect(jsonPath("$.updatedByName").value("admin"))
                .andExpect(jsonPath("$.inputFields.length()").value(2))
                .andExpect(jsonPath("$.inputFields[1].fieldName").value("name"))
                .andExpect(jsonPath("$.versionNo").value(org.hamcrest.Matchers.not(originalVersion)));

        mockMvc.perform(delete("/api/rules/" + ruleId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void injectsSelectedRulesIntoPlanningPromptInput() {
        StatRuleRequest request = new StatRuleRequest();
        request.setDataSourceKey(DEMO_KEY);
        request.setRuleCode(uniqueCode("PROMPT"));
        request.setRuleName("提示词注入规则");
        request.setRuleDescription("按地区统计客户数量");
        request.setFormula("COUNT(customer.id)");
        request.setBusinessRule("只统计有效客户");
        request.setRemark("仅用于规划提示词");
        StatRuleInputFieldRequest inputField = new StatRuleInputFieldRequest();
        inputField.setSourceTable("customer");
        inputField.setFieldName("region");
        inputField.setFieldDescription("所属地区");
        request.setInputFields(List.of(inputField));

        org.rin.rule.dto.StatRuleView created = statRuleService.createRule(adminUser, request);
        ruleCodes.add(request.getRuleCode());
        assertTrue(created.getId() > 0);

        // 未勾选规则时不注入任何内容。
        assertEquals("", statRuleService.buildPromptJson(adminId, DEMO_KEY, List.of()));

        String promptJson = statRuleService.buildPromptJson(
                adminId, DEMO_KEY, List.of(created.getId()));
        assertTrue(promptJson.contains(request.getRuleCode()));
        assertTrue(promptJson.contains("提示词注入规则"));
        assertTrue(promptJson.contains("COUNT(customer.id)"));
        assertTrue(promptJson.contains("\"fieldName\":\"region\""));
        assertTrue(promptJson.contains(created.getVersionNo()));
        assertFalse(promptJson.contains("createdBy"), "提示词不应携带内部审计字段");

        ChatRequest chat = new ChatRequest();
        chat.setQuestion("每个地区有多少客户？");
        chat.setDatabase(DEMO_KEY);
        chat.setRuleIds(List.of(created.getId(), created.getId()));
        ChatRequest prepared = agentService.prepare(adminId, chat);
        assertEquals(promptJson, prepared.getRulePromptJson());
        assertEquals(List.of(created.getId()), prepared.getRuleIds(),
                "重复的规则 ID 应去重后按顺序保留");

        chat.setRuleIds(List.of(created.getId()));
        chat.setDatabase(null);
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> agentService.prepare(adminId, chat));
        assertTrue(error.getMessage().contains("未指定数据源时不能使用统计规则"));
    }

    private String uniqueCode(String prefix) {
        return "IT_" + prefix + "_" + System.nanoTime();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Map<String, Object> field(String sourceTable, String fieldName, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("sourceTable", sourceTable);
        field.put("fieldName", fieldName);
        field.put("fieldDescription", description);
        return field;
    }

    private Map<String, Object> ruleBody(String ruleCode, String ruleName,
                                         List<Map<String, Object>> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataSourceKey", DEMO_KEY);
        body.put("ruleCode", ruleCode);
        body.put("ruleName", ruleName);
        body.put("ruleDescription", "按地区统计客户数量");
        body.put("formula", "COUNT(customer.id)");
        body.put("businessRule", "只统计有效客户，按所属地区分组");
        body.put("remark", "集成测试规则");
        body.put("inputFields", fields);
        return body;
    }
}
