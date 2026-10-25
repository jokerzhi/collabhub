package com.collabhub.identity.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希器。
 *
 * <p>选 Argon2id 而不是 BCrypt 的原因：Argon2 是 2015 年密码哈希竞赛的胜出者，
 * 抗 GPU/ASIC 暴力破解能力更强，也是 OWASP 现在的首选。
 *
 * <p>参数含义（{@code Argon2PasswordEncoder(saltLength, hashLength, parallelism, memory, iterations)}）：
 * <ul>
 *   <li>saltLength 16 字节：每个密码独立随机盐，防彩虹表</li>
 *   <li>hashLength 32 字节：输出长度</li>
 *   <li>parallelism 1：单线程（本机开发够用，服务器上可提到 2-4）</li>
 *   <li><b>memory 16384 KB = 16 MB</b>：这是 Argon2 的核心，故意占大量内存，
 *       让 GPU 并行暴力破解的成本变得极高</li>
 *   <li><b>iterations 2</b>：时间成本</li>
 * </ul>
 *
 * <p>memory 和 iterations 是安全性与延迟的权衡。16 MB / 2 轮大约 50ms 一次，
 * 登录时用户无感，但攻击者要付出 16 MB × 并发数的内存代价。
 *
 * <p>注意：<b>这个 Bean 要一直保持单例</b>，因为编码串里记录了参数，
 * 验证时用同一套参数；将来调参数也能自动兼容旧密码（编码串自带版本和参数）。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 16 * 1024, 2);
    }
}
