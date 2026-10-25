# ADR-0002 用 Spring Modulith + ArchUnit 双重守护模块边界

- **状态**：已接受
- **日期**：2026-10-25
- **相关**：ADR-0001

## 背景

ADR-0001 选了模块化单体。但「模块化」如果只靠自觉，三个月后就会退化：
有人（包括我自己、也包括 AI）为了省事直接 `import` 另一个模块的 Repository，
编译能过，边界就破了。

所以需要一个**能让构建失败**的机制。

## 候选方案

| 方案 | 能防住什么 | 代价 |
|---|---|---|
| 纯人工 code review | 明显违规 | 不可靠，自己和 AI 都容易放过 |
| 拆成 Maven 模块，靠依赖控制 | 跨模块依赖 | 只能控制到「模块级」，控制不到「某个包」；而且拆得太碎 |
| ArchUnit 自定义规则 | 任意包/类级别的规则 | 规则要自己写，容易漏 |
| **Spring Modulith** | 循环依赖、访问非暴露包、模块结构一致性 | 需要按约定组织包结构 |
| **两者都用** | 上述全部 | 配置略多，两条规则体系要维护 |

## 决定

**两者都用，分工明确：**

1. **Spring Modulith** 负责「模块之间」：自动发现 `com.collabhub.*` 下的直接子包为模块，
   校验无循环、无越界访问，并生成模块文档。
2. **ArchUnit** 负责「代码层面」：自定义规则，比如「platform 不许依赖业务模块」、
   「Controller 不许直接碰 JdbcTemplate」。

两者都在 `mvn verify` 里执行，任何一条破了，CI 红。

## 包结构约定

```
com.collabhub.<module>
├── <Module>Api.java        ← 对外接口（其他模块只能通过它调用）
├── api/                    ← 对外 DTO
├── internal/               ← 内部实现，禁止跨模块访问
│   ├── domain/
│   ├── repository/
│   └── service/
└── web/                    ← 本模块的 Controller
```

- `platform` 标记为 `@ApplicationModule(type = OPEN)`，因为它是共享内核，
  所有模块都允许访问它的任意包。
- 业务模块之间**不互相 import**，需要数据时通过对方暴露的 `*Api` 接口。

## 后果

### 正面

- 边界违规是编译/构建期错误，不是六个月后的技术债。
- Modulith 能生成模块依赖图，架构文档自动更新。
- 给 AI 写代码时有了硬约束：它越界，测试直接挂，不用我逐行 review。

### 代价

- 模块间调用需要写接口和 DTO，比直接 `import` 啰嗦一点。
- 初期会有「为了一个方法新建一个 Api 接口」的摩擦，这是必要成本。
- Modulith 对包结构敏感，重构包名时要同步调整 `@ApplicationModule` 声明。

## 验证方式

- `ModularityTest`：`ApplicationModules.of(...).verify()`
- `ArchitectureTest`：三条 ArchUnit 规则
- 两个测试都在 `mvn verify` 中执行
