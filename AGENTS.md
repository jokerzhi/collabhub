# AGENTS.md

给 AI 编码助手（Claude Code / Copilot / Cursor / DSH 等）的项目约定。
**动代码之前先读这一页**，比读整个仓库快。

---

## 这个项目是什么

一个模块化单体的协作文档平台。业务目标在 `docs/requirements.md`，
每条需求都有编号（`FR-DOC-004` 这种），代码和提交信息都要引用编号。

当前进度：**M1 地基已完成**。`identity / workspace / document` 三个模块只有包声明，还没有业务代码。

---

## 动手之前，按顺序读

1. `docs/requirements.md` 第 2、3、8 节 —— 做什么、不做什么、里程碑
2. `docs/adr/` —— 已经定下来的架构决策，**不要推翻，除非写新 ADR**
3. 本文件 —— 编码约定

---

## 常用命令

```powershell
.\mvnw.cmd verify                              # 全量构建 + 单元测试 + 集成测试
.\mvnw.cmd test                                # 只跑单元测试（秒级）
.\mvnw.cmd -pl app spring-boot:run             # 本地启动
cd infra; docker compose up -d                 # 中间件
```

改完任何代码，**至少跑一次 `.\mvnw.cmd test`**。边界测试挂在里面。

---

## 代码结构

```
com.collabhub
├── platform/          技术底座，OPEN 模块，任何模块都可以依赖它
├── identity/          身份与账号
├── workspace/         工作空间与项目
├── document/          文档、评论、协同
└── CollabHubApplication   唯一启动类（在根包）
```

每个业务模块内部：

```
<module>/
├── <Module>Api.java   跨模块调用的唯一入口
├── api/               对外 DTO（record）
├── internal/          实现细节，禁止跨模块访问
│   ├── domain/
│   ├── repository/
│   └── service/
└── web/               本模块 Controller
```

### 三条硬规则

1. **业务模块之间不许互相 import**。需要别人的数据，调它的 `*Api` 接口。
2. **platform 不许依赖任何业务模块**。
3. **Controller 不许直接碰 `JdbcTemplate` / Repository**，必须经过 Service。

三条都会被拦住，但**把关的角色不同**（详见 [docs/testing.md](docs/testing.md) §2）：

- 规则 1 由 **Maven 依赖**兜底（pom 没声明就编译不过），Modulith 复核
- 规则 2 由 Maven + ArchUnit 双重
- 规则 3 **只有 ArchUnit 能管**，编译器不会拦你——所以写 Controller 时格外注意

越界了 `mvnw test` 会直接失败，不用等人 review。

---

## 编码约定

| 项 | 约定 |
|---|---|
| 语言级别 | Java 25。优先用 `record`、`sealed`、`switch` 模式匹配 |
| Lombok | **不用**。record + 显式构造器就够了，Lombok 会破坏调试体验 |
| 依赖注入 | 构造器注入，字段一律 `private final`。不用 `@Autowired` 注解字段 |
| 可变状态 | 默认不可变。要改的对象用 `with*` 方法返回新实例 |
| 空值 | 方法参数用 `Objects.requireNonNull` 或 `@NonNull`，返回值尽量不返回 null |
| 时间 | 数据库存 UTC，展示层按用户时区转换。用 `Instant` / `OffsetDateTime`，不用 `Date` |
| 事务 | `@Transactional` 只加在 Service 的公开方法上，不加在 Controller |
| 日志 | `private static final Logger log = LoggerFactory.getLogger(X.class);`，参数化 `log.info("id={}", id)` |
| 异常 | 业务失败抛 `BusinessException(status, code, message)`；程序错误直接抛，交给全局处理器 |
| 注释 | 中文。**解释为什么，不解释是什么**。一眼能看懂的代码不要注释 |

---

## 数据库

- 迁移脚本放各自模块的 `src/main/resources/db/migration/`
- 版本号按号段：platform `1-999`、identity `1001-1999`、workspace `2001-2999`、document `3001-3999`
- **已执行过的迁移脚本永远不要改**，要改表就新增一个脚本
- 所有表必须有：`id`（BIGINT 自增或 UUID）、`created_at`、`updated_at`
- 业务表必须有 `workspace_id`（多租户隔离的根）
- 字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`（要存 emoji）
- 外键：项目里**不建物理外键**，靠应用层保证 + 定期一致性检查（原因：分库和归档时外键会挡路，写在 ADR 里）

---

## 测试

| 类型 | 命名 | 跑在哪 | 要求 |
|---|---|---|---|
| 单元测试 | `*Test` | surefire，`mvn test` | 不连数据库，毫秒级 |
| 集成测试 | `*IT` | failsafe，`mvn verify` | 用 Testcontainers，连真实 MySQL |

新增一个接口，至少要有一条集成测试断言「状态码 + 关键字段」。
新增一个业务规则，至少要有一条单元测试覆盖边界条件。

测试方法名用英文，避免不同平台编码问题。

**写测试之前先读 [docs/testing.md](docs/testing.md)**：里面有三层测试的分工、每条架构规则防的是什么、
失败信息怎么读，以及"新增接口 / 新增业务规则 / 新增架构约束"三套模板，直接抄。

---

## 提交信息

```
<type>(<需求编号>): <一句话说明>

feat(DOC-004): 文档每次发布生成不可变版本
fix(AUTH-003): 登录失败锁定时间未生效
refactor(WS-001): 抽取租户上下文解析
docs(ADR): 新增持久层选型决策
test(FILE-003): 补秒传的哈希冲突用例
```

`type` 只用 `feat / fix / refactor / docs / test / chore`。

---

## 不要做的事

- ❌ 不要在 `app` 模块里写业务代码（它只负责装配和配置）
- ❌ 不要为了图快把两个模块的表放进同一个 Service
- ❌ 不要引入新的重量级依赖而不写 ADR
- ❌ 不要在 Controller 里拼错误响应（统一用 `BusinessException`）
- ❌ 不要用 `System.out.println`，也不要吞异常
- ❌ 不要把密码、密钥写进代码或配置文件，用环境变量（`.env` 已在 `.gitignore`）
- ❌ 不要一次性提交 20 个文件的大改动

---

## 和 AI 协作的建议姿势

这个项目本身也是「练习用 AI 写项目」的场地。几条经验：

1. **一次一件事**。让 AI 先只做「建表 + 实体 + Repository」，跑通再继续。
   一口气让它实现整个模块，你会得到一堆看不懂的代码。
2. **先让 AI 写测试**。描述清楚输入输出，让它先写测试用例，你确认测试是对的，
   再让它写实现。测试是唯一能快速验证 AI 产出正确性的东西。
3. **要求解释 diff**。每次改完，问「为什么改这三个文件，各自的作用是什么」。
   答不上来的改动，大概率是它顺手加了不该加的东西。
4. **让它先查约定**。开新任务时，把 `AGENTS.md` 和相关的 ADR 丢给它，
   比事后 review 省事得多。
5. **架构决策自己拍板**。AI 倾向于选「最流行」的方案，但选型要看你的约束。
   它给了建议，你写 ADR，把理由固定下来。
6. **小步提交**。每个能通过的改动单独提交，出问题能精确回滚。

---

## 当前阶段的已知坑

| 坑 | 说明 |
|---|---|
| `spring-boot-maven-plugin` 必须配 `classifier=exec` | 否则 repackage 会覆盖主产物，failsafe 阶段会报 `Failed to find merged annotation for @BootstrapWith` |
| `ResponseEntityExceptionHandler` 的 logger 是 commons-logging | 不支持 `{}` 占位符，只能字符串拼接 |
| Flyway 的 `flyway_schema_history` 是 `VARCHAR` 版本列 | 取最大版本要 `CAST(version AS UNSIGNED)` |
| Docker 镜像源 `docker.m.daocloud.io` 不放行 minio | 从 `docker.1panel.live` 拉取后 retag |
| IDEA 用 Maven 3.10 导入后 0 个依赖库、满屏编译错误 | IDEA 2025.3 的 Maven 适配层只支持到 3.9.x（Maven 3.10 会报 `No implementation for ArtifactHandlerManager`）。IDEA 里 Maven home 必须选 **Bundled (3.9.11)**；命令行仍用 3.10，互不影响 |

遇到新的问题：**先查 [docs/troubleshooting.md](docs/troubleshooting.md)**（分层定位 → 日志地图 → 五步法 → 已知故障库），
解决完把「现象 / 证据 / 修法 / 验证」补进第 5 节，别只留在聊天记录里。
