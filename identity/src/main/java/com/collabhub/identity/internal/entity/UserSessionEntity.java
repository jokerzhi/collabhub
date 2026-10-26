package com.collabhub.identity.internal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code user_session} 表的映射对象。
 *
 * <p>一条记录 = 一个 refresh token = 一台已登录的设备。
 * 存的是令牌的 SHA-256 哈希，不是原文 —— 库被拖走也换不出可用令牌。
 *
 * <p>字段含义与时间语义见 {@link UserAccountEntity} 的类注释。
 */
@TableName("user_session")
public class UserSessionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** refresh token 的 SHA-256 十六进制（64 字符），唯一索引。 */
    private String refreshTokenHash;

    private String deviceName;

    /** IPv6 最长 45 字符。仅用于展示，不能作为访问控制依据。 */
    private String ipAddress;

    private String userAgent;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime expiresAt;

    /** 非空表示已吊销（登出 / 改密 / 检测到重放）。 */
    private LocalDateTime revokedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public void setRefreshTokenHash(String refreshTokenHash) {
        this.refreshTokenHash = refreshTokenHash;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
