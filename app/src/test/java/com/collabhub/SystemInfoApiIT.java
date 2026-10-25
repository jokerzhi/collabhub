package com.collabhub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：真实 MySQL（Testcontainers）+ 真实 Flyway + 真实 HTTP。
 *
 * <p>这是 M1 的验收物。它证明的不是「代码能编译」，而是：
 * 容器化数据库能起、迁移能跑、Spring 上下文能装配、HTTP 层能响应、配置能被真实读取。
 *
 * <p>用 {@code *IT} 命名，由 failsafe 在 {@code mvn verify} 阶段执行；
 * 单元测试（{@code *Test}）由 surefire 执行，两者分开跑。
 */
@Testcontainers
@SpringBootTest(classes = CollabHubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SystemInfoApiIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.35")
            .withDatabaseName("collabhub")
            .withUsername("collabhub")
            .withPassword("collabhub_dev_pwd");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void returnsSystemInfoAndProvesTheWholePipeline() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/system/info", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 请求 ID 过滤器生效
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("application")).isEqualTo("collabhub");
        assertThat(body.get("javaVersion")).asString().startsWith("25");

        // 数据库真的连上了
        assertThat(body.get("database")).asString().contains("8.0");

        // Flyway 真的跑了，库里是当前最新版本（加迁移时这里要同步改，
        // 这是刻意的：它记录「这个构建期望的 schema 版本」）
        assertThat(body.get("schemaVersion")).isEqualTo("1001");

        // 迁移里的种子数据真的进去了
        assertThat(asMap(body.get("settings"))).containsEntry("app.timezone", "Asia/Shanghai");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
