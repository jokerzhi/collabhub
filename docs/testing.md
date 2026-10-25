# CollabHub 测试指南

> 这份文档解释「每个测试防的是什么、失败时怎么读、新功能该补哪种测试」。
> 排查线上/环境问题看 [troubleshooting.md](troubleshooting.md)，这里只管测试。

---

## 1. 三层测试，三种代价

| 层 | 类名后缀 | 执行者 | 命令 | 耗时 | 起 Spring？ | 起容器？ |
|---|---|---|---|---|---|---|
| 架构测试 | `*Test` | surefire | `mvn test` | 毫秒 | 否 | 否 |
| 单元测试 | `*Test` | surefire | `mvn test` | 毫秒 | 否 | 否 |
| 集成测试 | `*IT` | failsafe | `mvn verify` | 20–40 秒 | 是 | 是（真 MySQL） |

分层的唯一理由是**反馈速度**。

- 改一行代码，`mvn test` 两秒出结果 → 你愿意每次都跑
- 如果所有测试都要起容器，一分钟一轮 → 你会开始跳过它

所以：**能放在快层的，绝不放到慢层。**

---

## 2. 守卫矩阵：谁真正在管什么

这是最容易误解的部分。三条"硬规则"其实由不同角色把关，强度也不同。

| 规则 | Maven | Spring Modulith | ArchUnit |
|---|:--:|:--:|:--:|
| 业务模块之间不许互相 import | ✅ **真正在管** | 复核 | — |
| platform 不许依赖业务模块 | ✅ | — | ✅ |
| Controller 不许碰 JdbcTemplate | ❌ 管不了 | — | ✅ **只有它能管** |
| 模块之间不许有循环依赖 | ❌ | ✅ | ✅ |

结论：**ArchUnit 的"Controller 不碰数据库"是唯一不可替代的**——`JdbcTemplate` 就在 classpath 上，编译器不会拦你，只有 ArchUnit 会。

Modulith 的价值在另外两处：
1. 将来若把模块合并成单个 artifact，Maven 的物理隔离失效，它还在
2. 能生成模块依赖关系文档

---

## 3. ModularityTest

文件：`app/src/test/java/com/collabhub/ModularityTest.java`

```java
private static final ApplicationModules MODULES = ApplicationModules.of(CollabHubApplication.class);

@Test void verifiesModularStructure() { MODULES.verify(); }

@Test void discoversExpectedModules() {
    List<String> names = MODULES.stream()
            .map(m -> m.getDisplayName().toLowerCase(Locale.ROOT))
            .toList();
    assertThat(names).contains("platform", "identity", "workspace", "document");
}
```

### 3.1 `ApplicationModules.of()` 做了什么

从启动类出发，扫 `com.collabhub` 下的**第一层子包**，每个子包 = 一个模块：

```
com.collabhub.platform   → platform
com.collabhub.identity   → identity
com.collabhub.workspace  → workspace
com.collabhub.document   → document
```

然后读字节码，画「谁依赖谁」的图。**不启动应用**，纯静态分析。

### 3.2 `verify()` 检查三件事

| 检查 | 失败时的报错 |
|---|---|
| 无循环依赖 | `Cycle detected: identity -> workspace -> identity` |
| 不访问别人未公开的包 | `Module 'identity' depends on non-exposed type ...internal.Secret` |
| 代码与 `@ApplicationModule` 声明一致 | 各种结构不一致 |

「公开」的定义：模块的**根包**对外可见，子包（如 `internal`）默认隐藏。

### 3.3 为什么需要第二个测试

`verify()` 有个特性：**如果一个模块都没发现，它可能静默通过**。包名改错、`@ApplicationModule` 写坏，你会得到一个假绿灯。

`discoversExpectedModules` 是给 `verify()` 加的前提校验：

> **先断言前提成立，再断言规则成立。**

这是写架构测试的通用套路。

---

## 4. ArchitectureTest

文件：`app/src/test/java/com/collabhub/ArchitectureTest.java`

### 4.1 先学会读这种链式写法

```java
noClasses()      // 主语：这些类
  .that()        // 筛选：什么样的类
  .should()      // 谓语：不许做什么
```

连起来读：**"no classes that … should …"** = 「满足 A 条件的类，不许做 B」。

### 4.2 三条规则

**规则 1：平台不许反向依赖业务**

```java
noClasses()
    .that().resideInAPackage("com.collabhub.platform..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("com.collabhub.identity..",
                        "com.collabhub.workspace..",
                        "com.collabhub.document..");
```

为什么：platform 是共享内核，一旦它 import 业务模块，就再也无法独立理解、独立测试、独立拆出。

**规则 2：模块之间不许成环**

```java
slices()
    .matching("com.collabhub.(*)..")
    .should().beFreeOfCycles();
```

`(*)` 把每个子包切成一片，检查片与片之间没有环。有环 = 两个模块分不开。

**规则 3：Controller 不许碰数据库**

```java
noClasses()
    .that().haveSimpleNameEndingWith("Controller")
    .should().dependOnClassesThat()
    .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate");
```

### 4.3 真实失败长什么样

故意写一个 `TempBadController` 用 `JdbcTemplate`，测试输出：

```
Rule 'no classes that have simple name ending with 'Controller' should depend on
classes that have fully qualified name 'org.springframework.jdbc.core.JdbcTemplate''
was violated (3 times):

Constructor <TempBadController.<init>(JdbcTemplate)> has parameter of type <JdbcTemplate>
Field <TempBadController.jdbc> has type <JdbcTemplate>
Method <TempBadController.ping()> calls method <JdbcTemplate.queryForObject(...)>
```

注意它从**三个角度**抓：构造器参数、字段类型、方法调用。任何一处都算依赖，绕不过去（改成静态方法、反射也逃不掉）。

### 4.4 两个注解

```java
@AnalyzeClasses(packages = "com.collabhub",
                importOptions = ImportOption.DoNotIncludeTests.class)
```

- `packages`：扫描范围
- `DoNotIncludeTests`：**排除测试代码自己**，否则测试里 new 一个 JdbcTemplate 也会违规

```java
@ArchTest static final ArchRule 名字 = ...
```

每个字段 = 一条独立规则，JUnit 分别显示通过/失败。

### 4.5 自己动手破坏一次

```powershell
# 1. 在 platform/src/main/java/com/collabhub/platform/system/ 下新建 TempBadController.java
#    里面注入 JdbcTemplate
# 2. 跑
.\mvnw.cmd -pl app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
# 3. 看到上面那段 violation，删掉临时类
```

亲眼看过一次失败，比读十遍文档记得牢。

---

## 5. SystemInfoApiIT

文件：`app/src/test/java/com/collabhub/SystemInfoApiIT.java`

### 5.1 执行流程

```
① @Testcontainers 启动
        ↓
② Docker 起 mysql:8.0.35，随机端口
        ↓
③ @DynamicPropertySource 把容器 URL 注入 Spring 配置
        ↓
④ @SpringBootTest 起完整上下文 + Tomcat（随机端口）
        ↓
⑤ Flyway 自动在容器里执行 V1
        ↓
⑥ TestRestTemplate 发真 HTTP 请求
        ↓
⑦ 断言
        ↓
⑧ 容器销毁
```

关键：**你本机 3306 上那个 MySQL 完全不参与**，测试用随机端口的临时容器。

### 5.2 `@DynamicPropertySource` 为什么必需

容器端口是**启动时才分配**的（可能是 49153）。`application.yml` 里写死 `localhost:3306` 会指向你的开发库。

```java
@DynamicPropertySource
static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
}
```

Spring 建数据源之前先问容器「你的 URL 是什么」。**删掉这段，测试会连到你的开发库上**，污染数据或直接失败。

### 5.3 七条断言，每条证明一层

| 断言 | 证明什么 |
|---|---|
| `status == 200` | 路由、控制器、JSON 序列化都通 |
| `X-Request-Id` 非空 | `RequestIdFilter` 执行了 |
| `application == "collabhub"` | 配置读到了 |
| `javaVersion` 以 `25` 开头 | 跑的是 JDK 25 |
| `database` 含 `8.0` | 真连上 MySQL |
| `schemaVersion == "1"` | Flyway 执行了 V1 |
| `settings` 含 `app.timezone` | 迁移里的 INSERT 真落库了 |

这叫**一条断言一个层次**。挂了不用猜——看哪条红就知道断在哪层：

- 200 过、`database` 挂 → 应用起来了但连不上库
- `database` 过、`schemaVersion` 挂 → 连上了但迁移没跑

### 5.4 为什么用 `TestRestTemplate` 而不是 MockMvc

MockMvc 绕过真实 Servlet 容器，验证不到过滤器链、序列化、真实 HTTP 状态码。
`TestRestTemplate` 发的是**真请求**，所以能覆盖「Tomcat + 过滤器 + 拦截器 + 序列化」全链路。

代价是慢（20 秒起容器），所以只放一条。

---

## 6. 怎么跑

```powershell
.\mvnw.cmd test                                  # 只跑快层（架构 + 单元）
.\mvnw.cmd verify                                # 全量，含集成测试
.\mvnw.cmd test -Dtest=ArchitectureTest          # 单跑某个类
.\mvnw.cmd verify -Dit.test=SystemInfoApiIT      # 单跑集成测试
```

### 已知坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `No tests were executed!` | `-Dtest=X` 对多模块**全部生效**，某个模块里没有 X | 加 `-Dsurefire.failIfNoSpecifiedTests=false` |
| 集成测试卡住不动 | Docker 没启动 / 镜像在拉 | 先 `docker run --rm hello-world` 确认 |
| 测试很慢 | 镜像没缓存 | 第一次拉 mysql 约 600MB，之后走本地缓存 |

---

## 7. 模板：以后照抄

### 7.1 新增接口 → 一条 `*IT`

```java
@Test
void createWorkspaceReturnsCreated() {
    var body = Map.of("name", "我的空间", "slug", "my-space");

    ResponseEntity<Map> res = rest.postForEntity("/api/v1/workspaces", body, Map.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(asMap(res.getBody()))
            .containsEntry("slug", "my-space")
            .containsKey("id");
}
```

**要求**：
- 只断言「状态码 + 关键字段」，不做全字段比对（否则改字段就红）
- 一个用例只验证一条规则
- M2 起要解决**测试间数据污染**：每个用例自己造数据、自己清理，或用 `@Sql` 重置（这是 M2 的待办）

### 7.2 新增业务规则 → 一条 `*Test`

```java
@Test
void rejectsWeakPassword() {
    var ex = assertThrows(BusinessException.class,
            () -> PasswordPolicy.validate("12345678"));

    assertThat(ex.getCode()).isEqualTo("WEAK_PASSWORD");
}
```

**要求**：不连数据库、不起 Spring、不 mock 一堆东西。纯逻辑就纯测。

### 7.3 新增架构约束 → 一条 `@ArchTest`

```java
@ArchTest
static final ArchRule service_must_not_depend_on_web_layer =
        noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..");
```

**什么时候加**：同一种错误犯了第二次，就把口头约定变成规则。

### 7.4 新增模块 → 更新 `discoversExpectedModules`

```java
assertThat(names).contains("platform", "identity", "workspace", "document", "notification");
```

---

## 8. 失败信息速查

| 报错片段 | 含义 | 去哪修 |
|---|---|---|
| `Cycle detected: A -> B -> A` | 两模块互相依赖 | 抽第三模块，或改成事件解耦 |
| `depends on non-exposed type ...internal.X` | 访问了别人内部类 | 改用对方 `*Api` 接口 |
| `was violated (N times)` + 类名 | ArchUnit 规则被破 | 按 violation 列出的位置逐个改 |
| `Failed to load ApplicationContext` | 上下文装配失败 | 往下看第一个 `Caused by` |
| `Could not find a valid Docker environment` | Testcontainers 找不到 Docker | 启 Docker Desktop |
| `Communications link failure` | 连不上数据库 | 容器没起来 / URL 错 |
| `Migration V1 ... failed` | SQL 本身有错 | 看 Flyway 报的具体行 |

---

## 9. 关于覆盖率

这个项目**不追求覆盖率数字**。

理由是：覆盖率只度量"代码被执行过"，不度量"分支被验证过"。一个 90% 覆盖率的项目照样可以在边界条件上全是洞。

真正该花力气的地方，按性价比排序：

1. **架构测试**（当前 5 条）—— 一条规则能防住一整类未来错误
2. **业务规则的边界条件** —— 空、超长、越权、并发、重复提交
3. **关键链路的集成测试** —— 一条覆盖十几个组件

第 1 类最便宜、收益最大，这也是为什么 M1 先写它。
