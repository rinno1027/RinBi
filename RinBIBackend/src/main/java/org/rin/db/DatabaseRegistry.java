package org.rin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.exception.BadRequestException;
import org.rin.exception.ForbiddenException;
import org.rin.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按用户授权管理查询数据源的连接池。
 *
 * <p>连接配置保存在元数据库 rinbidb.data_source，用户权限保存在
 * rinbidb.user_data_source 中间表。当前登录用户只能列出、访问已授权的数据源。
 * 连接池按数据源主键懒加载，配置更新（updated_at 变化）后会自动重建。</p>
 *
 * <p>Agent 执行 SQL 时使用原始 JDBC 连接，以便精确控制只读、超时和最大行数。</p>
 */
@Component
public class DatabaseRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRegistry.class);
    private static final String DEFAULT_MYSQL_PARAMS =
            "useUnicode=true&characterEncoding=utf8&useSSL=false"
                    + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String DEFAULT_H2_PARAMS =
            "DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";

    @Autowired
    private DataSourceConfigMapper dataSourceMapper;

    @Autowired
    private SecretCipher secretCipher;

    private final Map<Long, PoolEntry> pools = new ConcurrentHashMap<>();

    /**
     * 列出用户已授权的数据源（不含密码）。
     *
     * <p>连接配置来自全局 data_source，用户权限来自 user_data_source 中间表。</p>
     */
    public List<DataSourceDefinition> listForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return dataSourceMapper.selectEnabledByUser(userId).stream()
                .map(row -> toDefinition(row, userId))
                .collect(Collectors.toList());
    }

    public List<String> getKeys(Long userId) {
        return listForUser(userId).stream()
                .map(DataSourceDefinition::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 校验用户是否已被授权访问该数据源，并返回运行时定义。
     *
     * @throws BadRequestException 数据源不存在
     * @throws ForbiddenException  数据源存在但当前用户未被授权
     */
    public DataSourceDefinition requireOwned(Long userId, String databaseKey) {
        if (userId == null) {
            throw new ForbiddenException("缺少当前登录用户信息");
        }
        if (!StringUtils.hasText(databaseKey)) {
            throw new BadRequestException("数据库 key 不能为空");
        }
        String key = databaseKey.trim();
        DataSourceConfig row = dataSourceMapper.selectOwnedByUser(userId, key);
        if (row == null) {
            if (dataSourceMapper.countEnabledByKey(key) > 0) {
                throw new ForbiddenException("当前用户无权访问数据源: " + key);
            }
            throw new BadRequestException("未配置数据库: " + key
                    + "，可用数据库: " + getKeys(userId));
        }
        return toDefinition(row, userId);
    }

    public DataSource getDataSource(DataSourceDefinition definition) {
        PoolEntry entry = pools.compute(definition.getId(), (id, existing) -> {
            if (existing != null && existing.version == definition.getVersion()
                    && !existing.dataSource.isClosed()) {
                return existing;
            }
            if (existing != null) {
                closeQuietly(existing.dataSource);
            }
            HikariDataSource created = createDataSource(definition);
            runInitScript(definition, created);
            log.info("数据源连接池已就绪: user={}, key={}, dialect={}, url={}",
                    definition.getUserId(), definition.getKey(),
                    definition.getDialect(), definition.getJdbcUrl());
            return new PoolEntry(created, definition.getVersion());
        });
        return entry.dataSource;
    }

    private HikariDataSource createDataSource(DataSourceDefinition definition) {
        Class<?> driver = resolveDriver(definition);
        HikariConfig config = new HikariConfig();
        config.setPoolName("agent-" + definition.getUserId() + "-" + definition.getId());
        config.setJdbcUrl(definition.getJdbcUrl());
        if (driver != null) {
            config.setDriverClassName(driver.getName());
        }
        if (definition.getUsername() != null) {
            config.setUsername(definition.getUsername());
        }
        if (definition.getPassword() != null) {
            config.setPassword(definition.getPassword());
        }
        config.setMaximumPoolSize(definition.getMaximumPoolSize() == null
                || definition.getMaximumPoolSize() < 1 ? 3 : definition.getMaximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(definition.getConnectionTimeoutMillis() == null
                ? 10000L : definition.getConnectionTimeoutMillis());
        config.setAutoCommit(true);
        // 只读是数据库层面的一道额外保险，真正的安全边界是 SqlGuard。
        config.setReadOnly(Boolean.TRUE.equals(definition.getReadOnly()));
        config.setInitializationFailTimeout(-1L);
        return new HikariDataSource(config);
    }

    private Class<?> resolveDriver(DataSourceDefinition definition) {
        String driverClass = definition.getDriverClassName();
        if (!StringUtils.hasText(driverClass)) {
            return null;
        }
        try {
            return Class.forName(driverClass.trim());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("数据源 " + definition.getKey() + " 的驱动类不存在: "
                    + driverClass + "，请检查 pom.xml 是否已加入对应依赖", e);
        }
    }

    private void runInitScript(DataSourceDefinition definition, DataSource dataSource) {
        if (!StringUtils.hasText(definition.getInitScript())) {
            return;
        }
        String script = definition.getInitScript().trim();
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(script));
            // 显式指定脚本编码，否则会使用操作系统默认编码（Windows 下通常是 GBK），
            // 导致 UTF-8 脚本中的中文数据被错误写入数据库。
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            log.info("数据源 {} 初始化脚本执行完成: {}", definition.getKey(), script);
        } catch (Exception e) {
            throw new IllegalStateException("数据源 " + definition.getKey()
                    + " 初始化脚本执行失败: " + script, e);
        }
    }

    /**
     * 配置更新或删除后关闭缓存的连接池，下一次查询按新配置重建。
     */
    public void invalidateDataSource(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        PoolEntry removed = pools.remove(dataSourceId);
        if (removed != null) {
            closeQuietly(removed.dataSource);
            log.info("数据源连接池已失效: id={}", dataSourceId);
        }
    }

    public DataSource getDataSource(Long userId, String databaseKey) {
        return getDataSource(requireOwned(userId, databaseKey));
    }

    private DataSourceDefinition toDefinition(DataSourceConfig row, Long userId) {
        String dbType = normalizeType(row.getDbType());
        return DataSourceDefinition.builder()
                .id(row.getId())
                .userId(userId)
                .key(row.getDbKey())
                .name(row.getName() == null ? row.getDbKey() : row.getName())
                .description(row.getDescription())
                .dbType(dbType)
                .dialect(StringUtils.hasText(row.getDialect()) ? row.getDialect() : dbType)
                .jdbcUrl(buildJdbcUrl(row, dbType))
                .driverClassName(resolveDriverClassName(row, dbType))
                .username(row.getUsername())
                .password(secretCipher.decrypt(row.getPassword()))
                .readOnly(row.getReadOnly() == null || Boolean.TRUE.equals(row.getReadOnly()))
                .maximumPoolSize(row.getMaxPoolSize())
                .connectionTimeoutMillis(row.getConnectionTimeoutMillis())
                .initScript(row.getInitScript())
                .version(row.getUpdatedAt() == null ? 0L
                        : row.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    private String buildJdbcUrl(DataSourceConfig row, String dbType) {
        if (StringUtils.hasText(row.getJdbcUrl())) {
            return row.getJdbcUrl().trim();
        }
        String host = trimToNull(row.getHost());
        String database = trimToNull(row.getDatabaseName());
        if (database == null) {
            throw new IllegalStateException("数据源 " + row.getDbKey()
                    + " 未配置 database_name 或 jdbc_url");
        }
        switch (dbType) {
            case "mysql":
                return "jdbc:mysql://" + requireHost(row, host) + ":" + port(row, 3306)
                        + "/" + database + queryString(row.getJdbcParams(), DEFAULT_MYSQL_PARAMS);
            case "postgresql":
                return "jdbc:postgresql://" + requireHost(row, host) + ":" + port(row, 5432)
                        + "/" + database + queryString(row.getJdbcParams(), null);
            case "h2":
                String params = StringUtils.hasText(row.getJdbcParams())
                        ? row.getJdbcParams().trim() : DEFAULT_H2_PARAMS;
                return "jdbc:h2:mem:" + database + ";" + params;
            default:
                throw new IllegalStateException("数据源 " + row.getDbKey()
                        + " 的数据库类型暂不支持自动拼装 URL: " + dbType
                        + "，请填写 jdbc_url 与 driver_class_name");
        }
    }

    private String resolveDriverClassName(DataSourceConfig row, String dbType) {
        if (StringUtils.hasText(row.getDriverClassName())) {
            return row.getDriverClassName().trim();
        }
        return switch (dbType) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "h2" -> "org.h2.Driver";
            default -> null;
        };
    }

    private String requireHost(DataSourceConfig row, String host) {
        if (host == null) {
            throw new IllegalStateException("数据源 " + row.getDbKey() + " 未配置登录地址 host");
        }
        return host;
    }

    private int port(DataSourceConfig row, int defaultPort) {
        return row.getPort() == null || row.getPort() <= 0 ? defaultPort : row.getPort();
    }

    private String queryString(String jdbcParams, String defaultParams) {
        String params = StringUtils.hasText(jdbcParams) ? jdbcParams.trim() : defaultParams;
        return StringUtils.hasText(params) ? "?" + params : "";
    }

    private String normalizeType(String dbType) {
        if (!StringUtils.hasText(dbType)) {
            throw new IllegalStateException("数据源缺少 db_type 配置");
        }
        String type = dbType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "mariadb" -> "mysql";
            case "pg", "postgres" -> "postgresql";
            default -> type;
        };
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void closeQuietly(HikariDataSource dataSource) {
        try {
            dataSource.close();
        } catch (Exception e) {
            log.warn("关闭旧数据源连接池失败: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        for (Map.Entry<Long, PoolEntry> entry : pools.entrySet()) {
            try {
                entry.getValue().dataSource.close();
                log.info("数据源连接池已关闭: id={}", entry.getKey());
            } catch (Exception e) {
                log.warn("关闭数据源连接池失败: id={}", entry.getKey(), e);
            }
        }
        pools.clear();
    }

    private static final class PoolEntry {
        private final HikariDataSource dataSource;
        private final long version;

        private PoolEntry(HikariDataSource dataSource, long version) {
            this.dataSource = dataSource;
            this.version = version;
        }
    }
}

