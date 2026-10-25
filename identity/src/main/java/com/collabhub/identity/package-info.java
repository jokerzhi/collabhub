/**
 * 身份与账号模块（Identity）。
 *
 * <p>负责：注册、登录、JWT 令牌、会话与设备、个人资料、第三方登录。
 * 对应需求 FR-AUTH-001 ~ FR-AUTH-012。
 *
 * <p>对外只暴露 {@code api} 子包里的接口和 DTO，其余包（{@code internal}）禁止跨模块访问，
 * 由 ArchUnit 与 Spring Modulith 双重守卫。
 *
 * <p>数据库版本号段：V1001 ~ V1999。
 */
package com.collabhub.identity;
