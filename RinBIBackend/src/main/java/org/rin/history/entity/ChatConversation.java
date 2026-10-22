package org.rin.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史对话，对应元数据库 rinbidb.chat_conversation。
 */
@Data
@TableName("chat_conversation")
public class ChatConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 会话业务 ID，由前端生成并透传。
     */
    private String conversationId;

    private String title;

    private String datasourceKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
