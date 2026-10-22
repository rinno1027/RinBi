package org.rin.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户，对应元数据库 rinbidb.sys_user。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /**
     * 账号已禁用或被管理员拒绝。
     */
    public static final int STATUS_DISABLED = 0;

    /**
     * 账号正常，可以登录。
     */
    public static final int STATUS_ACTIVE = 1;

    /**
     * 新注册账号，等待管理员审批。
     */
    public static final int STATUS_PENDING = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * PBKDF2 摘要，格式：pbkdf2-sha256$迭代次数$salt$hash。
     */
    private String passwordHash;

    private String displayName;

    /**
     * ADMIN / USER。
     */
    private String role;

    /**
     * 1 启用，0 禁用。
     */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
