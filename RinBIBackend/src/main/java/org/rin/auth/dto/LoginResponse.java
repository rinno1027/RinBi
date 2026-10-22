package org.rin.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 登录成功响应：token 放入 Authorization: Bearer &lt;token&gt;。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private OffsetDateTime expiresAt;

    private UserInfo user;
}
