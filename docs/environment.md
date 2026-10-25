# CollabHub 开发环境基线 v2

> 更新：2026-10-25　机器：Windows 11（Build 26200）　32 GB 内存　工作目录：`D:\CollabHub`
>
> 状态：**环境已就绪**。中间件全部跑通，JDK 25 与 Maven 已装好。
> 唯一待办：用 root 执行一次建库脚本（见 §6）。
>
> 需求范围见 [需求清单](requirements.md)。

---

## 1. 当前环境

| 组件 | 版本 | 位置 | 状态 |
|---|---|---|---|
| JDK | **25.0.4.1 LTS**（Temurin） | `D:\proRuan\jdk-25\jdk-25.0.4.1+1` | ✅ 新装 |
| JDK（旧的） | 17.0.20 | `D:\proRuan\java17` | 保留不删 |
| Maven | **3.10.0** | `D:\proRuan\maven\apache-maven-3.10.0` | ✅ 新装 |
| Maven 本地仓库 | — | `D:\mavenku` | 已指向 D 盘（2026-10-25 从 `D:\proRuan\maven-repo` 合并迁移） |
| MySQL | 8.0.35 服务 `MySQL80`，端口 3306 | `C:\Program Files\MySQL` | ✅ 运行中 |
| Node.js | 24.22.1 | `D:\proRuan\node` | ✅ |
| npm / pnpm | 11.19.1 / 11.8.0 | — | ✅ |
| Docker Desktop | 客户端 29.9.2 / Server 29.9.2 / Compose v5.6.1 | `C:\Program Files\Docker\Docker` | ✅ 引擎已通 |
| Git | 2.56.0 | `D:\proRuan\git` | ✅ |
| IntelliJ IDEA | 2025.3.6.1 | `D:\proRuan\IntelliJ IDEA 2025.3.6.1` | ✅ |
| VS Code | 1.143.1 | — | ✅ |
| Navicat Premium Lite | 17 | `D:\proRuan` | ✅ |
| Redis | 7.4-alpine（容器 `ch-redis`） | localhost:6379 | ✅ healthy |
| RabbitMQ | 4.4.0（容器 `ch-rabbitmq`） | localhost:5672 | ✅ healthy |
| MinIO | 容器 `ch-minio` | localhost:9000 | ✅ 运行中 |
| Mailpit | 容器 `ch-mailpit` | localhost:1025 | ✅ healthy |

内存 32 GB → Docker Desktop 建议分配 **8 GB / 6 CPU**（Settings → Resources），够同时跑 MySQL + 4 个容器 + 后期 Kafka/OpenSearch。

---

## 2. 版本决策

### 2.1 JDK 17 → 25（已执行）

| | 说明 |
|---|---|
| 为什么升 | 虚拟线程（JEP 444）是这个项目最值钱的学习点——协作平台是典型 IO 密集型，虚拟线程直接把「线程池该调多大」这个问题消灭掉。17 上用不了。 |
| 为什么是 25 | 2025 年 9 月 GA 的 LTS，支持到 2032；Spring Boot 3.5.x 与 IDEA 2025.3 都已支持。 |
| 怎么升的 | 下载 Temurin 25.0.4.1 的 zip，解压到 `D:\proRuan\jdk-25`，不动原有的 17。 |
| 环境变量 | **用户级** `JAVA_HOME` = JDK 25 路径（用户级会覆盖系统级的 17）。 |

> ⚠️ **一个已知的小问题**：系统级 PATH 里还留着 JDK 17 的 `bin`，而 Windows 的 PATH 是「系统在前、用户在后」，所以**新开终端执行 `java -version` 仍然会显示 17**。
> 这不影响实际开发——Maven、Spring Boot、IDEA 都读 `JAVA_HOME`，实测 `mvn -v` 显示的是 **Java 25.0.4.1**。
> 如果你想让 `java -version` 也干净，见 §7 的一次性修复。

### 2.2 数据库：保持 MySQL 8（不换 PostgreSQL）

**决策记录**：最初建议 PostgreSQL 17 + pgvector，理由是一个库能覆盖 JSONB、全文检索、向量检索。你决定保持 MySQL，理由站得住：**没用过、工作中用不到，先在自己的主力技术栈上把项目做完，比同时学新数据库更稳**。

接受这个决定，但要把代价说清楚：

| 能力 | 有 PG 时 | 只有 MySQL 时 | 应对方案 |
|---|---|---|---|
| 向量检索 | `pgvector`，一条 SQL | 无原生支持 | 先用 Java 暴力算余弦相似度（几万条完全够），再换 **Qdrant** 容器 |
| 中文全文检索 | `pg_trgm` / `zhparser` | 8.0 自带 **ngram 分词器**，可用但排序弱 | 先用 `FULLTEXT ... WITH PARSER ngram`，命中率不够再换 OpenSearch |
| 半结构化数据 | `jsonb` + GIN 索引 | `JSON` 列，函数较弱 | 权限规则、流程定义改成规范化的表结构 |
| 递归查询 | 原生递归 CTE | 8.0 支持递归 CTE | 无差异 |

**结论**：MySQL 8 做主库，`FULLTEXT ngram` 做第一阶段搜索，Qdrant 做向量库（`--profile vector` 时才启动）。

> 这个取舍本身就是一个很好的 ADR 素材：**技术选型不只看「哪个更强」，还要看「哪个我现在能掌控」**。

### 2.3 Maven 3.6 → 3.10.0（已执行）

3.6 是 2019 年的版本。装 3.10.0，并配好：
- 阿里云镜像（`maven.aliyun.com/repository/public`），依赖下载快一个数量级
- 本地仓库指向 `D:\mavenku`，不占系统盘（与原有 4.5 GB 仓库合并，`_remote.repositories` 已清理，支持离线构建）
- 默认编译级别 25

配置文件：`C:\Users\18174\.m2\settings.xml`

真正写项目时会用 **Maven Wrapper（`mvnw`）** 把版本锁进仓库，换机器不用再配。

---

## 3. 端口与账号

| 服务 | 端口 | 访问 | 账号 / 口令 |
|---|---|---|---|
| MySQL（原生） | 3306 | Navicat | 库 `collabhub`，应用账号 `collabhub` / `collabhub_dev_pwd`（待建） |
| Redis | 6379 | `redis-cli` | 口令 `collabhub_dev_pwd` |
| RabbitMQ | 5672 / **15672** | http://localhost:15672 | `collabhub` / `collabhub_dev_pwd` |
| MinIO | 9000 / **9001** | http://localhost:9001 | `collabhub` / `collabhub_dev_pwd` |
| Mailpit | 1025 / **8025** | http://localhost:8025 | 免登录 |
| Qdrant（按需） | 6333 / 6334 | http://localhost:6333/dashboard | 无 |
| 应用 | 8080 | — | — |
| 前端 dev | 5173 | — | — |

---

## 4. 中间件启动

```powershell
cd D:\CollabHub\infra
docker compose up -d          # 4 个服务
docker compose ps             # 全部 healthy
docker compose down           # 停
```

向量库按需启动（第 5 个月做语义搜索时）：

```powershell
docker compose --profile vector up -d
```

### 4.1 实测状态（2026-10-25）

| 容器 | 状态 | 验证方式 |
|---|---|---|
| ch-redis | healthy | `redis-cli ping` → `PONG` |
| ch-rabbitmq | healthy | `rabbitmq-diagnostics -q status` → 4.4.0 |
| ch-minio | running | `GET /minio/health/live` → 200 |
| ch-mailpit | healthy | `http://localhost:8025` → 200 |

---

## 5. 踩过的坑（下次照这个排）

| 现象 | 原因 | 解决 |
|---|---|---|
| `no configuration file provided: not found` | `docker compose` 只在当前目录找 `compose.yaml` | 先 `cd D:\CollabHub\infra` |
| `docker-credential-desktop: executable file not found` | Docker 的 bin 目录不在这个 shell 的 PATH 里 | 把 `C:\Program Files\Docker\Docker\resources\bin` 加到 PATH，或重开终端 |
| `minio/minio` 拉取报 `denied` | 当前镜像源 `docker.m.daocloud.io` 不放行该镜像 | 从 `docker.1panel.live` 拉取后重命名：<br>`docker pull docker.1panel.live/minio/minio:latest`<br>`docker tag docker.1panel.live/minio/minio:latest minio/minio:latest` |
| GitHub 下载 JDK 报 `Recv failure` | GitHub Release 直连被重置 | 用清华镜像 `mirrors.tuna.tsinghua.edu.cn/Adoptium/...` |
| `npm.ps1 无法加载，禁止运行脚本` | PowerShell 执行策略默认 Restricted | 用 `npm.cmd`，或管理员执行 `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser` |
| `java -version` 显示 17 | 系统 PATH 里的 JDK17 在前 | 见 §7，一次性修复 |

---

## 6. 唯一待办：建库

用 Navicat 以 **root** 连接 `localhost:3306`，打开 [infra/mysql/01-init.sql](../infra/mysql/01-init.sql) 执行一次。它会：

1. 建库 `collabhub`（utf8mb4）
2. 建应用账号 `collabhub` / `collabhub_dev_pwd`
3. 授予该库全部权限

执行完验证：

```sql
SELECT user, host FROM mysql.user WHERE user = 'collabhub';
-- 应该返回两行（localhost 和 %）
```

> 应用不用 root 连库，这是最基本的一条安全习惯。

---

## 7. 可选：让 `java -version` 也显示 25

需要管理员权限（改的是系统级 PATH）。任选一种：

**方式 A（图形界面，推荐）**
`Win + R` → `sysdm.cpl` → 高级 → 环境变量 → 系统变量里的 `Path` → 找到 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin` → 删除 → 确定。

**方式 B（命令行，管理员 PowerShell）**

```powershell
$p = [Environment]::GetEnvironmentVariable('PATH','Machine')
$p = ($p -split ';' | Where-Object { $_ -notmatch 'Adoptium\\jdk-17' }) -join ';'
[Environment]::SetEnvironmentVariable('PATH', $p, 'Machine')
```

改完重开终端。**JDK 17 的目录不要删** —— 以后遇到只支持 17 的老项目还要用。

---

## 8. 技术在什么时候登场

每项都是业务逼到那一步才引入，不是一次全上。

| 阶段 | 新出现的技术 | 对应需求 |
|---|---|---|
| M1 地基 | Maven Wrapper、Flyway、**Spring Modulith**、**Testcontainers**、**ArchUnit**、OpenTelemetry 骨架、虚拟线程 | NFR-MAINT-001~005 |
| M2 身份 | Spring Security + JWT、RBAC+ABAC、多租户行级隔离 | AUTH / WS 全模块 |
| M2 文档 | 领域建模、乐观锁、版本快照、JSON 列 | DOC-001~015 |
| M2 文件 | MinIO SDK、分片上传、SHA-256 去重、预签名 URL | FILE-001~009 |
| M3 评论 | diff 算法、锚点定位（划词评论） | CMT-001~006 |
| M3 实时 | WebSocket、STOMP、**Yjs / CRDT**、快照压缩 | RT-001~007 |
| M3 搜索 | MySQL `FULLTEXT` + ngram 分词、相关性打分 | SRCH-001~005 |
| M3 通知 | RabbitMQ、**Outbox 模式**、幂等消费、死信、延迟队列 | NOTI-001~007 |
| M4 AI | **Spring AI**、RAG、Embedding、**Qdrant**、Tool Calling | AI-001~009 |
| M4 平台 | API Key、限流算法、Webhook 签名、OpenAPI | OPEN-001~006 |
| M4 后台 | 功能开关、灰度、数据看板 | ADMIN-001~005 |
| M5 纵深 | Prometheus / Grafana / Loki / Tempo、k6 压测、缓存一致性、Toxiproxy 混沌 | NFR-PERF / NFR-OBS |
| M6 平台化 | k3s、网关、Saga、服务拆分 | 里程碑 M6 |

---

## 9. 环境之后的进展

环境这条线已经闭环，M1 地基也已完成（多模块工程、Modulith 边界、Flyway、Testcontainers、CI、4 篇 ADR）。
怎么构建、怎么启动、怎么验证见 [README.md](../README.md)。

日常只需要记住三条：

```powershell
.\mvnw.cmd verify                       # 全量构建 + 单元测试 + 集成测试
.\mvnw.cmd -pl app spring-boot:run      # 本地启动
cd infra; docker compose up -d          # 起中间件
```
