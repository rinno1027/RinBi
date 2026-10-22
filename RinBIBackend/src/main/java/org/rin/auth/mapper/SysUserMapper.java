package org.rin.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rin.auth.entity.SysUser;

import java.util.List;

/**
 * 用户表访问入口。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 管理员用户列表：待审批优先，其次正常账号，最后禁用账号。
     */
    @Select("SELECT * FROM sys_user "
            + "ORDER BY CASE status WHEN 2 THEN 0 WHEN 1 THEN 1 ELSE 2 END, id DESC")
    List<SysUser> selectAllForAdmin();

    /**
     * 管理员用户分页：支持用户名/显示名称搜索与状态筛选。
     */
    @Select("""
            <script>
            SELECT * FROM sys_user
            <where>
                <if test="keyword != null and keyword != ''">
                    AND (username LIKE CONCAT('%', #{keyword}, '%')
                         OR display_name LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="status != null">AND status = #{status}</if>
            </where>
            ORDER BY CASE status WHEN 2 THEN 0 WHEN 1 THEN 1 ELSE 2 END, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SysUser> selectPageForAdmin(@Param("keyword") String keyword,
                                     @Param("status") Integer status,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM sys_user
            <where>
                <if test="keyword != null and keyword != ''">
                    AND (username LIKE CONCAT('%', #{keyword}, '%')
                         OR display_name LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="status != null">AND status = #{status}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("keyword") String keyword,
                       @Param("status") Integer status);
}
