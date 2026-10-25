package com.collabhub.identity.internal.domain;

import com.collabhub.platform.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 密码规则的纯单元测试。
 *
 * <p>这类测试的价值在于<b>快</b>和<b>准</b>：不启动 Spring、不连数据库，
 * 一个类跑完不到一秒，所以可以放心地把每个边界都补上。
 *
 * <p>每个用例只验证一条规则，失败时一眼能看出是哪条规则破了 ——
 * 不要写"输入一堆、断言一堆"的测试，那样失败了还得再查一遍。
 */
class PasswordPolicyTest {

    @Test
    void acceptsEightCharactersWithLetterAndDigit() {
        assertThatCode(() -> PasswordPolicy.validate("abc12345"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsLongerPassword() {
        assertThatCode(() -> PasswordPolicy.validate("my-long-password-2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordShorterThanEight() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("abc1234"));   // 7 位

        assertThat(ex.getCode()).isEqualTo("WEAK_PASSWORD");
        assertThat(ex.getMessage()).contains("至少 8 位");
    }

    @Test
    void rejectsDigitsOnlyEvenIfLongEnough() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("12345678"));

        assertThat(ex.getCode()).isEqualTo("WEAK_PASSWORD");
        assertThat(ex.getMessage()).contains("字母和数字");
    }

    @Test
    void rejectsLettersOnlyEvenIfLongEnough() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("abcdefgh"));

        assertThat(ex.getCode()).isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void rejectsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate(null));

        assertThat(ex.getCode()).isEqualTo("WEAK_PASSWORD");
    }
}
