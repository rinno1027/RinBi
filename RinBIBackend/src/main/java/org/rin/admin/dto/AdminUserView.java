package org.rin.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rin.auth.entity.SysUser;

import java.time.LocalDateTime;

/**
 * 管理员用户列表项，不包含密码摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserView {

    private Long id;

    private String username;

    private String displayName;

    private String role;

    /**
     * 0 禁用 / 1 正常 / 2 待审批。
     */
    private Integer status;

    private String statusText;

    private Integer dataSourceCount;

    /**
     * 授权弹框中表示该用户是否已获得当前数据源权限；用户分页中恒为 false。
     */
    private Boolean assigned;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static AdminUserView from(SysUser user, int dataSourceCount) {
        return from(user, dataSourceCount, false);
    }

    public static AdminUserView from(SysUser user, int dataSourceCount, boolean assigned) {
        return AdminUserView.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .status(user.getStatus())
                .statusText(statusText(user.getStatus()))
                .dataSourceCount(dataSourceCount)
                .assigned(assigned)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case SysUser.STATUS_ACTIVE -> "正常";
            case SysUser.STATUS_PENDING -> "待审批";
            case SysUser.STATUS_DISABLED -> "已禁用";
            default -> "未知";
        };
    }
}
