package org.rin.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推理模型一次流式调用的结果。
 *
 * <p>reasoning 是模型返回的思考过程，会在生成过程中通过回调实时下发；
 * content 是最终答案，只在流结束后一次性返回，不再逐片输出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningStreamResult {

    /**
     * 完整思考文本；模型不支持 reasoning_content 时为空字符串。
     */
    private String reasoning;

    /**
     * 完整 Markdown 答案。
     */
    private String content;
}
