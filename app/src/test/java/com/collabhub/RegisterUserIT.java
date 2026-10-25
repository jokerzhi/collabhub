package com.collabhub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册接口的集成测试。
 *
 * <p>验证四件事：正常路径、邮箱冲突、参数校验、密码哈希。
 * 每个用例用独立邮箱，互不干扰。
 */
@Testcontainers
@SpringBootTest(classes = CollabHubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegisterUserIT {

    private static final String ENDPOINT = "/api/v1/identity/users";

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registersNewUserAndReturns201() {
        String email = uniqueEmail();
        // 昵称带中文和 emoji：顺带验证 utf8mb4 全链路往返没丢字符
        String displayName = "张三🚀";

        ResponseEntity<Map> response = post(Map.of(
                "email", email,
                "displayName", displayName,
                "password", "Passw0rd!"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        Map<String, Object> body = asMap(response.getBody());
        assertThat(body.get("id")).isNotNull();
        assertThat(body.get("email")).isEqualTo(email);
        assertThat(body.get("displayName")).isEqualTo(displayName);
        assertThat(body.get("status")).isEqualTo("PENDING");
        assertThat(body.keySet()).doesNotContain("password", "passwordHash");
    }

    @Test
    void rejectsDuplicateEmailIgnoringCase() {
        String email = uniqueEmail();
        post(Map.of("email", email, "displayName", "张三", "password", "Passw0rd!"));

        // 换个大小写再注册，应被判为重复
        ResponseEntity<Map> second = post(Map.of(
                "email", email.toUpperCase(Locale.ROOT),
                "displayName", "李四",
                "password", "Passw0rd!"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(asMap(second.getBody()).get("code")).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void rejectsPasswordShorterThanEight() {
        ResponseEntity<Map> response = post(Map.of(
                "email", uniqueEmail(),
                "displayName", "王五",
                "password", "Ab1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = asMap(response.getBody());
        assertThat(body.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(asMap(body.get("errors"))).containsKey("password");
    }

    @Test
    void rejectsPasswordWithoutLetters() {
        ResponseEntity<Map> response = post(Map.of(
                "email", uniqueEmail(),
                "displayName", "赵六",
                "password", "12345678"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asMap(response.getBody()).get("code")).isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void storesArgon2HashAndNeverThePlaintext() {
        String email = uniqueEmail();
        String password = "Passw0rd!";
        post(Map.of("email", email, "displayName", "钱七", "password", password));

        String stored = passwordHashOf(email);

        assertThat(stored)
                .as("必须存 Argon2id 编码串，而不是明文或可逆加密")
                .startsWith("$argon2id$")
                .isNotEqualTo(password)
                .doesNotContain(password)
                .hasSizeGreaterThan(50);

        // 同一个密码、两个账号，哈希结果必须不同 —— 说明每个密码都有独立随机盐。
        // 如果这里相等，说明没加盐，彩虹表可以直接反查。
        String otherEmail = uniqueEmail();
        post(Map.of("email", otherEmail, "displayName", "孙八", "password", password));
        assertThat(passwordHashOf(otherEmail)).isNotEqualTo(stored);
    }

    // ---------------------------------------------------------------- helpers

    private String passwordHashOf(String email) {
        return jdbc.queryForObject("""
                SELECT c.password_hash
                FROM user_credential c
                JOIN user_account a ON a.id = c.user_id
                WHERE a.email = ?
                """, String.class, email);
    }

    private ResponseEntity<Map> post(Map<String, Object> payload) {
        return rest.postForEntity(ENDPOINT, payload, Map.class);
    }

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
