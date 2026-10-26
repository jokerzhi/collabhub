package com.collabhub.identity.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.collabhub.identity.internal.domain.UserStatus;
import com.collabhub.identity.internal.entity.UserAccountEntity;
import com.collabhub.identity.internal.entity.UserCredentialEntity;
import com.collabhub.identity.internal.mapper.UserAccountMapper;
import com.collabhub.identity.internal.mapper.UserCredentialMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 账号与凭证的持久化。
 *
 * <p>本类是一层<b>薄适配</b>：把 MyBatis-Plus 的实体和 SQL 细节挡在模块内部，
 * 对外只暴露领域语义的方法，出入参继续用 {@code Instant} / {@code record}。
 * 好处是 Service 层完全不知道持久层用的是 MyBatis 还是别的，换 ORM 只动这个文件。
 *
 * <p>三条贯穿全类的约定：
 *
 * <p><b>1. 时间统一按 UTC 写。</b>
 * 表里的 {@code DATETIME} 不带时区，交给驱动转换会随服务器时区漂移。
 * 所以这里显式在 {@link Instant} 与 UTC 的 {@link LocalDateTime} 之间转换，
 * 语义不依赖任何连接配置。
 *
 * <p><b>2. 不做"先查再插"。</b>
 * 查重再插入有竞态。邮箱唯一性由唯一索引 {@code uk_user_account_email} 保证，
 * 冲突时抛 {@link DuplicateKeyException}，由上层翻译成业务错误。
 *
 * <p><b>3. 软删除始终显式判断。</b>
 * 我们没有启用 MyBatis-Plus 的逻辑删除（{@code deleted_at} 是时间戳，插件的值语义对不上），
 * 所以每条查询都要自己带 {@code deleted_at IS NULL}。这是本项目最容易漏的地方，写新查询时务必检查。
 *
 * <p>不负责：密码哈希与校验、令牌签发、业务规则判断。
 */
@Repository
public class UserAccountRepository {

    private final UserAccountMapper accountMapper;
    private final UserCredentialMapper credentialMapper;

    public UserAccountRepository(UserAccountMapper accountMapper, UserCredentialMapper credentialMapper) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
    }

    /**
     * 插入账号，返回自增主键。
     *
     * <p>对比旧实现：以前要手写 INSERT 加 {@code GeneratedKeyHolder} 才能拿到主键，
     * 现在 {@code insert} 之后主键会被 MyBatis-Plus 直接回填到实体上。
     *
     * @throws DuplicateKeyException 邮箱已存在（大小写不敏感由 collation 负责）
     */
    public long insertAccount(String email, String displayName, UserStatus status, Instant now) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setEmail(email);
        entity.setDisplayName(displayName);
        entity.setStatus(status.name());
        // 显式给 0，不依赖数据库默认值：插入语句和表结构解耦，以后改默认值不影响这里的行为
        entity.setFailedLoginCount(0);
        entity.setCreatedAt(utc(now));
        entity.setUpdatedAt(utc(now));

        accountMapper.insert(entity);

        if (entity.getId() == null) {
            throw new IllegalStateException("插入 user_account 后没有拿到自增主键");
        }
        return entity.getId();
    }

    /** 插入密码凭证。一个用户每种凭证类型只有一条（唯一索引兜底）。 */
    public void insertPasswordCredential(long userId, String passwordHash, Instant now) {
        UserCredentialEntity entity = new UserCredentialEntity();
        entity.setUserId(userId);
        entity.setCredentialType("PASSWORD");
        entity.setPasswordHash(passwordHash);
        entity.setPasswordUpdatedAt(utc(now));
        entity.setCreatedAt(utc(now));
        entity.setUpdatedAt(utc(now));

        credentialMapper.insert(entity);
    }

    /** 按邮箱查用户 ID，主要给测试和后续登录用。 */
    public Long findIdByEmail(String email) {
        UserAccountEntity entity = accountMapper.selectOne(
                new LambdaQueryWrapper<UserAccountEntity>()
                        .select(UserAccountEntity::getId)
                        .eq(UserAccountEntity::getEmail, email)
                        .isNull(UserAccountEntity::getDeletedAt));

        return entity == null ? null : entity.getId();
    }

    /**
     * 登录时一次性取出「账号状态 + 锁定信息 + 密码哈希」。
     *
     * <p>返回 {@link Optional#empty()} 表示<b>邮箱不存在或已软删除</b> ——
     * 调用方不能把这个信息告诉客户端（那等于给了撞库者一个账号探测器）。
     */
    public Optional<AuthRow> findAuthByEmail(String email) {
        UserAccountEntity entity = accountMapper.selectAuthByEmail(email);
        return Optional.ofNullable(entity).map(UserAccountRepository::toAuthRow);
    }

    /**
     * 登录失败：失败次数 +1，返回累加后的值。
     *
     * <p>自增在 SQL 里完成（原子操作），然后在同一事务里读回。
     *
     * @return 累加后的失败次数，用于判断是否触达锁定阈值
     */
    public int incrementFailedLogin(long userId, Instant now) {
        accountMapper.incrementFailedLogin(userId, utc(now));

        UserAccountEntity entity = accountMapper.selectById(userId);
        if (entity == null || entity.getFailedLoginCount() == null) {
            throw new IllegalStateException("账号不存在或 failed_login_count 为 null, userId=" + userId);
        }
        return entity.getFailedLoginCount();
    }

    /** 触达阈值后锁定账号到指定时刻。 */
    public void lockUntil(long userId, Instant until, Instant now) {
        // 只设 id + 要改的字段：MyBatis-Plus 默认"非 null 才更新"，
        // 其它字段不会被意外覆盖成 null
        UserAccountEntity patch = new UserAccountEntity();
        patch.setId(userId);
        patch.setLockedUntil(utc(until));
        patch.setUpdatedAt(utc(now));

        accountMapper.updateById(patch);
    }

    /**
     * 登录成功：清零失败计数、解除锁定、记录本次登录时间。
     *
     * <p>用 Mapper 里的专用 SQL 而不是 {@code updateById}，因为要把
     * {@code locked_until} 改回 NULL —— 而 updateById 会忽略 null 字段。
     */
    public void markLoginSucceeded(long userId, Instant now) {
        accountMapper.markLoginSucceeded(userId, utc(now));
    }

    // ---------------------------------------------------------------- 转换

    private static AuthRow toAuthRow(UserAccountEntity e) {
        return new AuthRow(
                e.getId(),
                e.getEmail(),
                e.getDisplayName(),
                e.getStatus(),
                e.getFailedLoginCount() == null ? 0 : e.getFailedLoginCount(),
                instant(e.getLockedUntil()),
                instant(e.getCreatedAt()),
                e.getPasswordHash());
    }

    /** Instant -> UTC 的 LocalDateTime，不经过任何时区转换。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** DB 里的 DATETIME 按 UTC 解释，转回 Instant。NULL 保持 NULL。 */
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 登录需要的账号快照。
     *
     * <p>这是<b>读模型</b>，所以用不可变的 record；而 {@link UserAccountEntity}
     * 是<b>写模型</b>（要被框架读写），所以用可变 POJO。职责不同，形式也不同。
     *
     * @param passwordHash 可能为 null：账号只绑定了 OAuth 时没有密码凭证
     * @param lockedUntil  为 null 表示未锁定
     */
    public record AuthRow(
            long id,
            String email,
            String displayName,
            String status,
            int failedLoginCount,
            Instant lockedUntil,
            Instant createdAt,
            String passwordHash
    ) {
    }
}
