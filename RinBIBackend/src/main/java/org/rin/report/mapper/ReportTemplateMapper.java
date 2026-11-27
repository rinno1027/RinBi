package org.rin.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.report.entity.ReportTemplateEntity;

import java.util.List;

/**
 * 智能报表导入模板访问入口。
 */
@Mapper
public interface ReportTemplateMapper extends BaseMapper<ReportTemplateEntity> {

    @Select("SELECT * FROM report_template WHERE user_id = #{userId} "
            + "AND conversation_id = #{conversationId} LIMIT 1")
    ReportTemplateEntity selectByConversation(@Param("userId") Long userId,
                                              @Param("conversationId") String conversationId);

    @Select("SELECT * FROM report_template WHERE user_id = #{userId} "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<ReportTemplateEntity> selectByUser(@Param("userId") Long userId,
                                            @Param("limit") int limit);
}
