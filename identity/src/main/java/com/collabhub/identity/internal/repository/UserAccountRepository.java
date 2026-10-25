package com.collabhub.identity.internal.repository;

import com.collabhub.identity.internal.domain.UserStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 账号与凭证的持久化。
 *
 * <p>两个刻意的选择：
 *
 * <p><b>1. 时间统一按 UTC 写入。</b>
 * 表里的 {@code DATETIME} 不带时区，如果交给驱动按连接时区转换，
 * 换台服务器就可能差 8 小时。所以这里显式把 {@link Instant} 转成 UTC 的
 * {@link LocalDateTime} 再写，读出来也按 UTC 解释 —— 语义不依赖任何连接配置。
 *
 * <p><b>2. 不做"先查再插"。</b>
 * 查重再插入存在竞态：两个请求同时通过检查，然后一起插入。
 * 邮箱唯一性由唯一索引 {@code uk_user_account_email} 保证，
 * 冲突时抛 {@link DuplicateKeyException}，由上层翻译成业务错误。
 *
 * <p>不负责：密码哈希与校验、令牌签发、业务规则判断 —— 那些在 Service 层。
 */
@Repository
public class UserAccountRepository {

    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入账号，返回自增主键。
     *
     * @throws DuplicateKeyException 邮箱已存在（大小写不敏感由 collation 负责）
     */
    public long insertAccount(String email, String displayName, UserStatus status, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime utcNow = utc(now);

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO user_account (email, display_name, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, displayName);
            ps.setString(3, status.name());
            // 用 setObject 而不是 setTimestamp：setTimestamp 会把 Instant 按 JVM 默认时区
            // 和连接时区各转换一次，两个时区不一致时算出来的时间就是错的。
            // LocalDateTime 会被驱动原样写进 DATETIME，语义最干净。
            ps.setObject(4, utcNow);
            ps.setObject(5, utcNow);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 user_account 后没有拿到自增主键");
        }
        return key.longValue();
    }

    /** 插入密码凭证。一个用户每种凭证类型只有一条（唯一索引兜底）。 */
    public void insertPasswordCredential(long userId, String passwordHash, Instant now) {
        LocalDateTime utcNow = utc(now);
        jdbc.update("""
                        INSERT INTO user_credential
                            (user_id, credential_type, password_hash, password_updated_at, created_at, updated_at)
                        VALUES (?, 'PASSWORD', ?, ?, ?, ?)
                        """,
                userId, passwordHash, utcNow, utcNow, utcNow);
    }

    /** 按邮箱查用户 ID，主要给测试和后续登录用。 */
    public Long findIdByEmail(String email) {
        var ids = jdbc.queryForList(
                "SELECT id FROM user_account WHERE email = ? AND deleted_at IS NULL",
                Long.class, email);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * 登录时一次性取出「账号状态 + 锁定信息 + 密码哈希」。
     *
     * <p>为什么用一次 JOIN 而不是分两次查：登录是高频路径，少一次数据库往返就少一次
     * 网络抖动和上下文切换。而且这些字段必须来自同一时刻的快照，分开查可能读到不一致的数据。
     *
     * <p>返回 {@link Optional#empty()} 表示<b>邮箱不存在或已软删除</b> ——
     * 调用方不能把这个信息直接告诉客户端（那等于给了撞库者一个"账号是否存在"的探测器）。
     */
    public Optional<AuthRow> findAuthByEmail(String email) {
        List<AuthRow> rows = jdbc.query("""
                SELECT a.id, a.email, a.display_name, a.status,
                       a.failed_login_count, a.locked_until, a.created_at,
                       c.password_hash
                FROM user_account a
                LEFT JOIN user_credential c
                       ON c.user_id = a.id AND c.credential_type = 'PASSWORD'
                WHERE a.email = ? AND a.deleted_at IS NULL
                """, (rs, rowNum) -> new AuthRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getInt("failed_login_count"),
                instant(rs.getObject("locked_until", LocalDateTime.class)),
                instant(rs.getObject("created_at", LocalDateTime.class)),
                rs.getString("password_hash")
        ), email);

        return rows.stream().findFirst();
    }

    /**
     * 登录失败：失败次数 +1，返回累加后的值。
     *
     * <p>为什么用 {@code failed_login_count = failed_login_count + 1} 这种写法而不是
     * "先读出来加一再写回去"：后者在并发下会丢计数（两个请求都读到 3，都写回 4），
     * 攻击者可以用并发把 5 次锁定绕过去。交给数据库做自增是原子操作。
     *
     * @return 累加后的失败次数（用于判断是否触达锁定阈值）
     */
    public int incrementFailedLogin(long userId, Instant now) {
        jdbc.update("""
                        UPDATE user_account
                        SET failed_login_count = failed_login_count + 1, updated_at = ?
                        WHERE id = ?
                        """,
                utc(now), userId);

        Integer count = jdbc.queryForObject(
                "SELECT failed_login_count FROM user_account WHERE id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 触达阈值后锁定账号到指定时刻。 */
    public void lockUntil(long userId, Instant until, Instant now) {
        jdbc.update("""
                        UPDATE user_account SET locked_until = ?, updated_at = ? WHERE id = ?
                        """,
                utc(until), utc(now), userId);
    }

    /**
     * 登录成功：清零失败计数、解除锁定、记录本次登录时间。
     *
     * <p>三个字段必须一起更新：只清零不解除锁定，用户会被继续挡在门外；
     * 只解除锁定不清零，下次再错一次就立刻又被锁。
     */
    public void markLoginSucceeded(long userId, Instant now) {
        jdbc.update("""
                        UPDATE user_account
                        SET failed_login_count = 0, locked_until = NULL,
                            last_login_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                utc(now), utc(now), userId);
    }

    // ---------------------------------------------------------------- 时间

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
     * <p>{@code passwordHash} 可能为 null：用户若有账号但没设密码（比如只绑定了
     * OAuth），此时任何密码都不应该能登录 —— Service 里要把 null 当作"永远比对失败"。
     *
     * @param lockedUntil 为 null 表示未锁定
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
