package com.collabhub.identity.internal.service;

import com.collabhub.identity.internal.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 登录失败计数与锁定。
 *
 * <p><b>为什么这个类必须单独存在、而且必须用 {@link Propagation#REQUIRES_NEW}：</b>
 *
 * <p>登录失败时，Service 会抛异常告诉调用方"凭证无效"。而登录主流程是
 * {@code @Transactional} 的 —— 异常一抛，事务回滚。如果失败计数和主流程在同一个事务里，
 * 计数就会被一起回滚掉：<b>攻击者可以无限次试密码，因为每一次失败都没被记住。</b>
 *
 * <p>这个 bug 在代码里看不出来（UPDATE 明明执行了），只有测试能抓到。
 * 第一次写的时候就是踩了这个坑，测试 `<em>successfulLoginResetsFailedCounter</em>` 报了
 * "期望 2，实际 0"。
 *
 * <p>解法是让计数走独立事务：不管外面成功还是失败，这次尝试都要被记下来。
 * 独立性是有代价的（多一个数据库连接、短时间内的行锁竞争），
 * 但"失败次数必须可靠"比这点代价重要得多。
 *
 * <p>不负责：判断凭证是否正确、抛出业务异常 —— 那些在 {@link LoginService}。
 */
@Service
public class LoginAttemptRecorder {

    private final UserAccountRepository accounts;

    public LoginAttemptRecorder(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * 记录一次失败；达到阈值时顺手锁定。
     *
     * <p>计数自增和锁定写在同一个独立事务里，保证"第 5 次失败"这个瞬间，
     * 计数和锁定状态是一致的，不会出现"计数到 5 但没锁上"。
     *
     * @param userId        用户 ID
     * @param now           当前时刻（由调用方传入，避免两次取时间导致边界误差）
     * @param maxAttempts   允许的最大连续失败次数
     * @param lockDuration  锁定时长
     * @return 本次记录的结果，调用方据此决定返回 401 还是 429
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureOutcome recordFailure(long userId, Instant now, int maxAttempts, Duration lockDuration) {
        int failedAttempts = accounts.incrementFailedLogin(userId, now);

        if (failedAttempts >= maxAttempts) {
            Instant lockedUntil = now.plus(lockDuration);
            accounts.lockUntil(userId, lockedUntil, now);
            return new FailureOutcome(failedAttempts, true, lockedUntil);
        }

        return new FailureOutcome(failedAttempts, false, null);
    }

    /**
     * 一次失败记录的结果。
     *
     * @param failedAttempts 累计失败次数（含本次）
     * @param locked         是否因为本次而触发锁定
     * @param lockedUntil    锁定到期时刻，未锁定时为 null
     */
    public record FailureOutcome(int failedAttempts, boolean locked, Instant lockedUntil) {
    }
}
