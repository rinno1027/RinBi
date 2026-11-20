package org.rin.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.rule.entity.StatRuleInputField;

import java.util.List;

/**
 * 统计规则输入字段访问入口。
 */
@Mapper
public interface StatRuleInputFieldMapper extends BaseMapper<StatRuleInputField> {

    @Select("""
            <script>
            SELECT * FROM stat_rule_input_field
            WHERE rule_id IN
            <foreach collection="ruleIds" item="ruleId" open="(" separator="," close=")">
                #{ruleId}
            </foreach>
            ORDER BY rule_id, sort_order, id
            </script>
            """)
    List<StatRuleInputField> selectByRuleIds(@Param("ruleIds") List<Long> ruleIds);
}
