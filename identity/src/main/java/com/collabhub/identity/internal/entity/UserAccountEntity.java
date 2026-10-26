package com.collabhub.identity.internal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code user_account} 表的映射对象。
 *
 * <p><b>为什么这里是 class 而不是 record：</b>
 * MyBatis-Plus 的 {@code BaseMapper} 需要一个无参构造 + setter，才能把查询结果
 * 反射填进对象、也才能生成 insert 语句。record 是不可变的，填不进去。
 *
 * <p>这与 AGENTS.md 的「record 优先」不冲突 —— 那条约定针对的是<b>对外 DTO 和查询结果</b>。
 * 实体是「表结构的镜像」，它的职责是被框架读写，用可变 POJO 是工具约束，不是设计退步。
 *
 * <p>不加 Lombok（项目约定），getter/setter 手写。这些方法没有逻辑，
 * 一眼能看出在干什么，所以不加注释。
 *
 * <p>时间字段都是 {@link LocalDateTime} 且语义为 <b>UTC</b>：
 * 表里存的是不带时区的 DATETIME，Repository 层负责 Instant ↔ UTC LocalDateTime 的转换。
 */
@TableName("user_account")
public class UserAccountEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String email;

    private String displayName;

    private String avatarUrl;

    /** PENDING / ACTIVE / SUSPENDED / DELETED，见 {@code UserStatus}。 */
    private String status;

    private LocalDateTime emailVerifiedAt;

    private String timeZone;

    private String locale;

    private Integer failedLoginCount;

    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 非空表示已软删除。查询时必须显式加 {@code deleted_at IS NULL}。 */
    private LocalDateTime deletedAt;

    /**
     * 密码哈希，来自 {@code user_credential} 表，<b>不是本表的列</b>。
     *
     * <p>{@code exist = false} 告诉 MyBatis-Plus：不要在 insert / update / 自动查询里
     * 用到这个字段。它只由登录用的联表查询（{@code UserAccountMapper#selectAuthByEmail}）
     * 填充，其它查询里恒为 null。
     */
    @TableField(exist = false)
    private String passwordHash;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Integer getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(Integer failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
