package org.rin.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 全局数据源配置，对应元数据库 rinbidb.data_source。
 *
 * <p>连接参数由管理员统一维护，用户通过 user_data_source 中间表获得访问权限。
 * 同一数据源可以被多个用户复用，避免为每个用户重复录入相同连接。</p>
 */
@Data
@TableName("data_source")
public class DataSourceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 数据源 key，全局唯一，作为接口 database 参数。
     */
    private String dbKey;

    private String name;

    private String description;

    /**
     * mysql / postgresql / h2 等。
     */
    private String dbType;

    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    /**
     * AES-GCM 加密后的密码；兼容历史明文数据。
     */
    @ToString.Exclude
    private String password;

    private String driverClassName;

    /**
     * 完整 JDBC URL，非空时优先于 host/port/databaseName 拼装。
     */
    private String jdbcUrl;

    private String jdbcParams;

    /**
     * 方言提示，供模型生成 SQL 时参考。
     */
    private String dialect;

    private Boolean readOnly;

    private Integer maxPoolSize;

    private Long connectionTimeoutMillis;

    /**
     * 可选的 classpath 初始化脚本，例如 db/demo.sql。
     */
    private String initScript;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
