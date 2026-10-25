package com.collabhub.identity.api;

import java.time.Instant;

/**
 * 对外的用户视图。
 *
 * <p>这个 record 存在的唯一目的：<b>精确控制哪些字段能被序列化出去</b>。
 * 密码哈希、锁定计数、内部状态都不在这里，所以不可能"不小心"泄露。
 *
 * <p>不要图省事直接返回实体对象 —— 那是数据泄露最常见的来源。
 *
 * @param id          用户 ID
 * @param email       邮箱（已统一小写）
 * @param displayName 展示名
 * @param status      账号状态，注册后是 PENDING（待邮箱验证）
 * @param createdAt   创建时间
 */
public record UserView(
        Long id,
        String email,
        String displayName,
        String status,
        Instant createdAt
) {
}
