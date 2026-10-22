package org.rin.auth.dto;

import lombok.Data;

/**
 * 新用户注册申请。
 *
 * <p>注册成功后账号状态为「待审批」，需要管理员审批通过后才能登录。</p>
 */
@Data
public class RegisterRequest {

    private String username;

    private String password;

    /**
     * 显示名称；留空时使用用户名。
     */
    private String displayName;
}
