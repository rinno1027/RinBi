package org.rin.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rin.agent.dto.ChatResponse;
import org.rin.exception.NotFoundException;
import org.rin.history.dto.ConversationDetail;
import org.rin.history.dto.ConversationSummary;
import org.rin.history.entity.ChatConversation;
import org.rin.history.entity.ChatMessageEntity;
import org.rin.history.mapper.ChatConversationMapper;
import org.rin.history.mapper.ChatMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 历史对话持久化：会话与消息落库到 rinbidb，用户之间严格隔离。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final int TITLE_MAX_CHARS = 120;

    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 写入一轮问答。会话不存在时自动创建，标题取首个问题摘要。
     */
    public void recordExchange(Long userId, String conversationId, String question,
                               String datasourceKey, ChatResponse response) {
        if (userId == null || !StringUtils.hasText(conversationId)
                || !StringUtils.hasText(question)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null) {
            conversation = new ChatConversation();
            conversation.setUserId(userId);
            conversation.setConversationId(conversationId);
            conversation.setTitle(truncate(question.trim()));
            conversation.setDatasourceKey(datasourceKey);
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversationMapper.insert(conversation);
        } else {
            conversation.setUpdatedAt(now);
            if (StringUtils.hasText(datasourceKey)) {
                conversation.setDatasourceKey(datasourceKey);
            }
            conversationMapper.updateById(conversation);
        }

        ChatMessageEntity questionMessage = new ChatMessageEntity();
        questionMessage.setConversationPk(conversation.getId());
        questionMessage.setRole("USER");
        questionMessage.setContent(question.trim());
        questionMessage.setCreatedAt(now);
        messageMapper.insert(questionMessage);

        if (response == null) {
            return;
        }
        ChatMessageEntity answerMessage = new ChatMessageEntity();
        answerMessage.setConversationPk(conversation.getId());
        answerMessage.setRole("ASSISTANT");
        answerMessage.setContent(response.getAnswer());
        answerMessage.setResponseJson(writeJson(response));
        answerMessage.setCreatedAt(now);
        messageMapper.insert(answerMessage);
    }

    /**
     * 当前用户的历史会话列表，按最近更新时间倒序。
     */
    public List<ConversationSummary> list(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return conversationMapper.selectSummaries(userId, safeLimit);
    }

    /**
     * 会话详情，包含完整的问答消息与结构化响应，用于前端点击历史后恢复。
     */
    public ConversationDetail detail(Long userId, String conversationId) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        List<ChatMessageEntity> messages = messageMapper.selectByConversation(conversation.getId());
        List<ConversationDetail.Message> items = new ArrayList<>(messages.size());
        for (ChatMessageEntity message : messages) {
            items.add(ConversationDetail.Message.builder()
                    .role(message.getRole())
                    .content(message.getContent())
                    .response(readJson(message.getResponseJson()))
                    .createdAt(message.getCreatedAt())
                    .build());
        }
        return ConversationDetail.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .datasourceKey(conversation.getDatasourceKey())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .messages(items)
                .build();
    }

    /**
     * 删除会话及其消息；不存在或不属于当前用户时抛出 NotFoundException。
     */
    public void delete(Long userId, String conversationId) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        messageMapper.deleteByConversation(conversation.getId());
        conversationMapper.deleteById(conversation.getId());
    }

    /**
     * 从数据库恢复最近的问答，用于进程重启后继续追问。
     */
    public List<HistoryExchange> recentExchanges(Long userId, String conversationId, int maxExchanges) {
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null) {
            return List.of();
        }
        int limit = Math.max(1, maxExchanges) * 2;
        List<ChatMessageEntity> recent = messageMapper.selectRecent(conversation.getId(), limit);
        Collections.reverse(recent);
        List<HistoryExchange> exchanges = new ArrayList<>();
        String pendingQuestion = null;
        for (ChatMessageEntity message : recent) {
            if ("USER".equals(message.getRole())) {
                pendingQuestion = message.getContent();
            } else if ("ASSISTANT".equals(message.getRole()) && pendingQuestion != null) {
                exchanges.add(new HistoryExchange(pendingQuestion, message.getContent()));
                pendingQuestion = null;
            }
        }
        return exchanges;
    }

    public ChatConversation findConversation(Long userId, String conversationId) {
        if (userId == null || !StringUtils.hasText(conversationId)) {
            return null;
        }
        return conversationMapper.selectOne(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .eq(ChatConversation::getConversationId, conversationId)
                .last("LIMIT 1"));
    }

    private ChatConversation requireConversation(Long userId, String conversationId) {
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null) {
            throw new NotFoundException("历史对话不存在或不属于当前用户");
        }
        return conversation;
    }

    private String writeJson(ChatResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("序列化对话响应失败: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("解析历史响应失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text) {
        if (text.length() <= TITLE_MAX_CHARS) {
            return text;
        }
        return text.substring(0, TITLE_MAX_CHARS) + "...";
    }

    /**
     * 一条历史问答。
     */
    public record HistoryExchange(String question, String answer) {
    }
}
