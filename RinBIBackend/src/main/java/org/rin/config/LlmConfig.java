package org.rin.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 模型配置全部来自 agent.llm.*，避免在代码中硬编码模型地址和参数。
 *
 * <p>LangChain4j 1.x 起阻塞式 {@code generate(...)} 已移除，业务侧统一使用
 * {@link StreamingChatModel}：由调用方在流结束后汇总文本。因此这里只注册流式模型。</p>
 */
@Configuration
public class LlmConfig {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * 普通对话模型，Agent 的“可行性判断/生成 SQL”和“结果整理”都使用它。
     *
     * <p>流式模型不提供 maxRetries 配置项，重试由上层业务（spring-retry）负责。</p>
     */
    @Bean
    @Primary
    public StreamingChatModel openAiStreamingChatModel(AgentProperties properties) {
        AgentProperties.Llm llm = properties.getLlm();
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .modelName(llm.getModelName())
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .logRequests(Boolean.TRUE.equals(llm.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(llm.getLogResponses()))
                .maxTokens(llm.getMaxTokens())
                .topP(0.8D)
                .temperature(llm.getTemperature())
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm.getTimeoutSeconds())))
                .frequencyPenalty(0D)
                .presencePenalty(0D)
                .returnThinking(Boolean.TRUE.equals(llm.getReturnThinking()));
        // reasoning_effort / thinking.type 只有在配置后才写入请求体
        OpenAiChatModelOptions.applyReasoningOptions(builder, llm);
        return builder.build();
    }

    /**
     * ollama 本地大模型（可选）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agent.llm.ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OllamaStreamingChatModel ollamaStreamModel(AgentProperties properties) {
        AgentProperties.Ollama ollama = properties.getLlm().getOllama();
        return OllamaStreamingChatModel.builder()
                .modelName(ollama.getModelName())
                .baseUrl(ollama.getBaseUrl())
                .logRequests(Boolean.TRUE.equals(properties.getLlm().getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLlm().getLogResponses()))
                .numCtx(8000)
                .topP(0.8D)
                .temperature(properties.getLlm().getTemperature())
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(properties.getLlm().getTimeoutSeconds())))
                .returnThinking(Boolean.TRUE.equals(properties.getLlm().getReturnThinking()))
                .build();
    }

    private int resolveTimeoutSeconds(Integer timeoutSeconds) {
        return timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }
}




