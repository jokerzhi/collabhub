# ADR-0004 统一错误响应用 RFC 9457 ProblemDetail

- **状态**：已接受
- **日期**：2026-10-25
- **相关**：ADR-0002

## 背景

所有接口都需要一套一致的错误响应格式。否则前端要为每个接口写一遍
「这个接口失败时字段叫什么、在哪一层」。

Java 生态里最常见的两种做法是自造响应信封和用标准错误格式。

## 候选方案

### 方案 A：自造信封 `{"code": 20001, "msg": "xx", "data": null}`

- 优点：国内团队最熟悉，前端好写；业务码可以做得很细。
- 缺点：
  - HTTP 状态码和业务码两套体系，很容易互相矛盾（HTTP 200 里套一个错误码）。
  - 每个接口都要包一层 `Result<T>`，泛型污染整条链路。
  - 没有标准工具链支持，OpenAPI 里还得手工描述一遍。
  - 错误码表需要长期维护，最后没人记得 20001 和 20002 的区别。

### 方案 B：只返回 HTTP 状态码 + 纯文本

- 优点：零成本。
- 缺点：前端拿不到可判定的错误类型，只能靠字符串匹配。

### 方案 C：RFC 9457 Problem Details（`application/problem+json`）

- 优点：
  - IETF 标准（RFC 9457，取代了 RFC 7807），字段语义固定：`type`、`title`、
    `status`、`detail`、`instance`，自定义字段放扩展成员。
  - HTTP 状态码就是错误种类，不再有「两套体系」的问题。
  - Spring Framework 6 原生支持：`ProblemDetail` 类、`ResponseEntityExceptionHandler`
    的默认实现，框架异常（405、415、参数绑定失败）自动就是标准格式。
  - OpenAPI / 前端生成器能识别。
- 缺点：国内团队不熟，需要向「接口调用方」解释一次；业务错误码要用扩展字段承载。

## 决定

**采用方案 C。**

- 所有错误响应：`Content-Type: application/problem+json`
- `status` 与 HTTP 状态码一致
- `title` 放稳定的错误标识（大写下划线，如 `VALIDATION_FAILED`），供前端 switch
- `detail` 放给用户看的中文说明
- 扩展字段：
  - `code`：与 `title` 相同的机器可读标识（保持兼容习惯）
  - `errors`：字段级校验错误 `{"field": "message"}`
  - `traceId`：链路 ID，用户报错时贴这个就能定位

示例：

```json
{
  "type": "urn:collabhub:error:VALIDATION_FAILED",
  "title": "VALIDATION_FAILED",
  "status": 400,
  "detail": "请求参数校验失败",
  "code": "VALIDATION_FAILED",
  "errors": { "name": "长度必须在 2 到 64 之间" },
  "traceId": "4f2a9c1e8b7d6a5f"
}
```

## 后果

### 正面

- 前端只需要写一次错误处理。
- 框架异常开箱即用，不用逐个拦截。
- 和 HTTP 语义一致，网关、监控、日志都能直接按状态码统计。

### 代价

- 团队需要学习一次标准字段含义（一次性成本）。
- 业务错误码只能放扩展字段，不能作为一等公民；所以 `title` 的取值要有纪律，
  必须是稳定的机器标识，不能写成人话。

## 约束

1. 抛 `BusinessException(status, code, message)`，由 `GlobalExceptionHandler` 统一转换。
2. **不要**在 Controller 里手写 `ResponseEntity.status(...).body(...)` 拼错误响应。
3. 未预期异常一律 500，`detail` 里不能泄露堆栈、SQL、内部路径。
4. 错误信息里不能出现「用户不存在」这种可用于枚举账号的措辞 —— 统一说「邮箱或密码错误」。
