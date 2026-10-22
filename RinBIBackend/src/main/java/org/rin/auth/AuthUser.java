package org.rin.auth;

import org.rin.auth.entity.SysUser;

/**
 * 当前登录用户的精简信息，存放在请求属性中。
 */
public record AuthUser(Long id, String username, String displayName, String role) {

    public static AuthUser from(SysUser user) {
        return new AuthUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole() == null ? "USER" : user.getRole());
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
