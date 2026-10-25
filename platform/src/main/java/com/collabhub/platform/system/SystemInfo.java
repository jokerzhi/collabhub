package com.collabhub.platform.system;

import java.util.Map;

/**
 * 系统自检信息。
 *
 * <p>这个接口不是为了「好看」，它是 M1 的验收物：一次请求同时证明了
 * Controller → Service → JDBC → MySQL → Flyway → 链路追踪 整条管道是通的。
 *
 * @param application   应用名
 * @param version       版本号
 * @param javaVersion   运行时 JDK 版本
 * @param database      数据库版本
 * @param schemaVersion 当前已执行的最高 Flyway 版本
 * @param settings      全局配置项
 * @param traceId       本次请求的链路 ID（没有启用追踪时为 null）
 */
public record SystemInfo(
        String application,
        String version,
        String javaVersion,
        String database,
        String schemaVersion,
        Map<String, String> settings,
        String traceId
) {
}
