package org.rin.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.rule.dto.StatRuleOption;
import org.rin.rule.entity.StatRule;

import java.util.List;

/**
 * 统计规则访问入口。
 *
 * <p>所有共享查询都通过 user_data_source 校验当前用户对数据源的访问权限；
 * 创建人/修改人名称在查询中直接联表带出，避免逐条回查用户表。</p>
 */
@Mapper
public interface StatRuleMapper extends BaseMapper<StatRule> {

    @Select("""
            <script>
            SELECT r.*, d.name AS data_source_name,
                   cu.username AS created_by_name,
                   COALESCE(uu.username, cu.username) AS updated_by_name
            FROM stat_rule r
            JOIN data_source d ON d.db_key = r.data_source_key
            JOIN user_data_source uds ON uds.data_source_id = d.id AND uds.user_id = #{userId}
            LEFT JOIN sys_user cu ON cu.id = r.created_by
            LEFT JOIN sys_user uu ON uu.id = r.updated_by
            WHERE d.enabled = 1
            <if test="dataSourceKey != null and dataSourceKey != ''">
                AND r.data_source_key = #{dataSourceKey}
            </if>
            <if test="keyword != null and keyword != ''">
                AND (r.rule_code LIKE CONCAT('%', #{keyword}, '%')
                     OR r.rule_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY r.updated_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<StatRule> selectAccessiblePage(@Param("userId") Long userId,
                                        @Param("dataSourceKey") String dataSourceKey,
                                        @Param("keyword") String keyword,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM stat_rule r
            JOIN data_source d ON d.db_key = r.data_source_key
            JOIN user_data_source uds ON uds.data_source_id = d.id AND uds.user_id = #{userId}
            WHERE d.enabled = 1
            <if test="dataSourceKey != null and dataSourceKey != ''">
                AND r.data_source_key = #{dataSourceKey}
            </if>
            <if test="keyword != null and keyword != ''">
                AND (r.rule_code LIKE CONCAT('%', #{keyword}, '%')
                     OR r.rule_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countAccessible(@Param("userId") Long userId,
                         @Param("dataSourceKey") String dataSourceKey,
                         @Param("keyword") String keyword);

    @Select("""
            SELECT r.*, d.name AS data_source_name,
                   cu.username AS created_by_name,
                   COALESCE(uu.username, cu.username) AS updated_by_name
            FROM stat_rule r
            JOIN data_source d ON d.db_key = r.data_source_key
            JOIN user_data_source uds ON uds.data_source_id = d.id AND uds.user_id = #{userId}
            LEFT JOIN sys_user cu ON cu.id = r.created_by
            LEFT JOIN sys_user uu ON uu.id = r.updated_by
            WHERE r.id = #{ruleId} AND d.enabled = 1
            """)
    StatRule selectAccessibleById(@Param("userId") Long userId,
                                  @Param("ruleId") Long ruleId);

    @Select("SELECT COUNT(*) FROM stat_rule WHERE rule_code = #{ruleCode}")
    int countByRuleCode(@Param("ruleCode") String ruleCode);

    @Select("""
            <script>
            SELECT COUNT(*) FROM stat_rule
            WHERE rule_code = #{ruleCode}
            <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
            </script>
            """)
    int countByRuleCodeExcluding(@Param("ruleCode") String ruleCode,
                                 @Param("excludeId") Long excludeId);

    @Select("""
            <script>
            SELECT r.*
            FROM stat_rule r
            JOIN data_source d ON d.db_key = r.data_source_key
            JOIN user_data_source uds ON uds.data_source_id = d.id AND uds.user_id = #{userId}
            WHERE d.enabled = 1 AND r.data_source_key = #{dataSourceKey}
              AND r.id IN
            <foreach collection="ruleIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY r.id
            </script>
            """)
    List<StatRule> selectAccessibleByIds(@Param("userId") Long userId,
                                         @Param("dataSourceKey") String dataSourceKey,
                                         @Param("ruleIds") List<Long> ruleIds);

    /**
     * 对话页规则多选框候选项：只返回当前用户可访问数据源下的规则。
     */
    @Select("""
            SELECT r.id, r.rule_code AS ruleCode, r.rule_name AS ruleName,
                   r.rule_description AS ruleDescription
            FROM stat_rule r
            JOIN data_source d ON d.db_key = r.data_source_key
            JOIN user_data_source uds ON uds.data_source_id = d.id AND uds.user_id = #{userId}
            WHERE d.enabled = 1 AND r.data_source_key = #{dataSourceKey}
            ORDER BY r.rule_code
            """)
    List<StatRuleOption> selectOptions(@Param("userId") Long userId,
                                       @Param("dataSourceKey") String dataSourceKey);
}
