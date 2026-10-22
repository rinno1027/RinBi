package org.rin.config;

import org.rin.auth.PasswordHasher;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.entity.UserDataSource;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.db.mapper.UserDataSourceMapper;
import org.rin.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 首次启动初始化：创建默认管理员，并按需为其挂载 H2 演示数据源。
 *
 * <p>所有操作都是幂等的：账号已存在时跳过；演示数据源已存在时复用，管理员未授权时补建授权关系。
 * 生产环境可通过 {@code agent.bootstrap.enabled=false} 关闭。</p>
 */
@Component
public class BootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);
    private static final String DEMO_KEY = "demo";
    private static final String DEMO_URL =
            "jdbc:h2:mem:rinbi_demo;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE";

    private final AgentProperties properties;
    private final SysUserMapper userMapper;
    private final DataSourceConfigMapper dataSourceMapper;
    private final UserDataSourceMapper userDataSourceMapper;
    private final PasswordHasher passwordHasher;
    private final SecretCipher secretCipher;

    public BootstrapInitializer(AgentProperties properties,
                                SysUserMapper userMapper,
                                DataSourceConfigMapper dataSourceMapper,
                                UserDataSourceMapper userDataSourceMapper,
                                PasswordHasher passwordHasher,
                                SecretCipher secretCipher) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.userDataSourceMapper = userDataSourceMapper;
        this.passwordHasher = passwordHasher;
        this.secretCipher = secretCipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        AgentProperties.Bootstrap bootstrap = properties.getBootstrap();
        if (bootstrap == null || !Boolean.TRUE.equals(bootstrap.getEnabled())) {
            return;
        }
        if (!StringUtils.hasText(bootstrap.getAdminUsername())
                || !StringUtils.hasText(bootstrap.getAdminPassword())) {
            log.warn("已开启 bootstrap，但缺少 admin-username 或 admin-password，跳过初始化");
            return;
        }
        try {
            SysUser admin = ensureAdmin(bootstrap);
            if (admin != null && Boolean.TRUE.equals(bootstrap.getDemoDatasourceEnabled())) {
                ensureDemoDataSource(admin);
            }
        } catch (RuntimeException e) {
            // 初始化失败不应阻止服务启动，用户可以手工执行 db/rinbidb-schema.sql。
            log.warn("初始化默认账号或演示数据源失败: {}", e.getMessage());
        }
    }

    private SysUser ensureAdmin(AgentProperties.Bootstrap bootstrap) {
        String username = bootstrap.getAdminUsername().trim();
        SysUser existing = userMapper.selectByUsername(username);
        if (existing != null) {
            log.info("默认管理员 {} 已存在，跳过创建", username);
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordHasher.hash(bootstrap.getAdminPassword()));
        admin.setDisplayName(StringUtils.hasText(bootstrap.getAdminDisplayName())
                ? bootstrap.getAdminDisplayName() : username);
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userMapper.insert(admin);
        log.info("已创建默认管理员 {}，请尽快修改默认密码", username);
        return admin;
    }

    private void ensureDemoDataSource(SysUser admin) {
        DataSourceConfig demo = dataSourceMapper.selectByDbKey(DEMO_KEY);
        if (demo == null) {
            LocalDateTime now = LocalDateTime.now();
            demo = new DataSourceConfig();
            demo.setDbKey(DEMO_KEY);
            demo.setName("演示数据库");
            demo.setDescription("内置 H2 内存演示库，包含客户、商品与订单示例数据");
            demo.setDbType("h2");
            demo.setJdbcUrl(DEMO_URL);
            demo.setDriverClassName("org.h2.Driver");
            demo.setUsername("sa");
            demo.setPassword(secretCipher.encrypt(""));
            demo.setDialect("mysql");
            demo.setReadOnly(true);
            demo.setMaxPoolSize(4);
            demo.setConnectionTimeoutMillis(10_000L);
            demo.setInitScript("db/demo.sql");
            demo.setEnabled(true);
            demo.setCreatedAt(now);
            demo.setUpdatedAt(now);
            dataSourceMapper.insert(demo);
            log.info("已创建演示数据源 {}", DEMO_KEY);
        }
        if (userDataSourceMapper.countByUserAndDataSource(admin.getId(), demo.getId()) == 0) {
            UserDataSource grant = new UserDataSource();
            grant.setUserId(admin.getId());
            grant.setDataSourceId(demo.getId());
            grant.setCreatedAt(LocalDateTime.now());
            userDataSourceMapper.insert(grant);
            log.info("已为管理员 {} 授权演示数据源 {}", admin.getUsername(), DEMO_KEY);
        }
    }
}
