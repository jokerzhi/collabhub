package com.collabhub.identity.internal.service;

import com.collabhub.identity.api.LoginRequest;
import com.collabhub.identity.api.LoginResponse;
import com.collabhub.identity.api.UserView;
import com.collabhub.identity.internal.repository.UserAccountRepository;
import com.collabhub.identity.internal.repository.UserAccountRepository.AuthRow;
import com.collabhub.identity.internal.repository.UserSessionRepository;
import com.collabhub.identity.internal.security.JwtTokenIssuer;
import com.collabhub.identity.internal.security.RefreshTokenFactory;
import com.collabhub.platform.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * 登录：校验凭证、维护失败计数与锁定、签发双令牌、落一条会话。
 *
 * <p>这个类里有两个安全细节值得单独说明：
 *
 * <p><b>1. 不泄露"邮箱是否存在"。</b>
 * 用户不存在、密码错误，返回的 HTTP 状态码、错误码、错误文案<b>完全一样</b>。
 * 否则攻击者可以先批量探测哪些邮箱注册过，再针对性地撞库。
 *
 * <p><b>2. 时间也要对齐。</b>
 * 账号不存在时如果直接返回，响应会明显快于"账号存在但密码错"（后者要跑一次 Argon2，约 50ms）。
 * 攻击者靠响应时间差照样能判断邮箱是否注册。所以用户不存在时也拿一个假的哈希跑一次
 * {@code matches()}，把两条路径的耗时拉到同一量级。这不是理论洁癖——
 * 计时侧信道是真实可用的攻击手段。
 *
 * <p>不负责：注册、令牌校验、会话吊销。
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    /**
     * 所有"凭证无效"场景共用的文案与错误码。
     * 改这句话之前想清楚：任何更具体的措辞都会变成撞库者的探测器。
     */
    private static final String INVALID_CREDENTIALS_CODE = "INVALID_CREDENTIALS";
    private static final String INVALID_CREDENTIALS_MESSAGE = "邮箱或密码错误";

    private final UserAccountRepository accounts;
    private final UserSessionRepository sessions;
    private final LoginAttemptRecorder loginAttemptRecorder;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final RefreshTokenFactory refreshTokenFactory;

    private final int maxFailedAttempts;
    private final Duration lockDuration;
    private final Duration refreshTtl;

    /**
     * 用于时间对齐的假哈希。
     *
     * <p>在构造时随机生成：既保证是一个格式合法的 Argon2 串（能让 matches() 真正跑完
     * 一次哈希计算），又保证它不可能匹配任何用户密码。
     */
    private final String dummyHash;

    public LoginService(UserAccountRepository accounts,
                        UserSessionRepository sessions,
                        LoginAttemptRecorder loginAttemptRecorder,
                        PasswordEncoder passwordEncoder,
                        JwtTokenIssuer jwtTokenIssuer,
                        RefreshTokenFactory refreshTokenFactory,
                        @Value("${app.security.login.max-failed-attempts:5}") int maxFailedAttempts,
                        @Value("${app.security.login.lock-duration:PT15M}") Duration lockDuration,
                        @Value("${app.security.login.refresh-ttl:P7D}") Duration refreshTtl) {
        this.accounts = requireNonNull(accounts);
        this.sessions = requireNonNull(sessions);
        this.loginAttemptRecorder = requireNonNull(loginAttemptRecorder);
        this.passwordEncoder = requireNonNull(passwordEncoder);
        this.jwtTokenIssuer = requireNonNull(jwtTokenIssuer);
        this.refreshTokenFactory = requireNonNull(refreshTokenFactory);
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.refreshTtl = refreshTtl;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-" + UUID.randomUUID());
    }

    /**
     * 执行登录。
     *
     * <p>整个方法在一个事务里：失败计数、锁定、会话写入要么都生效，要么都不生效。
     * 否则可能出现"会话写进去了但失败计数没清零"之类的脏状态。
     *
     * @param request 登录请求（邮箱 + 明文密码）
     * @param client  客户端信息，仅用于会话记录
     * @return 双令牌 + 用户信息
     * @throws BusinessException 401 凭证无效 / 429 账号锁定 / 403 账号被禁用
     */
    @Transactional
    public LoginResponse login(LoginRequest request, ClientInfo client) {
        // 和注册保持一致：邮箱统一小写再查，避免大小写差异导致"登录不上但注册时能用"
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        Optional<AuthRow> maybeUser = accounts.findAuthByEmail(email);
        if (maybeUser.isEmpty()) {
            // 账号不存在也要消耗一次哈希时间，见类注释第 2 点
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }

        AuthRow user = maybeUser.get();

        // 锁定检查放在密码校验之前：已锁定的账号不应该再消耗一次 Argon2 计算，
        // 否则攻击者可以用锁定账号做 CPU 消耗攻击
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "登录失败次数过多，请 " + lockDuration.toMinutes() + " 分钟后再试");
        }

        // 只有 PENDING（未验证邮箱）和 ACTIVE 能登录。被封禁/已注销的账号直接拒绝
        if (!"PENDING".equals(user.status()) && !"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已被禁用");
        }

        // 没有密码凭证的账号（例如只绑定了 OAuth）永远不能用密码登录。
        // 这里额外用假哈希跑一次，保证这条路径的耗时和其它路径一致
        String storedHash = user.passwordHash();
        boolean passwordMatches;
        if (storedHash == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            passwordMatches = false;
        } else {
            passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        }

        if (!passwordMatches) {
            throw failedAttempt(user, now);
        }

        return issueTokens(user, client, now);
    }

    /**
     * 记录一次失败并返回要抛出的异常；达到阈值时账号已被锁定。
     *
     * <p>为什么返回异常而不是自己抛：调用点写成 {@code throw failedAttempt(...)} 时，
     * 读代码的人一眼就能看出这条分支一定结束、不会继续往下签发令牌。
     *
     * <p>计数与锁定交给 {@link LoginAttemptRecorder} 用<b>独立事务</b>提交 ——
     * 否则这里抛异常会让主事务回滚，失败次数等于没记（详见该类的注释）。
     */
    private BusinessException failedAttempt(AuthRow user, Instant now) {
        LoginAttemptRecorder.FailureOutcome outcome =
                loginAttemptRecorder.recordFailure(user.id(), now, maxFailedAttempts, lockDuration);

        if (outcome.locked()) {
            // 锁定是安全事件，用 warn 级别；但只记 userId，不记邮箱和密码（日志也会被别人看到）
            log.warn("账号登录失败达到 {} 次，已锁定至 {}，userId={}",
                    outcome.failedAttempts(), outcome.lockedUntil(), user.id());

            return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "登录失败次数过多，账号已锁定 " + lockDuration.toMinutes() + " 分钟");
        }

        return invalidCredentials();
    }

    /** 登录成功：清零失败计数、签发令牌、落会话。 */
    private LoginResponse issueTokens(AuthRow user, ClientInfo client, Instant now) {
        accounts.markLoginSucceeded(user.id(), now);

        JwtTokenIssuer.IssuedAccessToken access = jwtTokenIssuer.issue(user.id(), now);
        RefreshTokenFactory.RefreshToken refresh = refreshTokenFactory.issue();

        sessions.insert(user.id(), refresh.hash(),
                client == null ? null : client.ipAddress(),
                client == null ? null : client.userAgent(),
                now, now.plus(refreshTtl));

        log.info("登录成功 userId={} ip={}", user.id(), client == null ? "-" : client.ipAddress());

        return new LoginResponse(
                access.token(),
                refresh.raw(),
                "Bearer",
                jwtTokenIssuer.accessTtl().toSeconds(),
                new UserView(user.id(), user.email(), user.displayName(), user.status(), user.createdAt()));
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED,
                INVALID_CREDENTIALS_CODE, INVALID_CREDENTIALS_MESSAGE);
    }
}
