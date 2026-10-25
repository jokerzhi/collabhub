# CollabHub

一个支持多人实时协作的文档与知识空间。个人项目，目标是**把一整套 Java 服务端技术栈在真实业务里跑通**，而不是做一个 CRUD 演示。

> 需求范围见 [docs/requirements.md](docs/requirements.md)，环境基线见 [docs/environment.md](docs/environment.md)，
> 架构决策见 [docs/adr](docs/adr/)，测试怎么写见 [docs/testing.md](docs/testing.md)，
> 遇到问题先查 [docs/troubleshooting.md](docs/troubleshooting.md)。

## 技术栈

| 领域 | 选型 | 为什么 |
|---|---|---|
| 语言 | JDK 25 LTS | 虚拟线程：IO 密集型场景不再纠结线程池 |
| 框架 | Spring Boot 3.5 | 生态成熟 |
| 架构 | 模块化单体 + **Spring Modulith** | 边界可执行，不是一个 jar 里的大泥球 |
| 主库 | MySQL 8.0 | 本机已有；中文检索用 ngram 全文索引顶到第二阶段 |
| 迁移 | Flyway | 表结构变更全部可追溯 |
| 缓存 | Redis 7 | 会话、限流、缓存、分布式锁 |
| 消息 | RabbitMQ 4 | 事件驱动、延迟队列、死信 |
| 存储 | MinIO | S3 协议，附件与头像 |
| 搜索 | MySQL ngram → OpenSearch | 有数据支撑再升级 |
| 向量 | Qdrant（按需） | 语义检索 |
| 前端 | Vue 3 + TS + Vite | M3 开始（M2 只做 API，先定接口形状） |
| 测试 | JUnit 5 + **Testcontainers** + **ArchUnit** | 集成测试连真库；架构规则也能断言 |

## 目录结构

```
collabhub/
├── platform/     技术底座：错误模型、请求上下文、可观测性、系统自检
├── identity/     身份与账号（M2）
├── workspace/    工作空间与项目（M2）
├── document/     文档、评论、实时协同（M2-M3）
├── app/          唯一部署单元：启动类、配置、集成测试
├── infra/        docker compose（Redis / RabbitMQ / MinIO / Mailpit）
└── docs/         需求、环境基线、ADR
```

**依赖方向是单向的**：`app → 业务模块 → platform`。业务模块之间不允许互相依赖，
由 Spring Modulith 和 ArchUnit 在构建期强制检查。

## 快速开始

### 0. 前置条件

- JDK 25、Maven（用仓库自带的 `mvnw`，无需本机安装）
- Docker Desktop（跑中间件）
- MySQL 8 本机服务，且已执行过 `infra/mysql/01-init.sql`（建库 + 应用账号）

### 1. 起中间件

```powershell
cd infra
copy .env.example .env      # 只需一次
docker compose up -d
docker compose ps           # 四个服务应全是 healthy
```

### 2. 构建并跑测试

```powershell
.\mvnw.cmd verify
```

会执行：编译 → 单元测试（Modulith 边界 + ArchUnit 规则）→ 集成测试（Testcontainers 起一个 MySQL，跑 Flyway，发真实 HTTP 请求）。

### 3. 启动应用

```powershell
.\mvnw.cmd -pl app spring-boot:run
```

或者用打包好的可执行 jar：

```powershell
.\mvnw.cmd package
java -jar app\target\app-0.1.0-SNAPSHOT-exec.jar
```

### 4. 验证

```powershell
curl http://localhost:8080/api/v1/system/info
curl http://localhost:8080/actuator/health
```

`/api/v1/system/info` 应返回类似：

```json
{
  "application": "collabhub",
  "version": "0.1.0-SNAPSHOT",
  "javaVersion": "25.0.4.1",
  "database": "8.0.35",
  "schemaVersion": "1",
  "settings": { "app.timezone": "Asia/Shanghai" },
  "traceId": "dc2828512aa43c64f22bed7ac5ff6f58"
}
```

看到 `schemaVersion: "1"` 说明 Flyway 迁移成功；有 `traceId` 说明链路追踪在工作。

### 5. 在 IDEA 里跑

直接打开项目根目录，等右下角 Maven 导入完成，然后按绿色三角运行（项目里已经带好
[`.run/CollabHub.run.xml`](.run/CollabHub.run.xml) 运行配置）。

**一个必须注意的设置**：IDEA 的 Maven home 要用 **Bundled (Maven 3.9.x)**，
不能用本机的 3.10.0。IDEA 2025.3 的 Maven 适配层只支持到 3.9.x，配成 3.10 会在导入时
报 `No implementation for ArtifactHandlerManager`，结果是模块挂载 **0 个依赖库**、
满屏 `找不到符号`。命令行用 3.10 完全没问题，两边互不影响。

## 常用命令

| 命令 | 作用 |
|---|---|
| `.\mvnw.cmd verify` | 全量构建 + 单元测试 + 集成测试（CI 跑的就是它） |
| `.\mvnw.cmd test` | 只跑单元测试（快，不起容器） |
| `.\mvnw.cmd -pl app spring-boot:run` | 本地启动 |
| `.\mvnw.cmd package` | 打包出 `app/target/*-exec.jar` |
| `docker compose up -d` | 起中间件（在 `infra` 下） |
| `docker compose --profile vector up -d` | 额外起 Qdrant |

## 约定

- **模块边界**：跨模块调用只能走对方暴露的 `*Api` 接口，不许直接 import 对方的 `internal` 包。
- **数据库迁移**：每个模块自带 `db/migration`，版本号按号段分配（见 [ADR-0003](docs/adr/0003-flyway-version-blocks-per-module.md)）。已执行的脚本永不修改。
- **错误响应**：统一 RFC 9457 `application/problem+json`（见 [ADR-0004](docs/adr/0004-problem-detail-for-errors.md)）。
- **提交信息**：`feat(DOC-004): 文档版本历史`，引用需求编号。

给 AI 助手看的详细约定在 [AGENTS.md](AGENTS.md)。
