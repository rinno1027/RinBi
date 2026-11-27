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

    /**
     * 功能类型：DATA_QA 数据问答 / REPORT 智能报表。
     */
    private String featureType;

    private String datasourceKey;

    /**
     * 智能报表会话累计占用的模型输入字符数，用于多轮调整时的输入上限控制。
     */
    private Long inputChars;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
