package com.collabhub.identity.web;

import com.collabhub.identity.api.LoginRequest;
import com.collabhub.identity.api.LoginResponse;
import com.collabhub.identity.api.RegisterRequest;
import com.collabhub.identity.api.UserView;
import com.collabhub.identity.internal.service.ClientInfo;
import com.collabhub.identity.internal.service.LoginService;
import com.collabhub.identity.internal.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 身份模块的 HTTP 接口。
 *
 * <p>路径带模块前缀 {@code /api/v1/identity/}，而不是直接 {@code /api/v1/users}。
 * 理由：模块是代码的所有者，接口路径跟着模块走，将来一眼能看出某个接口归谁维护；
 * 也避免了 identity 和 workspace 都想用 {@code /users} 这种冲突。
 *
 * <p>控制器只做三件事：接参数、调 Service、拼响应码。业务逻辑一行都不在这里。
 *
 * <p><b>为什么登录用 {@code /sessions} 而不是 {@code /login}：</b>
 * 登录的本质是"创建一个会话"，登出是"删除一个会话"，
 * 用资源风格的路径，后面加 {@code DELETE /sessions/{id}} 吊销设备和
 * {@code GET /sessions} 查看登录设备都很自然。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

    private final RegistrationService registrationService;
    private final LoginService loginService;

    public IdentityController(RegistrationService registrationService, LoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
    }

    /**
     * 注册新用户。
     *
     * <p>成功返回 201 + {@code Location} 头，符合 REST 语义：新资源被创建时
     * 应该告诉调用方它在哪里。失败时错误格式由全局异常处理器统一产出（RFC 9457）。
     *
     * @param request 注册信息，校验失败会由全局处理器转成 400 + 字段级错误
     * @return 新建的用户视图（不含任何密码信息）
     */
    @PostMapping("/users")
    public ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest request) {
        UserView user = registrationService.register(request);
        return ResponseEntity
                .created(URI.create("/api/v1/identity/users/" + user.id()))
                .body(user);
    }

    /**
     * 登录，创建会话。
     *
     * <p>成功返回 200 + 双令牌。失败时：
     * <ul>
     *   <li>401 {@code INVALID_CREDENTIALS} —— 邮箱不存在或密码错误（两者不区分）</li>
     *   <li>429 {@code ACCOUNT_LOCKED} —— 连续失败达阈值，账号临时锁定</li>
     *   <li>403 {@code ACCOUNT_DISABLED} —— 账号被封禁或已注销</li>
     * </ul>
     *
     * @param request 邮箱 + 密码
     * @param http    用来取 IP 和 UA，仅写入会话记录供展示
     * @return 双令牌
     */
    @PostMapping("/sessions")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest http) {
        ClientInfo client = new ClientInfo(clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok(loginService.login(request, client));
    }

    /**
     * 取客户端 IP。
     *
     * <p>{@code X-Forwarded-For} 是客户端可以随便伪造的，所以这个值<b>只用于展示</b>
     * （"登录设备"列表），绝不能用来做访问控制或风控决策。
     * 取第一个地址是因为它是"最初的客户端"，后面的都是代理链。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
