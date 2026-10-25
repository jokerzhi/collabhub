-- ============================================================
-- V1001 身份模块：账号 / 凭证 / 会话
-- 归属模块：identity      号段：1001-1999（见 ADR-0003）
-- 对应需求：FR-AUTH-001、FR-AUTH-003、FR-AUTH-004、FR-AUTH-006、FR-AUTH-009
-- ============================================================
--
-- 设计要点（每条都是刻意的，不是随手写的）：
--
-- 1. 三张表分开，而不是一张大表
--    账号存资料、凭证存机密、会话存登录态。机密集中在一张表，方便
--    单独加密、单独审计、单独设访问权限；将来加 OAuth 登录时也只动凭证表。
--
-- 2. 不建物理外键（AGENTS.md 约定）
--    外键在数据归档、分库、批量导入时会挡路，一致性由应用层保证 + 定期对账。
--
-- 3. 时间列**不给** DEFAULT CURRENT_TIMESTAMP
--    默认值写入的是「数据库服务器所在时区」的时间，而我们要的是 UTC。
--    与其把时区假设藏在 DDL 默认值里，不如强制应用层用 Instant 显式写入，
--    让"存 UTC"变成一次显式决策。
--
-- 4. 邮箱唯一性靠唯一索引 + utf8mb4_0900_ai_ci
--    这个排序规则大小写不敏感，所以 alice@x.com 和 Alice@X.com 会被判为重复。
--    不额外存 email_lower 列。
--
-- 5. 软删除：deleted_at 非空即已删除
--    所有查询都必须带 deleted_at IS NULL。物理删除由定时任务在冷静期后执行。
--
-- 6. status 用 VARCHAR 而不是 ENUM
--    加状态值不需要改表结构；ENUM 每次变更都要 ALTER TABLE。
-- ============================================================


CREATE TABLE user_account
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email              VARCHAR(254)    NOT NULL COMMENT '登录邮箱，全局唯一（大小写不敏感）',
    display_name       VARCHAR(64)     NOT NULL COMMENT '展示名，2-32 字符',
    avatar_url         VARCHAR(512)    NULL COMMENT '头像对象存储 URL',
    status             VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING 待邮箱验证 / ACTIVE 正常 / SUSPENDED 被封 / DELETED 已注销',
    email_verified_at  DATETIME(3)     NULL COMMENT '邮箱验证通过时间，NULL 表示未验证',
    time_zone          VARCHAR(64)     NOT NULL DEFAULT 'Asia/Shanghai',
    locale             VARCHAR(16)     NOT NULL DEFAULT 'zh-CN',
    failed_login_count INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '连续登录失败次数，登录成功后清零',
    locked_until       DATETIME(3)     NULL COMMENT '锁定到该时刻，NULL 表示未锁定',
    last_login_at      DATETIME(3)     NULL,
    created_at         DATETIME(3)     NOT NULL,
    updated_at         DATETIME(3)     NOT NULL,
    deleted_at         DATETIME(3)     NULL COMMENT '非空表示已软删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_email (email),
    KEY idx_user_account_status (status, deleted_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户账号：资料与登录状态';


CREATE TABLE user_credential
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 -> user_account.id',
    credential_type     VARCHAR(16)     NOT NULL DEFAULT 'PASSWORD'
        COMMENT 'PASSWORD / OAUTH_GITHUB / OAUTH_GOOGLE',
    password_hash       VARCHAR(255)    NOT NULL
        COMMENT 'Argon2id 编码串（含算法参数与 salt），绝不存明文、绝不返回给接口',
    password_updated_at DATETIME(3)     NOT NULL COMMENT '最近一次修改密码时间，用于失效旧会话',
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    -- 一个用户每种凭证类型只能有一条：改密码是 UPDATE，不是 INSERT
    UNIQUE KEY uk_user_credential_user_type (user_id, credential_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户凭证：密码等机密，与账号资料隔离';


CREATE TABLE user_session
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id            BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 -> user_account.id',
    refresh_token_hash CHAR(64)        NOT NULL
        COMMENT 'refresh token 的 SHA-256 十六进制（64 字符）。明文永不落库',
    device_name        VARCHAR(128)    NULL COMMENT '如 "Chrome on Windows"',
    ip_address         VARCHAR(45)     NULL COMMENT 'IPv6 最长 45 字符',
    user_agent         VARCHAR(512)    NULL,
    created_at         DATETIME(3)     NOT NULL,
    last_used_at       DATETIME(3)     NULL COMMENT '每次刷新令牌时更新',
    expires_at         DATETIME(3)     NOT NULL,
    revoked_at         DATETIME(3)     NULL
        COMMENT '非空表示已吊销：登出 / 改密 / 检测到令牌重放',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_session_token (refresh_token_hash),
    KEY idx_user_session_user (user_id, revoked_at, expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户会话：一条记录对应一个 refresh token';
