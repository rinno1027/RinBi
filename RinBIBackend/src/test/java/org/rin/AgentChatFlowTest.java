package org.rin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rin.agent.AgentStatus;
import org.rin.auth.AuthService;
import org.rin.agent.ReasoningStreamClient;
import org.rin.agent.dto.ChatResponse;
import org.rin.agent.dto.ReasoningStreamResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用 Mock 模型跑通 Controller → AgentService → SqlGuard → H2 → 结果整理的完整链路，
 * 不依赖真实大模型服务。聊天接口以 SSE 返回，测试会等待异步流结束后再断言事件内容。
 */
@SpringBootTest(classes = AppStart.class)
@AutoConfigureMockMvc
class AgentChatFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @MockitoBean
    private StreamingChatModel chatModel;

    @MockitoBean
    private ReasoningStreamClient reasoningStreamClient;

    private String token;

    @BeforeEach
    void login() {
        // bootstrap 会创建默认管理员 admin/admin123，并挂载 H2 演示数据源 demo。
        token = authService.login("admin", "admin123").token();
    }

    private String authHeader() {
        return "Bearer " + token;
    }

    private void stubModel(String planJson, String formattedAnswer) {
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            List<ChatMessage> messages = request.messages();
            ChatMessage last = messages.get(messages.size() - 1);
            String prompt = last instanceof UserMessage ? ((UserMessage) last).singleText() : "";
            String answer = prompt.contains("查询结果总行数") ? formattedAnswer : planJson;
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(AiMessage.from(answer))
                    .build());
            return null;
        }).when(chatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        doAnswer(invocation -> {
            Consumer<String> onReasoning = invocation.getArgument(2);
            Consumer<String> onToken = invocation.getArgument(3);
            String reasoning = "先按地区统计客户数量，再按数量降序排列。";
            onReasoning.accept("先按地区统计客户数量");
            onReasoning.accept("，再按数量降序排列。");
            onToken.accept(formattedAnswer);
            return ReasoningStreamResult.builder()
                    .reasoning(reasoning)
                    .content(formattedAnswer)
                    .build();
        }).when(reasoningStreamClient).stream(anyString(), anyString(), any(), any());
    }

    /**
     * 执行请求并等待 SSE 异步流写完，返回完整的响应文本。
     */
    private String performChat(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", authHeader())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        return dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String eventData(String content, String eventName) {
        String marker = "event:" + eventName + "\n";
        int eventIndex = content.indexOf(marker);
        assertTrue(eventIndex >= 0, "未找到 SSE 事件 " + eventName + "，响应内容：" + content);
        int dataIndex = content.indexOf("data:", eventIndex + marker.length());
        assertTrue(dataIndex >= 0, "SSE 事件缺少 data：" + eventName);
        int start = dataIndex + "data:".length();
        int end = content.indexOf('\n', start);
        return end < 0 ? content.substring(start) : content.substring(start, end);
    }

    @Test
    void executesReadOnlyQueryAndFormatsAnswer() throws Exception {
        stubModel("{\"feasible\":true,\"database\":\"demo\","
                        + "\"sql\":\"SELECT region, COUNT(*) AS 客户数量 FROM customer "
                        + "GROUP BY region ORDER BY 客户数量 DESC\","
                        + "\"explanation\":\"按地区统计客户数\"}",
                "华东地区客户数量最多。");

        String content = performChat("{\"question\":\"每个地区有多少客户？\",\"conversationId\":\"flow-ok\"}");

        assertTrue(content.contains("event:meta"), "应包含 meta 事件：" + content);
        assertTrue(content.contains("event:reasoning"), "应包含 reasoning 事件：" + content);
        assertTrue(content.contains("event:done"), "应包含 done 事件：" + content);
        assertTrue(content.contains("event:token"), "答案应逐片推送：" + content);
        assertTrue(content.indexOf("event:token") < content.indexOf("event:done"),
                "token 事件应早于 done 事件：" + content);
        assertEquals("华东地区客户数量最多。",
                objectMapper.readValue(eventData(content, "token"), String.class));
        assertEquals("先按地区统计客户数量",
                objectMapper.readValue(eventData(content, "reasoning"), String.class));

        ChatResponse meta = objectMapper.readValue(eventData(content, "meta"), ChatResponse.class);
        assertEquals(AgentStatus.SUCCESS, meta.getStatus());
        assertEquals("demo", meta.getDatabase());
        assertEquals("flow-ok", meta.getConversationId());
        assertNull(meta.getAnswer());
        assertNull(meta.getReasoning());
        assertNull(meta.getSql(), "默认不返回 SQL，避免泄漏真实表名/字段名");
        assertEquals("所属地区", meta.getTable().getColumns().get(0), "列名应使用字段注释");

        ChatResponse done = objectMapper.readValue(eventData(content, "done"), ChatResponse.class);
        assertEquals(AgentStatus.SUCCESS, done.getStatus());
        assertEquals("demo", done.getDatabase());
        assertEquals("flow-ok", done.getConversationId());
        assertEquals("华东地区客户数量最多。", done.getAnswer());
        assertEquals("先按地区统计客户数量，再按数量降序排列。", done.getReasoning());
        assertEquals(Integer.valueOf(7), done.getRowCount());
        assertEquals("所属地区", done.getTable().getColumns().get(0), "列名应使用字段注释");
        assertEquals("客户数量", done.getTable().getColumns().get(1));
        assertNull(done.getSql(), "默认不返回 SQL，避免泄漏真实表名/字段名");
        assertEquals("华东", done.getTable().getRows().get(0).get(0));
        assertTrue(done.getElapsedMs() != null && done.getElapsedMs() >= 0);
    }

    @Test
    void blocksWriteSqlWithoutExecutingIt() throws Exception {
        stubModel("{\"feasible\":true,\"database\":\"demo\","
                        + "\"sql\":\"DELETE FROM customer\"}",
                "不应被调用");

        String content = performChat("{\"question\":\"删除所有客户\",\"conversationId\":\"flow-unsafe\"}");
        ChatResponse done = objectMapper.readValue(eventData(content, "done"), ChatResponse.class);

        assertEquals(AgentStatus.UNSAFE_SQL, done.getStatus());
        assertEquals("demo", done.getDatabase());
        assertNull(done.getTable());
        assertNull(done.getSql(), "默认不返回 SQL，避免泄漏真实表名/字段名");
    }

    @Test
    void returnsNotFeasibleWhenModelRejectsQuestion() throws Exception {
        stubModel("{\"feasible\":false,\"reason\":\"当前数据没有 customer.id 对应的员工信息\"}",
                "不应被调用");

        String content = performChat("{\"question\":\"员工工资是多少？\",\"conversationId\":\"flow-no\"}");
        ChatResponse done = objectMapper.readValue(eventData(content, "done"), ChatResponse.class);

        // 未指定 database 时也要用全部已授权数据源的注释清洗模型输出。
        assertEquals(AgentStatus.NOT_FEASIBLE, done.getStatus());
        assertEquals("当前数据没有 客户编号 对应的员工信息", done.getReason());
        assertEquals("当前数据没有 客户编号 对应的员工信息", done.getAnswer());
    }

    @Test
    void rejectsInvalidRequestAndClearsConversation() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(delete("/api/agent/conversations/flow-ok")
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());

        // 未登录访问受保护接口应返回 401
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"每个地区有多少客户？\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
