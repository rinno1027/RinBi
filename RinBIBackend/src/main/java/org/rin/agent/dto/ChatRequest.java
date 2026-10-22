package org.rin.agent.dto;

import lombok.Data;

/**
 * 用户提问请求。
 */
@Data
public class ChatRequest {

    /**
     * 用户自然语言问题，必填。
     */
    private String question;

    /**
     * 指定数据库 key；为空时由模型根据 schema 自动选择。
     */
    private String database;

    /**
     * 会话 ID，用于多轮追问；为空时后端会生成并返回。
     */
    private String conversationId;
}
