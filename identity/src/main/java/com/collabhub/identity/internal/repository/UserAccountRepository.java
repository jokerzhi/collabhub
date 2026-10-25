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

    // ---------------------------------------------------------------- 时间

    /** Instant -> UTC 的 LocalDateTime，不经过任何时区转换。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
