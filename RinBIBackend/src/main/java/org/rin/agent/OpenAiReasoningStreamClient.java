package org.rin.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.rin.agent.dto.ReasoningStreamResult;
import org.rin.config.AgentProperties;
import org.rin.config.OpenAiChatModelOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的推理模型流式客户端。
 *
 * <p>完全基于 LangChain4j 的 {@link OpenAiStreamingChatModel} 实现，不再手工维护
 * HTTP/SSE 连接：</p>
 * <ul>
 *     <li>{@code delta.reasoning_content} 由 LangChain4j 解析为
 *         {@link PartialThinking}，通过 {@code onPartialThinking} 实时回调；</li>
 *     <li>{@code delta.content} 通过 {@code onPartialResponse} 实时回调（可下发前端）；</li>
 *     <li>流结束后返回完整思考文本与完整答案。</li>
 * </ul>
 *
 * <p>{@code agent.llm.base-url} 应为服务根地址或包含版本前缀的地址，例如
 * {@code https://api.deepseek.com} 或 {@code https://api.openai.com/v1}；
 * LangChain4j 会自动追加 {@code /chat/completions}。</p>
 */
@Component
public class OpenAiReasoningStreamClient implements ReasoningStreamClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiReasoningStreamClient.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final AgentProperties properties;
    private final StreamingChatModel reasoningChatModel;

    public OpenAiReasoningStreamClient(AgentProperties properties) {
        this.properties = properties;
        this.reasoningChatModel = buildReasoningChatModel(properties);
    }


    @Override
    public ReasoningStreamResult stream(String systemPrompt, String userPrompt,
                                        Consumer<String> onReasoning, Consumer<String> onToken) {
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build();

        try {
            reasoningChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse == null || partialResponse.isEmpty()) {
                        return;
                    }
                    content.append(partialResponse);
                    notifyChunk(onToken, partialResponse);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    String chunk = partialThinking == null ? null : partialThinking.text();
                    if (!StringUtils.hasText(chunk)) {
                        return;
                    }
                    reasoning.append(chunk);
                    notifyChunk(onReasoning, chunk);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    completed.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    failure.compareAndSet(null, error);
                    completed.countDown();
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("调用推理模型失败: " + safeMessage(e), e);
        }

        awaitCompletion(completed);

        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException("调用推理模型失败: " + safeMessage(error), error);
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("推理模型返回为空");
        }
        log.debug("推理模型流式输出完成：思考 {} 字符，答案 {} 字符",
                reasoning.length(), content.length());
        return ReasoningStreamResult.builder()
                .reasoning(reasoning.toString())
                .content(content.toString())
                .build();
    }

    /**
     * 读取配置构建推理模型：模型名留空时回退到 {@code agent.llm.model-name}。
     */
    private StreamingChatModel buildReasoningChatModel(AgentProperties properties) {
        AgentProperties.Llm llm = properties.getLlm();
        if (llm == null) {
            throw new IllegalStateException("缺少 agent.llm 配置");
        }
        if (!StringUtils.hasText(llm.getBaseUrl())) {
            throw new IllegalStateException("缺少 agent.llm.base-url 配置");
        }
        String modelName = firstNonBlank(llm.getReasoningModelName(), llm.getModelName());
        if (!StringUtils.hasText(modelName)) {
            throw new IllegalStateException("缺少 agent.llm.reasoning-model-name 或 model-name 配置");
        }

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .modelName(modelName)
                .baseUrl(llm.getBaseUrl().trim())
                .apiKey(llm.getApiKey())
                .logRequests(Boolean.TRUE.equals(llm.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(llm.getLogResponses()))
                .maxTokens(llm.getMaxTokens())
                .temperature(llm.getTemperature())
                .topP(0.8D)
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm.getTimeoutSeconds())))
                // 关键：开启思考内容回传，LangChain4j 才会把 reasoning_content 交给 onPartialThinking；
                // 它只影响响应解析，请求体中的 thinking.type 由 applyReasoningOptions 写入。
                .returnThinking(true);
        OpenAiChatModelOptions.applyReasoningOptions(builder, llm);
        return builder.build();
    }

    /**
     * 阻塞等待流结束；超时或线程被中断时抛出异常，避免请求线程永久挂起。
     */
    private void awaitCompletion(CountDownLatch completed) {
        int timeoutSeconds = resolveTimeoutSeconds(properties.getLlm().getTimeoutSeconds());
        try {
            // 比模型自身的读取超时多留一点余量，正常情况下由模型先触发 onError/onComplete。
            if (!completed.await(timeoutSeconds + 30L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("推理模型响应超时（超过 " + timeoutSeconds + " 秒）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("推理模型调用被中断", e);
        }
    }

    /**
     * 回调中的异常不应中断模型读取，最终仍由上层完成收尾。
     */
    private void notifyChunk(Consumer<String> consumer, String chunk) {
        if (consumer == null) {
            return;
        }
        try {
            consumer.accept(chunk);
        } catch (RuntimeException e) {
            log.debug("推送流式分片失败: {}", safeMessage(e));
        }
    }

    private int resolveTimeoutSeconds(Integer timeoutSeconds) {
        return timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
