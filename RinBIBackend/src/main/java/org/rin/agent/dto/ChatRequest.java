package org.rin.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;

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

    /**
     * 用户勾选的统计规则 ID。只有显式指定 database 时才允许使用；
     * 为空表示不使用规则。规则会作为 JSON 注入查询规划提示词。
     */
    private List<Long> ruleIds;

    /**
     * 后端在 prepare 阶段根据 ruleIds 校验、序列化后的规则 JSON。
     * 该字段不参与请求反序列化和响应序列化，避免客户端伪造提示词内容。
     */
    @JsonIgnore
    private String rulePromptJson;
}
