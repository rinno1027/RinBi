package org.rin.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.rin.history.dto.ConversationSummary;
import org.rin.history.entity.ChatConversation;

import java.util.List;

/**
 * 历史对话访问入口。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    String SUMMARY_COLUMNS = "SELECT c.conversation_id AS conversationId, c.title AS title, "
            + "c.feature_type AS featureType, c.datasource_key AS datasourceKey, "
            + "t.template_name AS templateName, "
            + "(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_pk = c.id) AS messageCount, "
            + "c.updated_at AS updatedAt "
            + "FROM chat_conversation c "
            + "LEFT JOIN report_template t ON t.user_id = c.user_id "
            + "AND t.conversation_id = c.conversation_id ";

    @Select(SUMMARY_COLUMNS + "WHERE c.user_id = #{userId} "
            + "ORDER BY c.updated_at DESC, c.id DESC LIMIT #{limit}")
    List<ConversationSummary> selectSummaries(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 按功能类型过滤：DATA_QA 数据问答 / REPORT 智能报表，便于前端分开展示两个功能的历史。
     */
    @Select(SUMMARY_COLUMNS + "WHERE c.user_id = #{userId} AND c.feature_type = #{featureType} "
            + "ORDER BY c.updated_at DESC, c.id DESC LIMIT #{limit}")
    List<ConversationSummary> selectSummariesByFeature(@Param("userId") Long userId,
                                                       @Param("featureType") String featureType,
                                                       @Param("limit") int limit);

    @Update("UPDATE chat_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touch(@Param("id") Long id);

    /**
     * 累加报表会话占用的模型输入字符数。
     */
    @Update("UPDATE chat_conversation SET input_chars = input_chars + #{chars}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int addInputChars(@Param("id") Long id, @Param("chars") long chars);
}
