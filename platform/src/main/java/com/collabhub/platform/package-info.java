/**
 * 技术底座。
 *
 * <p>这里放的是「所有业务模块都要用、但不属于任何业务」的东西：
 * 统一错误模型、请求上下文、可观测性装配、系统自检接口。
 *
 * <p>标记为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN} 意味着
 * 其他模块可以访问本模块的任意包 —— 它本来就是一个共享内核。
 *
 * <p>反向约束：<b>platform 不允许依赖任何业务模块</b>，由 ArchUnit 守卫。
 */
@ApplicationModule(
        type = ApplicationModule.Type.OPEN,
        displayName = "Platform"
)
package com.collabhub.platform;

import org.springframework.modulith.ApplicationModule;
