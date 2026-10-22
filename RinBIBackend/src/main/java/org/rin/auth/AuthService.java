package org.rin.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.rin.auth.entity.SysUser;
import org.rin.auth.mapper.SysUserMapper;
import org.rin.config.AgentProperties;
import org.rin.exception.BadRequestException;
import org.rin.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 登录会话管理。
 *
 * <p>token 为随机生成的不透明字符串，保存在进程内（单实例部署）。
 * 多实例部署时可替换为 Redis 或把会话落库，接口保持不变。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_HEADER = "X-Auth-Token";
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    public static final String AUTH_USER_ATTRIBUTE = "rinbi.auth.user";

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private AgentProperties properties;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleaner;

    @PostConstruct
    public void startCleaner() {
        cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auth-session-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleAtFixedRate(this::removeExpiredSessions, 10, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stopCleaner() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
    }

    /**
     * 提交注册申请。账号以「待审批」状态创建，管理员审批通过后才能登录。
     *
     * @throws BadRequestException 参数不合法或用户名已存在
     */
    public SysUser register(String username, String password, String displayName) {
        if (!StringUtils.hasText(username)) {
            throw new BadRequestException("用户名不能为空");
        }
        String normalizedUsername = username.trim();
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new BadRequestException("用户名需为 3-32 位字母、数字或下划线");
        }
        if (!StringUtils.hasText(password)) {
            throw new BadRequestException("密码不能为空");
        }
        if (password.length() < MIN_PASSWORD_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {
            throw new BadRequestException("密码长度需为 8-64 位");
        }
        String normalizedDisplayName = StringUtils.hasText(displayName)
                ? displayName.trim() : normalizedUsername;
        if (normalizedDisplayName.length() > 64) {
            throw new BadRequestException("显示名称不能超过 64 个字符");
        }
        if (userMapper.selectByUsername(normalizedUsername) != null) {
            throw new BadRequestException("用户名已存在，请更换后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordHasher.hash(password));
        user.setDisplayName(normalizedDisplayName);
        user.setRole("USER");
        user.setStatus(SysUser.STATUS_PENDING);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BadRequestException("用户名已存在，请更换后重试");
        }
        log.info("收到新用户注册申请: username={}", normalizedUsername);
        return user;
    }

    /**
     * 校验用户名密码并创建登录会话。
     *
     * @throws BadRequestException   参数为空
     * @throws UnauthorizedException 用户名/密码错误、账号待审批或账号被禁用
     */
    public LoginResult login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BadRequestException("用户名和密码不能为空");
        }
        SysUser user = userMapper.selectByUsername(username.trim());
        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() == SysUser.STATUS_PENDING) {
            throw new UnauthorizedException("账号正在等待管理员审批，请审批通过后再登录");
        }
        if (user.getStatus() != SysUser.STATUS_ACTIVE) {
            throw new UnauthorizedException("账号已被禁用，请联系管理员");
        }

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        long ttlMinutes = tokenTtlMinutes();
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60);
        AuthUser authUser = AuthUser.from(user);
        sessions.put(token, new Session(authUser, expiresAt));
        log.info("用户登录成功: username={}, ttlMinutes={}", authUser.username(), ttlMinutes);
        return new LoginResult(token, OffsetDateTime.now().plusMinutes(ttlMinutes), authUser);
    }

    /**
     * 校验 token 并返回当前用户。
     *
     * @throws UnauthorizedException 未携带 token 或 token 已过期
     */
    public AuthUser authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("请先登录");
        }
        Session session = sessions.get(token);
        if (session == null || session.expiresAt.isBefore(Instant.now())) {
            sessions.remove(token);
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        return session.user;
    }

    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            sessions.remove(token);
        }
    }

    /**
     * 管理员禁用账号后，立即吊销该用户所有在线 token。
     */
    public int revokeUserSessions(Long userId) {
        if (userId == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (Objects.equals(entry.getValue().user.id(), userId)
                    && sessions.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("已吊销用户在线会话: userId={}, count={}", userId, removed);
        }
        return removed;
    }

    /**
     * 从 Authorization: Bearer &lt;token&gt; 或 X-Auth-Token 请求头中取出 token。
     */
    public static String resolveToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        String headerToken = request.getHeader(TOKEN_HEADER);
        return StringUtils.hasText(headerToken) ? headerToken.trim() : null;
    }

    private long tokenTtlMinutes() {
        Integer configured = properties.getAuth() == null
                ? null : properties.getAuth().getTokenTtlMinutes();
        if (configured == null || configured <= 0) {
            return 720L;
        }
        return configured;
    }

    private void removeExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    /**
     * 登录结果：token、过期时间、用户信息。
     */
    public record LoginResult(String token, OffsetDateTime expiresAt, AuthUser user) {
    }

    private static final class Session {
        private final AuthUser user;
        private final Instant expiresAt;

        private Session(AuthUser user, Instant expiresAt) {
            this.user = user;
            this.expiresAt = expiresAt;
        }
    }
}
