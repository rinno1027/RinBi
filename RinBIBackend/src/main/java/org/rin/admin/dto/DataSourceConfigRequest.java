package org.rin.admin.dto;

import lombok.Data;

/**
 * 管理员新增/修改全局数据源的请求。
 *
 * <p>修改时 {@code password} 留空表示保留原密码。</p>
 */
@Data
public class DataSourceConfigRequest {

    private String dbKey;

    private String name;

    private String description;

    /**
     * mysql / postgresql / h2，或配合 jdbcUrl + driverClassName 使用的自定义类型。
     */
    private String dbType;

    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    private String password;

    private String driverClassName;

    /**
     * 完整 JDBC URL；非空时优先于 host/port/databaseName 拼装。
     */
    private String jdbcUrl;

    private String jdbcParams;

    private String dialect;

    private Boolean readOnly;

    private Integer maxPoolSize;

    private Long connectionTimeoutMillis;

    /**
     * 可选 classpath 初始化脚本，例如 db/demo.sql。
     */
    private String initScript;

    private Boolean enabled;
}
