package org.rin.history.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史对话详情：包含完整消息列表，前端可据此恢复对话与结果表格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDetail {

    private String conversationId;

    private String title;

    private String datasourceKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<Message> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {

        /**
         * USER / ASSISTANT。
         */
        private String role;

        private String content;

        /**
         * 助手消息的结构化响应（ChatResponse），用户消息为 null。
         */
        private JsonNode response;

        private LocalDateTime createdAt;
    }
}
