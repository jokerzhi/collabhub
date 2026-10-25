package com.collabhub.identity.internal.domain;

/**
 * 账号状态。
 *
 * <p>用 Java enum 而不是数据库 ENUM：数据库里存 VARCHAR，
 * 将来加状态值不用 ALTER TABLE。
 *
 * <p>状态流转（M2 只实现前两步）：
 * <pre>
 *   PENDING  --验证邮箱-->  ACTIVE
 *   ACTIVE   --违规封禁-->  SUSPENDED
 *   ACTIVE   --用户注销-->  DELETED（软删除，冷静期后物理清理）
 * </pre>
 */
public enum UserStatus {

    /** 已注册，但还没验证邮箱。能登录，但很多操作受限。 */
    PENDING,

    /** 正常可用。 */
    ACTIVE,

    /** 被封禁。 */
    SUSPENDED,

    /** 已注销。 */
    DELETED
}
