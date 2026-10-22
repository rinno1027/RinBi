package org.rin.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.history.entity.ChatMessageEntity;

import java.util.List;

/**
 * 历史对话消息访问入口。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("SELECT * FROM chat_message WHERE conversation_pk = #{conversationPk} ORDER BY id")
    List<ChatMessageEntity> selectByConversation(@Param("conversationPk") Long conversationPk);

    @Select("SELECT * FROM chat_message WHERE conversation_pk = #{conversationPk} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessageEntity> selectRecent(@Param("conversationPk") Long conversationPk,
                                         @Param("limit") int limit);

    @Delete("DELETE FROM chat_message WHERE conversation_pk = #{conversationPk}")
    int deleteByConversation(@Param("conversationPk") Long conversationPk);
}
