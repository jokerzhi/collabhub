# CollabHub 排查手册

> 这份文档记录的是**怎么找问题**，不是"有哪些问题"。
> 现象会变，方法不会。遇到问题先读第 1、2 节，别急着改代码。

---

## 1. 先分层：故障到底在哪一层

大部分时间浪费在「跨层乱试」。先用一分钟确定层，再开始找。

| 层 | 典型症状 | 证据在哪 |
|---|---|---|
| **① IDE 配置层** | 代码全是红波浪线、`找不到符号`、Run 按钮灰的、明明 `mvnw` 能过 IDEA 却不行 | IDEA 的 `build.log` 里的 `N modules, M libraries`；`idea.log` |
| **② 构建层** | `mvnw verify` 失败、测试红、依赖下载不了 | Maven 控制台输出（`-X` 开详细）、`target/*-reports/*.txt` |
| **③ 运行层** | 编译过了但启动就挂、端口冲突、连不上数据库 | 应用控制台、异常栈的**第一个** `Caused by` |
| **④ 环境层** | 中间件没起、容器 unhealthy、磁盘/内存不足 | `docker compose ps` / `docker logs` |

**判断口诀**：命令行能过、IDEA 不能过 → 一定是第 ① 层，去改 IDE 配置，别动代码。

---

## 2. 日志地图

`%LOCALAPPDATA%` = `C:\Users\18174\AppData\Local`

### 2.1 IDEA

| 文件 | 路径 | 里面有什么 |
|---|---|---|
| 主日志 | `%LOCALAPPDATA%\JetBrains\IntelliJIdea2025.3\log\idea.log` | 插件异常、Maven 导入过程、设置加载失败 |
| 构建日志 | 同目录 `log\build-log\build.log` | JPS 模型（模块数、库数量）、编译了哪些文件、耗时 |
| 编译错误明细 | 底部 **Build** 工具窗口 | 具体哪个文件哪一行报什么错（不落盘） |
| 项目模型（非日志但最直观） | `%LOCALAPPDATA%\JetBrains\IntelliJIdea2025.3\projects\collabhub.87f8a72f\external_build_system\` | `libraries.xml`（依赖库清单）、`modules\*.xml`（每个模块的 classpath） |

**最快打开方式**：IDEA 菜单 → **帮助 → 在资源管理器中显示日志**。

> 那个 `collabhub.87f8a72f` 里的哈希是 IDEA 按项目路径算的，每个项目不同。

### 2.2 构建与测试

| 位置 | 内容 |
|---|---|
| 控制台 | `mvnw verify` 的完整输出 |
| `app\target\surefire-reports\*.txt` | 单元测试失败详情（`*Test`） |
| `app\target\failsafe-reports\*.txt` | 集成测试失败详情（`*IT`） |
| `app\target\failsafe-reports\*.dumpstream` | 测试 JVM 的 stderr，有些错误只在这里 |

### 2.3 应用运行时

目前只输出到控制台（IDEA 的 Run 窗口或 `java -jar` 的终端）。

想让日志落盘，在 `application.yml` 加：

```yaml
logging:
  file:
    name: logs/collabhub.log
```

### 2.4 中间件

```powershell
cd infra
docker compose ps                      # 谁没起来、谁 unhealthy
docker compose logs -f rabbitmq        # 跟踪某个服务
docker logs ch-redis --tail 100        # 直接看容器
```

---

## 3. 五步法

### 第 1 步：分层

用第 1 节的表确定是 ① ② ③ ④ 哪一层。**只在这一层里找**。

### 第 2 步：找"定性证据"

定性证据 = **一句话就能证伪一批假设**的那行日志。

| 定性证据 | 直接排除了什么 |
|---|---|
| `Project has 6 modules, 0 libraries` | 排除"代码写错了"，问题在 IDE 给的 classpath |
| `unresolved: []` | 排除"依赖下载失败" |
| `BUILD SUCCESS` + 运行报错 | 排除"代码编译问题"，去看运行层 |
| `docker compose ps` 里 unhealthy | 排除"应用代码问题"，先修容器 |

### 第 3 步：沿因果链**只**上溯一层

错误是果，不是因。每找到一条线索，问一句"它的上游是什么"，然后**只验证那一个上游**。

### 第 4 步：对照实验，一次只改一个变量

这是最容易被忽视的一步。改两个变量，结果变了你也不知道是谁的功劳。

标准动作：

1. 提出假设（"是不是 X 导致的"）
2. 设计一个只改变 X 的实验
3. 跑，看结果
4. 结论写下来：**排除了** 还是 **锁定了**

> 关键：**"排除了"也是有价值的结论**，要记下来，否则你会反复试同一个方向。

### 第 5 步：修完要有量化验证

不要"感觉好了"。要有数字：

- 依赖库从 `0` → `123`
- `app` 模块 orderEntry 从 `0` → `129`
- 测试 `Tests run: 6, Failures: 0`
- 接口返回 `"schemaVersion":"1"`

---

## 4. 决策树（照着走）

```
运行失败
├─ IDEA 里有红波浪线 / 编译器报 "找不到符号"
│   ├─ 看 build.log 的 "N modules, M libraries"
│   │   └─ M = 0  → Maven 没导入成功 → 跳到 §5.1
│   └─ M > 0     → 真的是代码问题，看 Build 窗口第一条错误
│
├─ 编译过了，启动就退出
│   ├─ 端口被占（Port 8080 was already in use）
│   ├─ 数据源连不上（Communications link failure）
│   ├─ Flyway 失败（Validate failed / checksum mismatch）
│   └─ 配置项解析失败（Failed to bind properties）
│
├─ 启动了，但接口 500
│   └─ 响应体里的 traceId → 日志里搜这个 traceId → 看那条异常的 Caused by
│
└─ 容器起不来
    ├─ docker compose ps 看状态
    ├─ docker compose logs <服务> 看报错
    └─ 端口冲突 → 改 compose 里的宿主机端口映射
```

---

## 5. 已知故障库

全是这个项目真实踩过的，附「怎么认」和「怎么修」。

### 5.1 IDEA 导入后 0 个依赖库

| | |
|---|---|
| **现象** | IDEA 满屏 `找不到符号`、`程序包 org.springframework 不存在`；但 `.\mvnw.cmd verify` 是绿的 |
| **怎么认** | `build.log` 里 `Project has 6 modules, 0 libraries`；`idea.log` 里搜 `injector`，出现 `No implementation for ArtifactHandlerManager was bound` |
| **原因** | IDEA 配置的 Maven 是 3.10.0，而 IDEA 2025.3 的 Maven 适配层只支持到 3.9.x |
| **修法** | IDEA 设置里 Maven home 选 **Bundled (Maven 3.9.x)**。命令行继续用 3.10.0，互不影响 |
| **验证** | `external_build_system\project\libraries.xml` 有明显体积（本项目 85 KB / 123 个库） |

### 5.2 failsafe 报 `Failed to find merged annotation for @BootstrapWith`

| | |
|---|---|
| **现象** | `mvn test` 能过，`mvn verify` 里集成测试全挂 |
| **怎么认** | 错误信息里有 `BootstrapWith` 和 `TestContextAnnotationUtils` |
| **原因** | `spring-boot:repackage` 把主产物替换成 fat jar，failsafe 阶段类路径错乱 |
| **修法** | `spring-boot-maven-plugin` 加 `<classifier>exec</classifier>`，保留原始薄 jar |
| **验证** | `mvn verify` 全绿；产物是 `app-0.1.0-SNAPSHOT-exec.jar` |

### 5.3 `no configuration file provided: not found`

| | |
|---|---|
| **原因** | 在 `D:\CollabHub` 根目录跑 `docker compose`，但 compose.yaml 在 `infra\` 下 |
| **修法** | `cd infra` 后再执行；或 `docker compose -f infra\compose.yaml up -d` |

### 5.4 `docker-credential-desktop: executable file not found`

| | |
|---|---|
| **原因** | Docker 的 bin 目录不在当前 shell 的 PATH |
| **修法** | 把 `C:\Program Files\Docker\Docker\resources\bin` 加进 PATH，或重开终端 |

### 5.5 拉 `minio/minio` 报 `denied`

| | |
|---|---|
| **原因** | 镜像源 `docker.m.daocloud.io` 不放行该镜像 |
| **修法** | 从 `docker.1panel.live` 拉取后重命名成本地名 |

### 5.6 离线模式报 `present, but unavailable`

| | |
|---|---|
| **现象** | `mvn -o` 报构件"存在但不可用"，构件明明在本地仓库里 |
| **原因** | `_remote.repositories` 记的是旧仓库 ID，和当前仓库不匹配 |
| **修法** | 删掉本地仓库里的 `_remote.repositories` 和 `*.lastUpdated`（迁移仓库时一次清干净） |

### 5.7 `java -version` 还是 17

| | |
|---|---|
| **原因** | Windows 的 PATH 是「系统在前、用户在后」，系统级 PATH 里的 JDK 17 排前面 |
| **修法** | 删掉系统级 PATH 里的 jdk-17 条目（需要管理员），或直接用 `JAVA_HOME` 驱动（Maven/IDEA 都读它） |

### 5.8 `npm.ps1 无法加载，禁止运行脚本`

| | |
|---|---|
| **原因** | PowerShell 执行策略默认 Restricted |
| **修法** | 用 `npm.cmd`；或管理员执行 `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser` |

---

## 6. 两个完整案例

### 6.1 IDEA 0 依赖库（因果链）

```
① 你说"运行不起来"
       ↓ 没有有效信息，先看证据
② .idea/misc.xml 有 MavenProjectsManager
       ↓ 项目确实以 Maven 导入过 —— 排除"没导入"
③ build.log: "6 modules, 0 libraries"
       ↓ classpath 是空的 —— 排除"代码写错"
④ idea.log: "No implementation for ArtifactHandlerManager was bound"
       ↓ Maven 服务器的依赖注入失败，导入中途夭折
⑤ 时间戳 13:53 < 项目打开时间 14:08
       ↓ 问题在项目之前就存在 —— 是 IDE 全局配置，不是这个项目
⑥ 看 Maven 服务器启动参数: embedder.version=3.10.0
   IDEA 自带: maven-core-3.9.11.jar
       ↓ 版本不匹配 → 锁定根因
⑦ 改配置 → 重启 → libraries.xml 123 个库 → 验证通过
```

**教训**：`0 libraries` 这种平淡的 INFO 行，比任何 `ERROR` 都值钱。

### 6.2 failsafe 注解丢失（对照实验）

| 假设 | 实验 | 结果 |
|---|---|---|
| JDK 25 字节码太新 | `-Dmaven.compiler.release=21` 重跑 | 还报错 → **排除** |
| failsafe 的 manifest-only jar 有问题 | `-Dfailsafe.useManifestOnlyJar=false` | 还报错 → **排除** |
| 是 failsafe 特有的问题 | 同一个测试改用 surefire 跑 | 通过 → 缩小到 failsafe |
| 是 `package` 阶段搞坏的 | 跳过 package 直接跑 failsafe | 通过 → **锁定 repackage** |

四次实验，每次只动一个变量。

---

## 7. 反模式：别这样做

| ❌ 别做 | 为什么 |
|---|---|
| 一上来就 `Invalidate Caches` / 重装 IDEA / 重装 JDK | 这些是"不知道原因时的大扫除"，会把真正的证据一起清掉 |
| 同时改三四个地方再试 | 成功了也不知道是哪个起效，失败了也不知道是哪个没用 |
| 只看最后一行报错 | 最后一个异常通常是最外层的包装，真正的因在第一个 `Caused by` |
| 看到 `ERROR` 才看日志 | 最有价值的线索经常在 `INFO` 和 `WARN` 里 |
| 用 `System.out.println` 代替日志 | 打不出 traceId、时间、线程，出问题还是得回来加日志 |
| 改了配置不重启 | IDEA 的 Maven 设置、JVM 参数这类，必须重启才生效 |

---

## 8. 什么时候该求助，怎么问

自己排查 20 分钟没进展，就该问。但**问法决定效率**。

### 好的问法

```
现象：IDEA 里所有 Spring 相关 import 报红，但命令行 mvnw verify 是绿的
证据：build.log 里 "6 modules, 0 libraries"
已排除：pom 没问题（命令行能构建）、JDK 是 25（mvn -v 显示 25）
已尝试：Reload All Maven Projects（无效）、删 .idea 重新导入（无效）
```

### 差的问法

```
"跑不起来，怎么办"      ← 零信息，只能反问
"帮我看看"              ← 看不出你想让我看什么
```

**给证据 > 给结论 > 给情绪**。

---

## 9. 常用命令速查

```powershell
# ---------- IDEA ----------
$idea = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2025.3\log\idea.log"
Select-String -Path $idea -Pattern 'ERROR|Exception' | Select-Object -Last 30
Select-String -Path $idea -Pattern 'injector|unresolved|import finished'
Get-Content "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2025.3\log\build-log\build.log" -Tail 40

# ---------- 构建 ----------
.\mvnw.cmd verify                                  # 全量
.\mvnw.cmd test                                    # 只单元测试
.\mvnw.cmd -B -ntp -X verify 2>&1 | Tee-Object build.log   # 详细依赖解析
.\mvnw.cmd -o verify                               # 离线，验证依赖是否齐全

# ---------- 测试报告 ----------
Get-Content app\target\surefire-reports\*.txt
Get-Content app\target\failsafe-reports\*.txt

# ---------- 应用 ----------
.\mvnw.cmd -pl app spring-boot:run
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/system/info

# ---------- 中间件 ----------
cd infra; docker compose ps
docker compose logs -f rabbitmq
docker logs ch-redis --tail 100

# ---------- 端口 ----------
netstat -ano | Select-String ':8080|:3306|:6379|:5672'
```
