package org.rin.config;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 agent.llm 中与推理/思考相关的配置写入 OpenAI 兼容请求体。
 *
 * <p>LangChain4j 的 {@code returnThinking} 只决定是否解析并回调响应中的
 * {@code reasoning_content}，<b>不会</b>向请求体写入任何字段。DeepSeek 等
 * OpenAI 兼容服务需要通过 {@code thinking.type} 显式开关思考模式，因此这里用
 * {@link OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder#customParameters(Map)}
 * 注入自定义字段（序列化后位于请求体顶层）。</p>
 */
public final class OpenAiChatModelOptions {

    private static final String THINKING = "thinking";
    private static final String TYPE = "type";

    private OpenAiChatModelOptions() {
    }

    /**
     * 应用 {@code reasoning_effort} 与 {@code thinking.type}；未配置的项不会出现在请求体中。
     */
    public static void applyReasoningOptions(
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
            AgentProperties.Llm llm) {
        if (builder == null || llm == null) {
            return;
        }
        String reasoningEffort = trimToNull(llm.getReasoningEffort());
        if (reasoningEffort != null) {
            builder.reasoningEffort(reasoningEffort);
        }
        String thinkingType = trimToNull(llm.getThinkingType());
        if (thinkingType != null) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put(TYPE, thinkingType);
            builder.customParameters(Map.of(THINKING, thinking));
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
