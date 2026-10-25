package com.collabhub.identity.web;

import com.collabhub.identity.api.RegisterRequest;
import com.collabhub.identity.api.UserView;
import com.collabhub.identity.internal.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/api/v1/identity/users")
public class IdentityController {

    private final RegistrationService registrationService;

    public IdentityController(RegistrationService registrationService) {
        this.registrationService = registrationService;
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
    @PostMapping
    public ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest request) {
        UserView user = registrationService.register(request);
        return ResponseEntity
                .created(URI.create("/api/v1/identity/users/" + user.id()))
                .body(user);
    }
}
