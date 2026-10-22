package org.rin.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rin.db.entity.DataSourceConfig;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 管理员数据源配置视图。
 *
 * <p>只返回连接参数与「是否已保存密码」，绝不返回密码明文或密文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDataSourceView {

    private Long id;

    private String dbKey;

    private String name;

    private String description;

    private String dbType;

    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    private boolean passwordConfigured;

    private String driverClassName;

    private String jdbcUrl;

    private String jdbcParams;

    private String dialect;

    private Boolean readOnly;

    private Integer maxPoolSize;

    private Long connectionTimeoutMillis;

    private String initScript;

    private Boolean enabled;

    /**
     * 已获得该数据源访问权限的用户数量。
     */
    private Integer assignedUserCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static AdminDataSourceView from(DataSourceConfig row) {
        return from(row, 0);
    }

    public static AdminDataSourceView from(DataSourceConfig row, int assignedUserCount) {
        return AdminDataSourceView.builder()
                .id(row.getId())
                .dbKey(row.getDbKey())
                .name(row.getName())
                .description(row.getDescription())
                .dbType(row.getDbType())
                .host(row.getHost())
                .port(row.getPort())
                .databaseName(row.getDatabaseName())
                .username(row.getUsername())
                .passwordConfigured(StringUtils.hasText(row.getPassword()))
                .driverClassName(row.getDriverClassName())
                .jdbcUrl(row.getJdbcUrl())
                .jdbcParams(row.getJdbcParams())
                .dialect(row.getDialect())
                .readOnly(row.getReadOnly())
                .maxPoolSize(row.getMaxPoolSize())
                .connectionTimeoutMillis(row.getConnectionTimeoutMillis())
                .initScript(row.getInitScript())
                .enabled(row.getEnabled())
                .assignedUserCount(assignedUserCount)
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }
}

