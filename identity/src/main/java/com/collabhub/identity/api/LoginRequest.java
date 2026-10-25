package com.collabhub.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>注意这里<b>没有</b>对密码做长度校验。原因：登录时的密码只是"拿来比对"，
 * 校验规则应该由注册环节负责。如果登录也加 min=8，攻击者就能通过"密码太短"这条
 * 错误信息反推出「这个账号的密码长度 >= 8」——虽然信息量很小，但没必要泄露。
 *
 * <p>同理，邮箱格式校验放在这里只是为了挡住明显的脏数据（不发数据库查询），
 * 真正的"账号是否存在"绝不会通过错误信息暴露。
 *
 * @param email    登录邮箱
 * @param password 明文密码，只在比对瞬间存在
 */
public record LoginRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
