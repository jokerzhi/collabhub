package com.collabhub.platform.system;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取系统自检信息。
 *
 * <p>这里刻意用 {@link JdbcTemplate} 而不是 JPA：M1 只是为了验证管道，
 * 引入 ORM 会把「到底哪一层出错」变模糊。业务模块的持久层方案留到 M2 再定。
 */
@Service
public class SystemInfoService {

    private final JdbcTemplate jdbc;
    private final String applicationName;
    private final String applicationVersion;

    public SystemInfoService(JdbcTemplate jdbc,
                             @Value("${spring.application.name:collabhub}") String applicationName,
                             @Value("${app.version:0.1.0-SNAPSHOT}") String applicationVersion) {
        this.jdbc = jdbc;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    public SystemInfo load() {
        return new SystemInfo(
                applicationName,
                applicationVersion,
                System.getProperty("java.version"),
                jdbc.queryForObject("SELECT VERSION()", String.class),
                currentSchemaVersion(),
                loadSettings(),
                MDC.get("traceId")
        );
    }

    /** flyway_schema_history 里的最高成功版本，用来确认「迁移真的跑过了」。 */
    private String currentSchemaVersion() {
        String version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) "
                        + "FROM flyway_schema_history WHERE success = 1",
                String.class);
        return version == null ? "0" : version;
    }

    private Map<String, String> loadSettings() {
        return jdbc.query(
                "SELECT setting_key, setting_value FROM platform_setting ORDER BY setting_key",
                rs -> {
                    Map<String, String> settings = new LinkedHashMap<>();
                    while (rs.next()) {
                        settings.put(rs.getString(1), rs.getString(2));
                    }
                    return settings;
                });
    }
}
