package org.rin.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证普通流式模型（@Primary）的请求体：
 * 配置 thinking-type / reasoning-effort 后才下发对应字段，未配置时保持请求体干净。
 */
class LlmConfigTest {

    @Test
    void primaryModelSendsThinkingAndReasoningEffort() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody);
        try {
            AgentProperties properties = properties(server);
            properties.getLlm().setThinkingType("enabled");
            properties.getLlm().setReasoningEffort("high");

            chat(new LlmConfig().openAiStreamingChatModel(properties));

            JsonNode json = new ObjectMapper().readTree(requestBody.get());
            assertEquals("enabled", json.path("thinking").path("type").asText(),
                    "请求体应包含 thinking.type：" + requestBody.get());
            assertEquals("high", json.path("reasoning_effort").asText(),
                    "请求体应包含 reasoning_effort：" + requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void primaryModelOmitsThinkingWhenNotConfigured() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody);
        try {
            chat(new LlmConfig().openAiStreamingChatModel(properties(server)));

            JsonNode json = new ObjectMapper().readTree(requestBody.get());
            assertFalse(json.has("thinking"), "未配置时不应下发 thinking：" + requestBody.get());
            assertFalse(json.has("reasoning_effort"),
                    "未配置时不应下发 reasoning_effort：" + requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 发起一次流式对话并等待结束，仅用于捕获请求体。
     */
    private void chat(StreamingChatModel model) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        model.chat(ChatRequest.builder().messages(UserMessage.from("你好")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        completed.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.compareAndSet(null, error);
                        completed.countDown();
                    }
                });
        assertTrue(completed.await(10, TimeUnit.SECONDS), "模型调用超时");
        assertNull(failure.get(), () -> "模型调用失败：" + failure.get());
    }

    private HttpServer startServer(AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
            writeSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                    + "data: [DONE]\n\n");
        });
        server.start();
        return server;
    }

    private void writeSse(HttpExchange exchange, String sse) throws java.io.IOException {
        byte[] body = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private AgentProperties properties(HttpServer server) {
        AgentProperties properties = new AgentProperties();
        AgentProperties.Llm llm = properties.getLlm();
        llm.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        llm.setApiKey("test-key");
        llm.setModelName("deepseek-flash");
        llm.setTemperature(0.2D);
        llm.setMaxTokens(100);
        llm.setTimeoutSeconds(5);
        llm.setReturnThinking(true);
        return properties;
    }
}
