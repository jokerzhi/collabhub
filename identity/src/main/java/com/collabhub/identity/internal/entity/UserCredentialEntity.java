package com.collabhub.identity.internal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code user_credential} 表的映射对象。
 *
 * <p>一张表同时承载多种登录方式（密码、将来的 GitHub / Google OAuth），
 * 用 {@code credentialType} 区分。唯一索引是 {@code (user_id, credential_type)}，
 * 所以同一用户每种方式只能有一条 —— 改密码是 UPDATE，不是新增一行。
 *
 * <p>字段含义与时间语义见 {@link UserAccountEntity} 的类注释。
 */
@TableName("user_credential")
public class UserCredentialEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** PASSWORD / OAUTH_GITHUB / OAUTH_GOOGLE。 */
    private String credentialType;

    /** Argon2id 编码串，含算法参数与盐。绝不存明文，也绝不返回给接口。 */
    private String passwordHash;

    private LocalDateTime passwordUpdatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    public String getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(String credentialType) {
        this.credentialType = credentialType;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public void setPasswordUpdatedAt(LocalDateTime passwordUpdatedAt) {
        this.passwordUpdatedAt = passwordUpdatedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
