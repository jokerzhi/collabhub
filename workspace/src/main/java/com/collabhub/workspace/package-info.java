/**
 * 工作空间与项目模块（Workspace）。
 *
 * <p>负责：租户（工作空间）、成员与角色、邀请、项目、配额。
 * 对应需求 FR-WS-001 ~ FR-WS-009、FR-PRJ-001 ~ FR-PRJ-007。
 *
 * <p>这是多租户的根：所有业务表的 {@code workspace_id} 都指向本模块的 workspace。
 * 其他模块<b>不允许</b>直接查 workspace 的表，需要时通过本模块 {@code api} 子包暴露的接口。
 *
 * <p>数据库版本号段：V2001 ~ V2999。
 */
package com.collabhub.workspace;
