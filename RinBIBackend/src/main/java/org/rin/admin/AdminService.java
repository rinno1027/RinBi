package org.rin.admin;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.rin.admin.dto.AdminDataSourceView;
import org.rin.admin.dto.AdminUserView;
import org.rin.admin.dto.DataSourceAccessRequest;
import org.rin.admin.dto.DataSourceAccessView;
import org.rin.admin.dto.DataSourceConfigRequest;
import org.rin.admin.dto.PageResult;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.db.DatabaseRegistry;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.entity.UserDataSource;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.db.mapper.UserDataSourceMapper;
import org.rin.exception.BadRequestException;
import org.rin.exception.ForbiddenException;
import org.rin.exception.NotFoundException;
import org.rin.security.SecretCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理员用户审批、全局数据源配置与按用户授权。
 *
 * <p>数据源连接配置统一保存在 data_source，用户访问权限保存在
 * user_data_source 中间表。所有公开方法都会再次校验当前登录用户是否为
 * ADMIN，避免只依赖前端隐藏入口。</p>
 */
@Service
public class AdminService {

    private static final Pattern DB_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int DEFAULT_MAX_POOL_SIZE = 3;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000L;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private DataSourceConfigMapper dataSourceConfigMapper;

    @Autowired
    private UserDataSourceMapper userDataSourceMapper;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private AuthService authService;

    /**
     * 用户分页，待审批优先；同时返回每个用户已授权的数据源数量。
     */
    public PageResult<AdminUserView> listUsers(AuthUser actor, int page, int size,
                                               String keyword, String status) {
        requireAdmin(actor);
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        String term = trimToNull(keyword);
        Integer statusValue = parseUserStatus(status);
        long total = userMapper.countForAdmin(term, statusValue);
        List<AdminUserView> items = userMapper.selectPageForAdmin(
                        term, statusValue, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(this::toUserView)
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    /**
     * 审批通过（或重新启用）账号，并吊销它此前可能残留的在线会话。
     */
    @Transactional
    public AdminUserView approveUser(AuthUser actor, Long userId) {
        requireAdmin(actor);
        SysUser target = requireUser(userId);
        target.setStatus(SysUser.STATUS_ACTIVE);
        target.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(target);
        authService.revokeUserSessions(userId);
        return toUserView(target);
    }

    /**
     * 禁用账号；待审批用户执行该操作等价于拒绝申请。
     */
    @Transactional
    public AdminUserView disableUser(AuthUser actor, Long userId) {
        requireAdmin(actor);
        if (Objects.equals(actor.id(), userId)) {
            throw new BadRequestException("不能禁用当前登录的管理员账号");
        }
        SysUser target = requireUser(userId);
        target.setStatus(SysUser.STATUS_DISABLED);
        target.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(target);
        authService.revokeUserSessions(userId);
        return toUserView(target);
    }

    /**
     * 全局数据源分页，包含停用记录，但不返回密码。
     */
    public PageResult<AdminDataSourceView> listDataSources(AuthUser actor, int page, int size,
                                                           String keyword, String enabled) {
        requireAdmin(actor);
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        String term = trimToNull(keyword);
        Boolean enabledValue = parseEnabled(enabled);
        long total = dataSourceConfigMapper.countForAdmin(term, enabledValue);
        List<AdminDataSourceView> items = dataSourceConfigMapper.selectPageForAdmin(
                        term, enabledValue, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(this::toDataSourceView)
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    /**
     * 数据源授权弹框：返回全部用户及当前已授权用户 ID。
     */
    public DataSourceAccessView listDataSourceAccess(AuthUser actor, Long dataSourceId) {
        requireAdmin(actor);
        DataSourceConfig source = requireDataSource(dataSourceId);
        Set<Long> assigned = new HashSet<>(
                userDataSourceMapper.selectUserIdsByDataSource(dataSourceId));
        List<AdminUserView> users = userMapper.selectAllForAdmin().stream()
                .map(user -> AdminUserView.from(user,
                        userDataSourceMapper.countByUser(user.getId()),
                        assigned.contains(user.getId())))
                .toList();
        return new DataSourceAccessView(source.getId(), source.getName(), users,
                new ArrayList<>(assigned));
    }

    /**
     * 以最终勾选结果覆盖某个数据源的用户授权。
     */
    @Transactional
    public void assignDataSourceUsers(AuthUser actor, Long dataSourceId,
                                      DataSourceAccessRequest request) {
        requireAdmin(actor);
        DataSourceConfig source = requireDataSource(dataSourceId);
        if (request == null) {
            throw new BadRequestException("授权用户列表不能为空");
        }
        Set<Long> userIds = new LinkedHashSet<>();
        if (request.getUserIds() != null) {
            for (Long userId : request.getUserIds()) {
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }
        for (Long userId : userIds) {
            requireUser(userId);
        }

        userDataSourceMapper.deleteByDataSource(source.getId());
        LocalDateTime now = LocalDateTime.now();
        for (Long userId : userIds) {
            UserDataSource grant = new UserDataSource();
            grant.setUserId(userId);
            grant.setDataSourceId(source.getId());
            grant.setCreatedAt(now);
            userDataSourceMapper.insert(grant);
        }
    }

    @Transactional
    public AdminDataSourceView createDataSource(AuthUser actor, DataSourceConfigRequest request) {
        requireAdmin(actor);
        if (request == null) {
            throw new BadRequestException("数据源配置不能为空");
        }
        String dbKey = normalizeAndValidate(request);
        if (dataSourceConfigMapper.selectByDbKey(dbKey) != null) {
            throw new BadRequestException("已存在数据源 key: " + dbKey);
        }

        LocalDateTime now = LocalDateTime.now();
        DataSourceConfig row = new DataSourceConfig();
        row.setDbKey(dbKey);
        applyConfig(row, request);
        row.setPassword(secretCipher.encrypt(
                request.getPassword() == null ? "" : request.getPassword()));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            dataSourceConfigMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BadRequestException("已存在数据源 key: " + dbKey);
        }
        return AdminDataSourceView.from(row);
    }

    /**
     * 修改数据源。password 留空表示保留原密码。
     */
    @Transactional
    public AdminDataSourceView updateDataSource(AuthUser actor, Long dataSourceId,
                                                DataSourceConfigRequest request) {
        requireAdmin(actor);
        DataSourceConfig existing = requireDataSource(dataSourceId);
        if (request == null) {
            throw new BadRequestException("数据源配置不能为空");
        }
        String dbKey = normalizeAndValidate(request);
        DataSourceConfig sameKey = dataSourceConfigMapper.selectByDbKey(dbKey);
        if (sameKey != null && !Objects.equals(sameKey.getId(), dataSourceId)) {
            throw new BadRequestException("已存在数据源 key: " + dbKey);
        }

        String storedPassword = StringUtils.hasText(request.getPassword())
                ? secretCipher.encrypt(request.getPassword())
                : existing.getPassword();

        LambdaUpdateWrapper<DataSourceConfig> update = new LambdaUpdateWrapper<>();
        update.eq(DataSourceConfig::getId, dataSourceId)
                .set(DataSourceConfig::getDbKey, dbKey)
                .set(DataSourceConfig::getName, request.getName().trim())
                .set(DataSourceConfig::getDescription, trimToNull(request.getDescription()))
                .set(DataSourceConfig::getDbType, normalizeDbType(request.getDbType()))
                .set(DataSourceConfig::getHost, trimToNull(request.getHost()))
                .set(DataSourceConfig::getPort, request.getPort())
                .set(DataSourceConfig::getDatabaseName, trimToNull(request.getDatabaseName()))
                .set(DataSourceConfig::getUsername, request.getUsername().trim())
                .set(DataSourceConfig::getPassword, storedPassword)
                .set(DataSourceConfig::getDriverClassName,
                        trimToNull(request.getDriverClassName()))
                .set(DataSourceConfig::getJdbcUrl, trimToNull(request.getJdbcUrl()))
                .set(DataSourceConfig::getJdbcParams, trimToNull(request.getJdbcParams()))
                .set(DataSourceConfig::getDialect,
                        normalizeDialect(request.getDialect(), request.getDbType()))
                .set(DataSourceConfig::getReadOnly,
                        request.getReadOnly() == null || Boolean.TRUE.equals(request.getReadOnly()))
                .set(DataSourceConfig::getMaxPoolSize,
                        normalizePoolSize(request.getMaxPoolSize()))
                .set(DataSourceConfig::getConnectionTimeoutMillis,
                        normalizeTimeout(request.getConnectionTimeoutMillis()))
                .set(DataSourceConfig::getInitScript, trimToNull(request.getInitScript()))
                .set(DataSourceConfig::getEnabled,
                        request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()))
                .set(DataSourceConfig::getUpdatedAt, LocalDateTime.now());
        dataSourceConfigMapper.update(null, update);
        databaseRegistry.invalidateDataSource(dataSourceId);

        DataSourceConfig saved = requireDataSource(dataSourceId);
        return toDataSourceView(saved);
    }

    /**
     * 删除全局数据源及其全部用户授权。
     */
    @Transactional
    public void deleteDataSource(AuthUser actor, Long dataSourceId) {
        requireAdmin(actor);
        requireDataSource(dataSourceId);
        userDataSourceMapper.deleteByDataSource(dataSourceId);
        dataSourceConfigMapper.deleteById(dataSourceId);
        databaseRegistry.invalidateDataSource(dataSourceId);
    }

    private String normalizeAndValidate(DataSourceConfigRequest request) {
        if (!StringUtils.hasText(request.getDbKey())) {
            throw new BadRequestException("数据库 key 不能为空");
        }
        String dbKey = request.getDbKey().trim();
        if (!DB_KEY_PATTERN.matcher(dbKey).matches()) {
            throw new BadRequestException("数据库 key 仅支持 1-64 位字母、数字、下划线和短横线");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BadRequestException("数据源名称不能为空");
        }
        checkLength(request.getName(), 128, "数据源名称");
        checkLength(request.getDescription(), 512, "数据源说明");
        checkLength(request.getHost(), 255, "登录地址");
        checkLength(request.getDatabaseName(), 128, "数据库名");
        checkLength(request.getUsername(), 128, "数据库账号");
        checkLength(request.getDriverClassName(), 128, "驱动类名");
        checkLength(request.getJdbcUrl(), 1024, "JDBC URL");
        checkLength(request.getJdbcParams(), 512, "连接参数");
        checkLength(request.getInitScript(), 255, "初始化脚本");
        if (request.getPassword() != null && request.getPassword().length() > 128) {
            throw new BadRequestException("数据库密码不能超过 128 个字符");
        }
        if (request.getPort() != null && (request.getPort() < 1 || request.getPort() > 65535)) {
            throw new BadRequestException("端口号需在 1-65535 之间");
        }
        String dbType = normalizeDbType(request.getDbType());
        if (request.getJdbcUrl() == null || request.getJdbcUrl().isBlank()) {
            if (!StringUtils.hasText(request.getDatabaseName())) {
                throw new BadRequestException("未填写 JDBC URL 时必须填写数据库名");
            }
            if (requiresHost(dbType) && !StringUtils.hasText(request.getHost())) {
                throw new BadRequestException("MySQL / PostgreSQL 数据源必须填写登录地址");
            }
        }
        if (!StringUtils.hasText(request.getUsername())) {
            throw new BadRequestException("数据库账号不能为空");
        }
        if (StringUtils.hasText(request.getInitScript())
                && (request.getInitScript().contains("..")
                || request.getInitScript().startsWith("/")
                || request.getInitScript().contains("\\"))) {
            throw new BadRequestException("初始化脚本必须是 classpath 相对路径，例如 db/demo.sql");
        }
        return dbKey;
    }

    private void applyConfig(DataSourceConfig row, DataSourceConfigRequest request) {
        row.setName(request.getName().trim());
        row.setDescription(trimToNull(request.getDescription()));
        row.setDbType(normalizeDbType(request.getDbType()));
        row.setHost(trimToNull(request.getHost()));
        row.setPort(request.getPort());
        row.setDatabaseName(trimToNull(request.getDatabaseName()));
        row.setUsername(request.getUsername().trim());
        row.setDriverClassName(trimToNull(request.getDriverClassName()));
        row.setJdbcUrl(trimToNull(request.getJdbcUrl()));
        row.setJdbcParams(trimToNull(request.getJdbcParams()));
        row.setDialect(normalizeDialect(request.getDialect(), request.getDbType()));
        row.setReadOnly(request.getReadOnly() == null || Boolean.TRUE.equals(request.getReadOnly()));
        row.setMaxPoolSize(normalizePoolSize(request.getMaxPoolSize()));
        row.setConnectionTimeoutMillis(normalizeTimeout(request.getConnectionTimeoutMillis()));
        row.setInitScript(trimToNull(request.getInitScript()));
        row.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
    }

    private String normalizeDbType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            throw new BadRequestException("数据库类型不能为空");
        }
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "mariadb" -> "mysql";
            case "pg", "postgres" -> "postgresql";
            default -> {
                if (type.length() > 32) {
                    throw new BadRequestException("数据库类型不能超过 32 个字符");
                }
                yield type;
            }
        };
    }

    private String normalizeDialect(String rawDialect, String rawDbType) {
        if (!StringUtils.hasText(rawDialect)) {
            return normalizeDbType(rawDbType);
        }
        String dialect = rawDialect.trim().toLowerCase(Locale.ROOT);
        if (dialect.length() > 32) {
            throw new BadRequestException("方言提示不能超过 32 个字符");
        }
        return dialect;
    }

    private boolean requiresHost(String dbType) {
        return "mysql".equals(dbType) || "postgresql".equals(dbType);
    }

    private int normalizePoolSize(Integer value) {
        if (value == null) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        if (value < 1 || value > 20) {
            throw new BadRequestException("连接池最大连接数需在 1-20 之间");
        }
        return value;
    }

    private long normalizeTimeout(Long value) {
        if (value == null) {
            return DEFAULT_CONNECTION_TIMEOUT_MILLIS;
        }
        if (value < 1000 || value > 120_000) {
            throw new BadRequestException("连接超时需在 1000-120000 毫秒之间");
        }
        return value;
    }

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizeSize(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private Integer parseUserStatus(String status) {
        if (!StringUtils.hasText(status) || "all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "pending", "2" -> SysUser.STATUS_PENDING;
            case "active", "1" -> SysUser.STATUS_ACTIVE;
            case "disabled", "0" -> SysUser.STATUS_DISABLED;
            default -> throw new BadRequestException("不支持的用户状态筛选: " + status);
        };
    }

    private Boolean parseEnabled(String enabled) {
        if (!StringUtils.hasText(enabled) || "all".equalsIgnoreCase(enabled.trim())) {
            return null;
        }
        return switch (enabled.trim().toLowerCase(Locale.ROOT)) {
            case "enabled", "true", "1" -> Boolean.TRUE;
            case "disabled", "false", "0" -> Boolean.FALSE;
            default -> throw new BadRequestException("不支持的数据源状态筛选: " + enabled);
        };
    }

    private void checkLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new BadRequestException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireAdmin(AuthUser actor) {
        if (actor == null || !"ADMIN".equalsIgnoreCase(actor.role())) {
            throw new ForbiddenException("仅管理员可执行该操作");
        }
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在: " + userId);
        }
        return user;
    }

    private DataSourceConfig requireDataSource(Long dataSourceId) {
        DataSourceConfig source = dataSourceId == null
                ? null : dataSourceConfigMapper.selectById(dataSourceId);
        if (source == null) {
            throw new NotFoundException("数据源不存在: " + dataSourceId);
        }
        return source;
    }

    private AdminUserView toUserView(SysUser user) {
        return AdminUserView.from(user, userDataSourceMapper.countByUser(user.getId()));
    }

    private AdminDataSourceView toDataSourceView(DataSourceConfig row) {
        return AdminDataSourceView.from(row,
                userDataSourceMapper.countByDataSource(row.getId()));
    }
}



