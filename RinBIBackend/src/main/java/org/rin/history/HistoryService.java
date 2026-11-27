package org.rin.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rin.agent.dto.ChatResponse;
import org.rin.config.AgentProperties;
import org.rin.exception.NotFoundException;
import org.rin.history.dto.ConversationDetail;
import org.rin.history.dto.ConversationSummary;
import org.rin.history.entity.ChatConversation;
import org.rin.history.entity.ChatMessageEntity;
import org.rin.history.mapper.ChatConversationMapper;
import org.rin.history.mapper.ChatMessageMapper;
import org.rin.report.ReportTemplateStore;
import org.rin.report.dto.ReportTemplateInfo;
import org.rin.report.entity.ReportTemplateEntity;
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
 *
 * <p>会话按功能类型区分：{@link #FEATURE_DATA_QA} 数据问答与 {@link #FEATURE_REPORT}
 * 智能报表，两个功能各自维护会话列表；报表会话还会关联一个导入模板，
 * 并记录累计消耗的模型输入字符数。</p>
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final int TITLE_MAX_CHARS = 120;

    /**
     * 功能类型：数据问答。
     */
    public static final String FEATURE_DATA_QA = "DATA_QA";

    /**
     * 功能类型：智能报表。
     */
    public static final String FEATURE_REPORT = "REPORT";

    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReportTemplateStore templateStore;

    @Autowired
    private AgentProperties properties;

    /**
     * 写入一轮问答。会话不存在时自动创建，标题取首个问题摘要。
     */
    public void recordExchange(Long userId, String conversationId, String question,
                               String datasourceKey, ChatResponse response) {
        recordExchange(userId, conversationId, question, datasourceKey, response, FEATURE_DATA_QA);
    }

    /**
     * 写入一轮问答，并指定会话的功能类型（数据问答 / 智能报表）。
     * 已存在的会话不会被改成其他功能类型，避免两个功能的历史混淆。
     */
    public void recordExchange(Long userId, String conversationId, String question,
                               String datasourceKey, ChatResponse response, String featureType) {
        if (userId == null || !StringUtils.hasText(conversationId)
                || !StringUtils.hasText(question)) {
            return;
        }
        String feature = normalizeFeature(featureType);
        LocalDateTime now = LocalDateTime.now();
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null) {
            conversation = new ChatConversation();
            conversation.setUserId(userId);
            conversation.setConversationId(conversationId);
            conversation.setTitle(truncate(question.trim()));
            conversation.setFeatureType(feature);
            conversation.setDatasourceKey(datasourceKey);
            conversation.setInputChars(0L);
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversationMapper.insert(conversation);
        } else {
            conversation.setUpdatedAt(now);
            boolean changed = false;
            if (StringUtils.hasText(datasourceKey)) {
                conversation.setDatasourceKey(datasourceKey);
                changed = true;
            }
            if (!StringUtils.hasText(conversation.getFeatureType())) {
                conversation.setFeatureType(feature);
                changed = true;
            }
            if (conversation.getInputChars() == null) {
                conversation.setInputChars(0L);
                changed = true;
            }
            if (changed) {
                conversationMapper.updateById(conversation);
            } else {
                conversationMapper.touch(conversation.getId());
            }
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
     *
     * @param featureType 功能类型；为空时返回全部会话
     */
    public List<ConversationSummary> list(Long userId, int limit) {
        return list(userId, limit, null);
    }

    /**
     * 按功能类型返回历史会话列表：数据问答与智能报表分开拉取，互不混淆。
     */
    public List<ConversationSummary> list(Long userId, int limit, String featureType) {
        if (userId == null) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        if (!StringUtils.hasText(featureType)) {
            return conversationMapper.selectSummaries(userId, safeLimit);
        }
        return conversationMapper.selectSummariesByFeature(userId,
                normalizeFeature(featureType), safeLimit);
    }

    /**
     * 会话详情，包含完整的问答消息与结构化响应，用于前端点击历史后恢复。
     * 报表会话还会返回绑定的模板信息与累计输入字符数。
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
        ReportTemplateEntity template = templateStore.findByConversation(userId, conversationId);
        return ConversationDetail.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .featureType(normalizeFeature(conversation.getFeatureType()))
                .datasourceKey(conversation.getDatasourceKey())
                .template(toTemplateInfo(template))
                .inputChars(conversation.getInputChars() == null ? 0L : conversation.getInputChars())
                .inputLimit(maxConversationChars())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .messages(items)
                .build();
    }

    /**
     * 删除会话及其消息；不存在或不属于当前用户时抛出 NotFoundException。
     * 报表会话还会同时清理导入的模板记录与模板文件。
     */
    public void delete(Long userId, String conversationId) {
        ChatConversation conversation = requireConversation(userId, conversationId);
        messageMapper.deleteByConversation(conversation.getId());
        conversationMapper.deleteById(conversation.getId());
        templateStore.deleteByConversation(userId, conversationId);
    }

    /**
     * 报表会话累计消耗的模型输入字符数。
     */
    public long inputChars(Long userId, String conversationId) {
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null || conversation.getInputChars() == null) {
            return 0L;
        }
        return conversation.getInputChars();
    }

    /**
     * 累加报表会话的输入字符消耗；落库失败只记录日志，不影响本次报表生成。
     */
    public long addInputChars(Long userId, String conversationId, long chars) {
        ChatConversation conversation = findConversation(userId, conversationId);
        if (conversation == null) {
            return 0L;
        }
        long current = conversation.getInputChars() == null ? 0L : conversation.getInputChars();
        if (chars <= 0) {
            return current;
        }
        try {
            conversationMapper.addInputChars(conversation.getId(), chars);
        } catch (RuntimeException e) {
            log.warn("累计报表会话输入字符失败: conversation={}", conversationId, e);
        }
        return current + chars;
    }

    /**
     * 报表会话累计输入字符上限，与 agent.report.max-conversation-chars 保持一致。
     */
    public int maxConversationChars() {
        Integer configured = properties.getReport().getMaxConversationChars();
        return configured == null || configured <= 0 ? 120_000 : configured;
    }

    private ReportTemplateInfo toTemplateInfo(ReportTemplateEntity entity) {
        if (entity == null) {
            return null;
        }
        return ReportTemplateInfo.builder()
                .templateId(entity.getId())
                .conversationId(entity.getConversationId())
                .templateName(entity.getTemplateName())
                .downloadUrl("/api/report/templates/" + entity.getId())
                .fileSize(entity.getFileSize())
                .contentType(entity.getContentType())
                .sheetSummary(entity.getSheetSummary())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static String normalizeFeature(String featureType) {
        if (!StringUtils.hasText(featureType)) {
            return FEATURE_DATA_QA;
        }
        String normalized = featureType.trim().toUpperCase(java.util.Locale.ROOT);
        return FEATURE_REPORT.equals(normalized) ? FEATURE_REPORT : FEATURE_DATA_QA;
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
