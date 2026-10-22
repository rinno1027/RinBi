package org.rin.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.db.entity.UserDataSource;

import java.util.List;

/**
 * 用户-数据源授权关系访问入口。
 */
@Mapper
public interface UserDataSourceMapper extends BaseMapper<UserDataSource> {

    @Select("SELECT data_source_id FROM user_data_source WHERE user_id = #{userId}")
    List<Long> selectDataSourceIdsByUser(@Param("userId") Long userId);

    @Select("SELECT user_id FROM user_data_source WHERE data_source_id = #{dataSourceId} ORDER BY user_id")
    List<Long> selectUserIdsByDataSource(@Param("dataSourceId") Long dataSourceId);

    @Select("SELECT COUNT(*) FROM user_data_source WHERE user_id = #{userId}")
    int countByUser(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM user_data_source WHERE data_source_id = #{dataSourceId}")
    int countByDataSource(@Param("dataSourceId") Long dataSourceId);

    @Select("SELECT COUNT(*) FROM user_data_source "
            + "WHERE user_id = #{userId} AND data_source_id = #{dataSourceId}")
    int countByUserAndDataSource(@Param("userId") Long userId,
                                 @Param("dataSourceId") Long dataSourceId);

    @Delete("DELETE FROM user_data_source WHERE data_source_id = #{dataSourceId}")
    int deleteByDataSource(@Param("dataSourceId") Long dataSourceId);
}

