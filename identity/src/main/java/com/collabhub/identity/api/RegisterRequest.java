package com.collabhub.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>这里的校验注解负责「格式层面」的拦截（空、太长、不像邮箱），
 * 业务规则（邮箱是否已存在、密码是否太弱）放在 Service 里，
 * 因为那些需要访问数据库或需要给出可读的业务错误码。
 *
 * @param email       登录邮箱
 * @param displayName 展示名，2-32 字符
 * @param password    明文密码，只在这一层存在，落库前就会被哈希掉
 */
public record RegisterRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过 254 个字符")
        String email,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 2, max = 32, message = "昵称长度必须在 2 到 32 个字符之间")
        String displayName,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 个字符之间")
        String password
) {
}
