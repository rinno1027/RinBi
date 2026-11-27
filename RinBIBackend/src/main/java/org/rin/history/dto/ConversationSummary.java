package org.rin.history.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 历史对话列表项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    private String conversationId;

    private String title;

    /**
     * 功能类型：DATA_QA 数据问答 / REPORT 智能报表。
     */
    private String featureType;

    private String datasourceKey;

    /**
     * 智能报表会话导入的模板名称；数据问答会话为 null。
     */
    private String templateName;

    /**
     * 该会话包含的问题/答案消息总数。
     */
    private Long messageCount;

    private LocalDateTime updatedAt;
}
