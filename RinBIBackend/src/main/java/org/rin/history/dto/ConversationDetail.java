package org.rin.history.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rin.report.dto.ReportTemplateInfo;

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

    /**
     * 功能类型：DATA_QA 数据问答 / REPORT 智能报表。
     */
    private String featureType;

    private String datasourceKey;

    /**
     * 智能报表会话导入的模板信息；数据问答会话为 null。
     */
    private ReportTemplateInfo template;

    /**
     * 报表会话已累计占用的输入字符数。
     */
    private Long inputChars;

    /**
     * 报表会话累计输入字符上限。
     */
    private Integer inputLimit;

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
