-- ============================================================
-- V1 平台基线
-- 归属模块：platform
-- 版本号段：platform 0001-0999 / identity 1001-1999 / workspace 2001-2999
--           document 3001-3999 / 其他模块依次类推（见 ADR-0003）
-- ============================================================

CREATE TABLE platform_setting
(
    setting_key   VARCHAR(100)  NOT NULL COMMENT '配置键，如 feature.ai.enabled',
    setting_value VARCHAR(2000) NOT NULL COMMENT '配置值',
    description   VARCHAR(255)  NULL COMMENT '说明',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='全局配置项与功能开关';

INSERT INTO platform_setting (setting_key, setting_value, description)
VALUES ('app.timezone', 'Asia/Shanghai', '系统默认时区'),
       ('feature.registration.enabled', 'true', '是否允许自主注册'),
       ('feature.ai.enabled', 'false', 'AI 功能总开关，M4 打开');
