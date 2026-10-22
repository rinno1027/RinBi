package org.rin.agent;

import org.rin.agent.dto.ChatResponse;

/**
 * 流式对话回调。
 *
 * <p>查询计划、SQL 校验与执行都在模型输出前完成，因此前端会先收到一次
 * {@link #onMeta(ChatResponse)}（包含会话、数据源、SQL、结构化结果），
 * 然后陆续收到 {@link #onReasoning(String)} 思考分片与 {@link #onToken(String)}
 * 答案分片。最终的完整 ChatResponse 通过 done 事件返回。</p>
 */
public interface ChatStreamListener {

    /**
     * 元数据就绪（此时 answer、reasoning 仍为空）。
     */
    default void onMeta(ChatResponse response) {
    }

    /**
     * 模型思考过程的分片。
     */
    default void onReasoning(String chunk) {
    }

    /**
     * 最终答案的分片。
     */
    default void onToken(String chunk) {
    }
}
