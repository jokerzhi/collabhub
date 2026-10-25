package com.collabhub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份模块的 schema 集成测试。
 *
 * <p>它验证的不是"表能建出来"这么简单，而是<b>业务假设能不能被数据库兜住</b>：
 * 邮箱唯一、凭证唯一、会话令牌唯一、字符集支持 emoji。
 * 这些约束一旦缺失，代码层面的 bug 会变成数据层面的脏数据。
 *
 * <p>用 {@link SpringBootTest.WebEnvironment#NONE}，不启动 Tomcat —— 这里只关心数据库，
 * 起 Web 容器纯属浪费 3 秒。
 */
@Testcontainers
@SpringBootTest(classes = CollabHubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdentitySchemaIT {

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
    private JdbcTemplate jdbc;

    @Test
    void createsTheThreeIdentityTables() {
        assertThat(tableExists("user_account")).isTrue();
        assertThat(tableExists("user_credential")).isTrue();
        assertThat(tableExists("user_session")).isTrue();
    }

    @Test
    void enforcesTheUniquenessConstraintsTheDomainReliesOn() {
        // 一个邮箱只能注册一次（大小写不敏感由 collation 负责）
        assertThat(hasUniqueIndex("user_account", "uk_user_account_email")).isTrue();

        // 一个用户每种凭证类型只有一条：改密码是 UPDATE 而不是 INSERT
        assertThat(hasUniqueIndex("user_credential", "uk_user_credential_user_type")).isTrue();

        // 一个 refresh token 只能对应一个会话，这是防重放的基础
        assertThat(hasUniqueIndex("user_session", "uk_user_session_token")).isTrue();
    }

    @Test
    void usesEmojiReadyCharset() {
        assertThat(collationOf("user_account")).startsWith("utf8mb4");
        assertThat(collationOf("user_credential")).startsWith("utf8mb4");
        assertThat(collationOf("user_session")).startsWith("utf8mb4");
    }

    @Test
    void flywayAppliedTheIdentityMigration() {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) "
                        + "FROM flyway_schema_history WHERE success = 1",
                Integer.class);

        assertThat(version).isEqualTo(1001);
    }

    // ---------------------------------------------------------------- helpers

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    private boolean hasUniqueIndex(String table, String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND index_name = ? AND non_unique = 0",
                Integer.class, table, indexName);
        return count != null && count > 0;
    }

    private String collationOf(String table) {
        return jdbc.queryForObject(
                "SELECT table_collation FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, table);
    }
}
