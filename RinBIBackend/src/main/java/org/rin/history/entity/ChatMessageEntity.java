package org.rin.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史对话消息，对应元数据库 rinbidb.chat_message。
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * chat_conversation.id。
     */
    private Long conversationPk;

    /**
     * USER / ASSISTANT。
     */
    private String role;

    private String content;

    /**
     * 助手消息的完整 ChatResponse（JSON），用于前端恢复表格、SQL、状态等结构化信息。
     */
    private String responseJson;

    private LocalDateTime createdAt;
}
