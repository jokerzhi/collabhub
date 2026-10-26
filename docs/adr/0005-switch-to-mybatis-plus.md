# ADR-0005 持久层从 JdbcTemplate 换成 MyBatis-Plus

- **状态**：已接受
- **日期**：2026-10-26
- **相关**：ADR-0001（模块化单体）、AGENTS.md（编码约定）
- **取代**：M1 时"持久层方案留到 M2 再定"的临时状态

## 背景

M1 只用 `JdbcTemplate` 跑通了一条管道（`SystemInfoService` 读配置表），
当时在代码注释里明确写了「业务模块的持久层方案留到 M2 再定」。

到 M2 第 3 条结束时，`identity` 模块已经有 2 个 Repository、231 行、21 条手写 SQL：

| 文件 | 行数 | 内容 |
|---|---|---|
| `UserAccountRepository` | 158 | 注册、登录查询、失败计数、锁定 |
| `UserSessionRepository` | 73 | 会话写入、按哈希查询 |

按这个密度推算，需求里的 30 多张表会产生 **3000+ 行纯样板代码**
（`INSERT INTO x (...) VALUES (?,?,?,?,?)` 加 `ResultSetMapper`），
而且这些代码没有任何信息量。

更要紧的是：**多租户隔离靠人肉保证不可靠**。
所有业务表都要带 `workspace_id`，手写 SQL 时漏一个 `WHERE` 条件，
代码看起来完全正常，review 也极难发现，但那是数据泄露级 bug。

## 候选方案

| 方案 | 简单 CRUD | 复杂查询 | 多租户自动隔离 | SQL 可见性 | 学习成本 |
|---|---|---|---|---|---|
| JdbcTemplate（原状） | 差 | 最好 | ❌ | 最高 | 0 |
| **MyBatis-Plus** | **最好** | 中（复杂时写 XML） | ✅ 全局拦截器 | 中（要开日志） | 低 |
| Spring Data JPA | 好 | 差（Criteria API 晦涩） | ⚠️ 需 Hibernate Filter | 低（N+1 隐蔽） | 中 |
| jOOQ | 中 | 最好（类型安全） | ❌ 需自己包 | 高 | 高 |

## 决定

**采用 MyBatis-Plus 3.5.18（`mybatis-plus-spring-boot3-starter`）。**

三条理由，按重要性排序：

1. **多租户拦截器（`TenantLineHandler`）**。
   它能在 SQL 生成阶段自动追加 `workspace_id = ?`，把「可能忘」变成「忘了也不会错」。
   这一条的价值超过其余所有便利性的总和，也是选它而不是 JPA / jOOQ 的主因。
2. **样板代码归零**。`BaseMapper` 提供 insert / selectById / updateById，
   不用再写 `GeneratedKeyHolder` 那套。
3. **认知成本为零**。这是国内 Java 岗位的主流方案，不需要额外学一套心智模型，
   省下的精力留给真正难的部分（M3 的 CRDT、M4 的 RAG）。

## 保留的抽象

**没有把 Service 直接接到 Mapper 上**，而是在中间保留 Repository：

```
Service  →  Repository（领域视角的方法）  →  Mapper（MyBatis-Plus）  →  DB
```

理由：
- Service 里的代码不出现任何 MyBatis 类型，将来换持久层只动 Repository
- Repository 的入参出参可以继续用 `record`（如 `AuthRow`），MyBatis 的 POJO 不外泄

代价是多一层转发（每个方法 1~3 行），换取业务代码与框架解耦。

## 后果

### 正面

- 新增一张表的 CRUD 从「写 3 个方法 + 3 段 SQL」变成「建一个实体 + 一个空 Mapper 接口」
- 分页、乐观锁、逻辑删除都有现成插件
- 多租户隔离从「靠自觉」变成「靠框架」

### 代价

**1. 实体必须是可变 POJO，用不了 record。**

MyBatis-Plus 的 `BaseMapper` 需要无参构造 + setter 才能把查询结果填进对象。
这与 AGENTS.md 里「record 优先」的约定冲突。

折中约定（已写入 AGENTS.md）：
- **实体（映射表结构）用 POJO**，手写 getter/setter，不加 Lombok
- **查询结果 / DTO（对外暴露）继续用 record**
- 写模型和读模型分开，本质上是 CQRS-lite 的简化版

**2. Wrapper 拼出来的 SQL 看不见。**

`lambdaQuery().eq(...).like(...)` 很方便，但出问题时不知道实际执行了什么。
开发期必须开 `mybatis-plus.configuration.log-impl`，
M3 做搜索时可以再接 p6spy 把带参数的完整 SQL 打出来。

**3. 复杂查询仍然是手写 SQL。**

M3 的搜索（多条件 + 相关性排序 + 分页）用 Wrapper 写会比裸 SQL 更乱，
那时应该直接写 XML 或 `@Select`。这不是"没用上 ORM"，就是 ORM 的正常用法。

## 没有做的事（有意留白）

| 未启用 | 原因 | 何时加 |
|---|---|---|
| `TenantLineHandler` | `workspace` 模块还不存在，没有 `workspace_id` 列 | M2 第 5-6 条建 workspace 表时 |
| `PaginationInnerInterceptor` | 还没有任何列表接口 | 第一个分页接口出现时 |
| 逻辑删除配置 | 我们的 `deleted_at` 是时间戳，MP 的 `logic-delete-value` 对时间类型支持不佳 | 查询层继续显式写 `deleted_at IS NULL` |

## 迁移范围

| 改动 | 文件 |
|---|---|
| 新增依赖 | `pom.xml`（版本管理）、`identity/pom.xml` |
| 新增实体 | `UserAccountEntity` / `UserCredentialEntity` / `UserSessionEntity` |
| 新增 Mapper | `UserAccountMapper` / `UserCredentialMapper` / `UserSessionMapper` |
| 重写 Repository | 2 个（对外方法签名不变，Service 零改动） |
| 配置 | `application.yml` 加 `mybatis-plus` 段 |

**关键点：Service 层一行没改。** 这正是保留 Repository 抽象换来的收益。
