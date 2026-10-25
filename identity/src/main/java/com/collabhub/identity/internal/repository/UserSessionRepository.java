package com.collabhub.identity.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 登录会话的持久化。
 *
 * <p>一条记录 = 一个 refresh token = 一台"已登录的设备"。
 * 有了这张表才能做到：
 * <ul>
 *   <li>登出时精确吊销<b>当前</b>设备，而不是把用户所有设备踢下线</li>
 *   <li>列出"我在哪些设备上登录过"，并单独撤销某台（FR-AUTH-009）</li>
 *   <li>改密后批量吊销全部会话</li>
 * </ul>
 *
 * <p>不负责：令牌的生成与哈希（{@code RefreshTokenFactory}），
 * 也不负责判断会话是否有效（调用方查 {@code revoked_at} 和 {@code expires_at}）。
 */
@Repository
public class UserSessionRepository {

    private final JdbcTemplate jdbc;

    public UserSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条会话。
     *
     * <p>注意存的是 <b>{@code refreshTokenHash} 而不是原文</b> —— 数据库被拖走时，
     * 哈希换不出可用令牌。这也是 {@code refresh_token_hash} 上有唯一索引的原因：
     * 同一串令牌不允许出现在两个会话里。
     *
     * @param userId           用户 ID
     * @param refreshTokenHash SHA-256 十六进制（64 字符）
     * @param ipAddress        客户端 IP，仅用于展示和异常检测，不做访问控制
     * @param userAgent        原始 UA 串
     * @param createdAt        签发时刻
     * @param expiresAt        过期时刻（7 天后）
     */
    public void insert(long userId,
                       String refreshTokenHash,
                       String ipAddress,
                       String userAgent,
                       Instant createdAt,
                       Instant expiresAt) {

        jdbc.update("""
                        INSERT INTO user_session
                            (user_id, refresh_token_hash, ip_address, user_agent,
                             created_at, last_used_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                userId,
                refreshTokenHash,
                truncate(ipAddress, 45),
                truncate(userAgent, 512),
                utc(createdAt),
                utc(createdAt),
                utc(expiresAt));
    }

    /** 按哈希查会话 ID，用于测试与后续的刷新/吊销逻辑。 */
    public Long findIdByHash(String refreshTokenHash) {
        var ids = jdbc.queryForList(
                "SELECT id FROM user_session WHERE refresh_token_hash = ?",
                Long.class, refreshTokenHash);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 按列宽截断。
     *
     * <p>UA 串可以任意长（有些爬虫会塞几千字符），直接入库会被 MySQL 拒绝（严格模式），
     * 导致登录接口在"用户一切正常"的情况下 500。截断比放宽列宽更稳：
     * 我们只需要它够用来识别设备，不需要完整保真。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
