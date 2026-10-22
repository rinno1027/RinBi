package org.rin.db;

import lombok.Builder;
import lombok.Data;

/**
 * 运行时数据源定义：由 data_source 表中的全局配置解析而来，密码已解密。
 *
 * <p>该对象只在后端内部流转，不会返回给前端。</p>
 */
@Data
@Builder
public class DataSourceDefinition {

    private Long id;

    private Long userId;

    /**
     * 数据源 key，同一用户内唯一。
     */
    private String key;

    private String name;

    private String description;

    private String dbType;

    private String dialect;

    private String jdbcUrl;

    private String driverClassName;

    private String username;

    private String password;

    private Boolean readOnly;

    private Integer maximumPoolSize;

    private Long connectionTimeoutMillis;

    private String initScript;

    /**
     * 配置版本（updated_at 时间戳），用于在配置更新后自动重建连接池。
     */
    private long version;
}
