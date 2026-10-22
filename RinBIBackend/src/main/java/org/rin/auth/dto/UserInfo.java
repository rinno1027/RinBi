package org.rin.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rin.auth.AuthUser;

/**
 * 返回给前端的当前用户信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    private Long id;

    private String username;

    private String displayName;

    private String role;

    public static UserInfo from(AuthUser user) {
        return UserInfo.builder()
                .id(user.id())
                .username(user.username())
                .displayName(user.displayName())
                .role(user.role())
                .build();
    }
}
