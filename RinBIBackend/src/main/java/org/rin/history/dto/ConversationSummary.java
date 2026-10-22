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

    private String datasourceKey;

    /**
     * 该会话包含的问题/答案消息总数。
     */
    private Long messageCount;

    private LocalDateTime updatedAt;
}
