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

    @Select("SELECT c.conversation_id AS conversationId, c.title AS title, "
            + "c.datasource_key AS datasourceKey, "
            + "(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_pk = c.id) AS messageCount, "
            + "c.updated_at AS updatedAt "
            + "FROM chat_conversation c WHERE c.user_id = #{userId} "
            + "ORDER BY c.updated_at DESC, c.id DESC LIMIT #{limit}")
    List<ConversationSummary> selectSummaries(@Param("userId") Long userId, @Param("limit") int limit);

    @Update("UPDATE chat_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touch(@Param("id") Long id);
}
