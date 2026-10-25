package com.collabhub.identity.internal.domain;

import com.collabhub.platform.error.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * 密码复杂度规则。
 *
 * <p>为什么单独抽一个类：这条规则会被三个地方用到（注册、改密、重置），
 * 写三遍迟早不一致。抽出来还能被单元测试直接覆盖，不需要起 Spring。
 *
 * <p>规则刻意宽松：
 * <ul>
 *   <li>长度 ≥ 8（长度比复杂度更有效，这条是底线）</li>
 *   <li>至少包含一个字母和一个数字（挡住 "12345678" 这种）</li>
 * </ul>
 * 不加"必须含大写 / 必须含符号"，因为那会逼用户写 <code>Password1!</code>，
 * 反而更容易被猜。
 */
public final class PasswordPolicy {

    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    /**
     * 校验明文密码，不合规直接抛业务异常。
     *
     * @throws BusinessException 错误码固定为 {@code WEAK_PASSWORD}，HTTP 400
     */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "密码至少 " + MIN_LENGTH + " 位");
        }
        if (!HAS_LETTER.matcher(rawPassword).find() || !HAS_DIGIT.matcher(rawPassword).find()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "密码必须同时包含字母和数字");
        }
    }
}
