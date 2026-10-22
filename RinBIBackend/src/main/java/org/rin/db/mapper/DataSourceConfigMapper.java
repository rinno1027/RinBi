package org.rin.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.db.entity.DataSourceConfig;

import java.util.List;

/**
 * 全局数据源配置访问入口。
 */
@Mapper
public interface DataSourceConfigMapper extends BaseMapper<DataSourceConfig> {

    @Select("SELECT d.* FROM data_source d "
            + "JOIN user_data_source uds ON uds.data_source_id = d.id "
            + "WHERE uds.user_id = #{userId} AND d.enabled = 1 ORDER BY d.id")
    List<DataSourceConfig> selectEnabledByUser(@Param("userId") Long userId);

    @Select("SELECT d.* FROM data_source d "
            + "JOIN user_data_source uds ON uds.data_source_id = d.id "
            + "WHERE uds.user_id = #{userId} AND d.db_key = #{dbKey} "
            + "AND d.enabled = 1 LIMIT 1")
    DataSourceConfig selectOwnedByUser(@Param("userId") Long userId,
                                       @Param("dbKey") String dbKey);

    /**
     * 判断某个 key 是否存在于全局数据源中，用于区分「不存在」与「无权限」。
     */
    @Select("SELECT COUNT(*) FROM data_source WHERE db_key = #{dbKey} AND enabled = 1")
    int countEnabledByKey(@Param("dbKey") String dbKey);

    @Select("SELECT * FROM data_source WHERE db_key = #{dbKey} LIMIT 1")
    DataSourceConfig selectByDbKey(@Param("dbKey") String dbKey);

    @Select("""
            <script>
            SELECT * FROM data_source
            <where>
                <if test="keyword != null and keyword != ''">
                    AND (name LIKE CONCAT('%', #{keyword}, '%')
                         OR db_key LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="enabled != null">AND enabled = #{enabled}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DataSourceConfig> selectPageForAdmin(@Param("keyword") String keyword,
                                              @Param("enabled") Boolean enabled,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM data_source
            <where>
                <if test="keyword != null and keyword != ''">
                    AND (name LIKE CONCAT('%', #{keyword}, '%')
                         OR db_key LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="enabled != null">AND enabled = #{enabled}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("keyword") String keyword,
                       @Param("enabled") Boolean enabled);
}
