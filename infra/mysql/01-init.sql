-- ============================================================
-- CollabHub 数据库初始化（MySQL 8.0.35 本机服务，端口 3306）
-- 执行方式：用 Navicat 以 root 连接 localhost:3306 → 新建查询 → 粘贴执行
-- 只需执行一次
-- ============================================================

-- 1) 建库：utf8mb4 才能存 emoji（协作软件里到处都是表情）
CREATE DATABASE IF NOT EXISTS `collabhub`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 2) 应用专用账号（不要用 root 跑应用）
CREATE USER IF NOT EXISTS 'collabhub'@'localhost' IDENTIFIED BY 'collabhub_dev_pwd';
CREATE USER IF NOT EXISTS 'collabhub'@'%'         IDENTIFIED BY 'collabhub_dev_pwd';

GRANT ALL PRIVILEGES ON `collabhub`.* TO 'collabhub'@'localhost';
GRANT ALL PRIVILEGES ON `collabhub`.* TO 'collabhub'@'%';
FLUSH PRIVILEGES;

-- 3) 验证：下面的查询应该返回两行
-- SELECT user, host, plugin FROM mysql.user WHERE user = 'collabhub';


-- ============================================================
-- 备注：中文全文检索
-- MySQL 8.0 自带 ngram 分词器，可以先用它顶着，不必一上来就上 ES：
--
--   CREATE TABLE doc_content (
--     id      BIGINT UNSIGNED NOT NULL PRIMARY KEY,
--     title   VARCHAR(500)  NOT NULL,
--     body    MEDIUMTEXT    NOT NULL,
--     FULLTEXT KEY ft_doc (title, body) WITH PARSER ngram
--   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
--   SELECT id, MATCH(title, body) AGAINST ('协作 编辑' IN BOOLEAN MODE) AS score
--   FROM doc_content WHERE MATCH(title, body) AGAINST ('协作 编辑' IN BOOLEAN MODE);
--
-- 这个索引由 Flyway 迁移创建，这里只是留个记号。
-- ============================================================
