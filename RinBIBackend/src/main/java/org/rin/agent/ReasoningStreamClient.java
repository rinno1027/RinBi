package org.rin.agent;

import org.rin.agent.dto.ReasoningStreamResult;

import java.util.function.Consumer;

/**
 * 支持思考过程的流式模型客户端。
 *
 * <p>实现需要把模型的 {@code reasoning_content} 增量交给 {@code onReasoning}，
 * 把最终答案的 {@code content} 增量交给 {@code onToken}，同时缓存完整文本，
 * 等流结束后一次性返回。这样前端可以实时展示思考过程和答案生成过程。</p>
 */
public interface ReasoningStreamClient {

    /**
     * 调用模型并阻塞到流结束。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param onReasoning  思考文本分片回调，可能被 IO 线程调用；为空时忽略
     * @param onToken      答案文本分片回调，可能被 IO 线程调用；为空时忽略
     * @return 完整思考文本与最终答案
     */
    ReasoningStreamResult stream(String systemPrompt, String userPrompt,
                                 Consumer<String> onReasoning, Consumer<String> onToken);
}
