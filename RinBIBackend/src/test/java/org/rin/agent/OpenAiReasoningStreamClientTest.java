package org.rin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.rin.agent.dto.ReasoningStreamResult;
import org.rin.config.AgentProperties;
import org.springframework.util.StreamUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证基于 LangChain4j 的推理模型流式客户端：
 * reasoning_content 逐片回调到 onReasoning，content 逐片回调到 onToken 并在流结束后汇总。
 */
class OpenAiReasoningStreamClientTest {

    @Test
    void streamsReasoningAndContentThroughCallbacks() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeSse(exchange, validSse());
        });
        server.start();

        try {
            OpenAiReasoningStreamClient client = new OpenAiReasoningStreamClient(properties(server));

            List<String> reasoningChunks = new ArrayList<>();
            List<String> contentChunks = new ArrayList<>();

            ReasoningStreamResult result = client.stream("system", "user",
                    reasoningChunks::add, contentChunks::add);

            assertEquals("先分析数据。", result.getReasoning());
            assertEquals("最终答案", result.getContent());
            assertEquals(List.of("先分析", "数据。"), reasoningChunks);
            assertEquals(List.of("最终", "答案"), contentChunks);
            // 请求体可能被 JSON 序列化器美化（字段间带空格），因此解析后再断言。
            JsonNode json = new ObjectMapper().readTree(requestBody.get());
            assertEquals("deepseek-reasoner", json.path("model").asText(),
                    "请求体应使用推理模型：" + requestBody.get());
            assertTrue(json.path("stream").asBoolean(),
                    "请求必须是流式的：" + requestBody.get());
            assertEquals("high", json.path("reasoning_effort").asText(),
                    "应透传 reasoning_effort：" + requestBody.get());
            assertEquals("enabled", json.path("thinking").path("type").asText(),
                    "应下发生思考模式开关 thinking.type：" + requestBody.get());
            assertEquals("Bearer test-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenModelReturnsNoContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8);
            writeSse(exchange, "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"只有思考\"}}]}\n\n"
                    + "data: [DONE]\n\n");
        });
        server.start();

        try {
            OpenAiReasoningStreamClient client = new OpenAiReasoningStreamClient(properties(server));
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.stream("system", "user", chunk -> { }, chunk -> { }));
            assertTrue(error.getMessage().contains("为空"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 模拟 OpenAI 兼容服务的流式响应，思考内容走 reasoning_content。
     */
    private String validSse() {
        return "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"数据。\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"最终\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private void writeSse(com.sun.net.httpserver.HttpExchange exchange, String sse) throws java.io.IOException {
        byte[] body = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private AgentProperties properties(HttpServer server) {
        AgentProperties properties = new AgentProperties();
        properties.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLlm().setApiKey("test-key");
        properties.getLlm().setModelName("deepseek-chat");
        properties.getLlm().setReasoningModelName("deepseek-reasoner");
        properties.getLlm().setReasoningEffort("high");
        properties.getLlm().setThinkingType("enabled");
        properties.getLlm().setTimeoutSeconds(5);
        properties.getLlm().setMaxTokens(100);
        return properties;
    }
}
