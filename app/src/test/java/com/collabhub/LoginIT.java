package com.collabhub;

import com.collabhub.identity.internal.security.RefreshTokenFactory;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录接口的集成测试。
 *
 * <p>重点验证三类行为：正常签发、安全边界（不泄露账号是否存在 / 失败计数 / 锁定）、
 * 以及"数据库里存的是哈希而不是令牌原文"。
 *
 * <p>每个用例用独立邮箱，互不干扰；锁定类用例只影响它自己的账号。
 */
@Testcontainers
@SpringBootTest(classes = CollabHubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIT {

    private static final String REGISTER = "/api/v1/identity/users";
    private static final String LOGIN = "/api/v1/identity/sessions";
    private static final String PASSWORD = "Passw0rd!";

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
    void loginReturnsAccessAndRefreshTokens() {
        String email = registerNewUser();

        ResponseEntity<Map> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = asMap(response.getBody());
        String accessToken = String.valueOf(body.get("accessToken"));
        String refreshToken = String.valueOf(body.get("refreshToken"));

        // JWT 的结构就是 header.payload.signature 三段
        assertThat(accessToken.split("\\.")).hasSize(3);
        assertThat(refreshToken).isNotBlank().hasSizeGreaterThan(30);
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(((Number) body.get("expiresIn")).longValue()).isEqualTo(15 * 60);
        assertThat(asMap(body.get("user")).get("email")).isEqualTo(email);
    }

    @Test
    void wrongPasswordReturns401WithGenericMessage() {
        String email = registerNewUser();

        ResponseEntity<Map> response = login(email, "WrongPass1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(asMap(response.getBody()).get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void unknownEmailReturnsTheSameErrorAsWrongPassword() {
        String email = registerNewUser();
        ResponseEntity<Map> wrongPassword = login(email, "WrongPass1");
        ResponseEntity<Map> unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD);

        // 两种失败必须"完全无法区分"：状态码、错误码、文案都一样。
        // 任何差异都会变成撞库者的账号探测器。
        assertThat(unknownEmail.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(asMap(unknownEmail.getBody()).get("code"))
                .isEqualTo(asMap(wrongPassword.getBody()).get("code"));
        assertThat(asMap(unknownEmail.getBody()).get("detail"))
                .isEqualTo(asMap(wrongPassword.getBody()).get("detail"));
    }

    @Test
    void locksAccountAfterFiveConsecutiveFailures() {
        String email = registerNewUser();

        // 前 4 次：普通 401
        for (int i = 1; i <= 4; i++) {
            assertThat(login(email, "WrongPass1").getStatusCode())
                    .as("第 %d 次失败应该是 401", i)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 第 5 次：触达阈值，立刻锁定并返回 429
        ResponseEntity<Map> fifth = login(email, "WrongPass1");
        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(asMap(fifth.getBody()).get("code")).isEqualTo("ACCOUNT_LOCKED");

        // 锁定期内即使密码正确也要拒绝 —— 否则锁定形同虚设
        ResponseEntity<Map> correct = login(email, PASSWORD);
        assertThat(correct.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(asMap(correct.getBody()).get("code")).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void successfulLoginResetsFailedCounter() {
        String email = registerNewUser();

        login(email, "WrongPass1");
        login(email, "WrongPass1");
        assertThat(failedCountOf(email)).isEqualTo(2);

        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(failedCountOf(email)).isZero();
    }

    @Test
    void storesRefreshTokenHashNotTheRawToken() {
        String email = registerNewUser();

        String refreshToken = String.valueOf(asMap(login(email, PASSWORD).getBody()).get("refreshToken"));
        String expectedHash = RefreshTokenFactory.sha256Hex(refreshToken);

        String storedHash = jdbc.queryForObject("""
                SELECT s.refresh_token_hash
                FROM user_session s
                JOIN user_account a ON a.id = s.user_id
                WHERE a.email = ?
                """, String.class, email);

        assertThat(storedHash)
                .as("库里必须存哈希，不能存令牌原文")
                .isEqualTo(expectedHash)
                .isNotEqualTo(refreshToken)
                .hasSize(64);
    }

    // ---------------------------------------------------------------- helpers

    private String registerNewUser() {
        String email = "login" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@example.com";
        ResponseEntity<Map> response = rest.postForEntity(REGISTER,
                Map.of("email", email, "displayName", "测试用户", "password", PASSWORD),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private ResponseEntity<Map> login(String email, String password) {
        return rest.postForEntity(LOGIN, Map.of("email", email, "password", password), Map.class);
    }

    private int failedCountOf(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT failed_login_count FROM user_account WHERE email = ?",
                Integer.class, email);
        return count == null ? -1 : count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
