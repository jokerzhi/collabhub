package com.collabhub.identity.internal.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * refresh token 的生成。
 *
 * <p><b>为什么 refresh token 不用 JWT，而用随机串：</b>
 * JWT 的最大缺点是不能撤回——签发出去就只能等它过期。refresh token 寿命长达 7 天，
 * 一旦泄露必须有办法立刻作废（登出、改密、检测到异地重放）。
 * 随机串 + 数据库记录，每次续期查一次库，就能随时吊销。这点延迟完全可以接受，
 * 因为 refresh 一天只用几次。
 *
 * <p><b>为什么库里存哈希而不是原文：</b>
 * 和密码同理。数据库被拖走时，攻击者拿到哈希也换不出有效令牌。
 * 这里用 SHA-256 而不是 Argon2：refresh token 本身是 256 位真随机数，
 * 不存在"弱口令"问题，不需要加盐和慢哈希，SHA-256 足够且更快。
 *
 * <p>不负责：把令牌存进数据库（那是 Repository 的事）、判断是否过期。
 */
@Component
public class RefreshTokenFactory {

    /** 32 字节 = 256 位熵。暴力猜解在这个量级下不可行（2^256 次尝试）。 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成一对（原文, 哈希）。
     *
     * <p>原文只返回这一次给客户端；数据库只存哈希。之后再也无法从哈希还原原文。
     *
     * @return 令牌对
     */
    public RefreshToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe Base64 且去掉 '=' 填充：可以直接放进 JSON，不用转义
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshToken(raw, sha256Hex(raw));
    }

    /**
     * 计算 SHA-256 的十六进制小写串（64 个字符，对应数据库的 {@code CHAR(64)}）。
     *
     * <p>用它把"客户端提交的原文"换算成"库里的哈希"再比对 —— 这样比对的是哈希，
     * 原文永远不会出现在日志、慢查询、堆栈里。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // 每个 Java 实现都必须支持 SHA-256，走到这里说明运行环境被破坏了
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 令牌对。
     *
     * @param raw  原文，只发给客户端一次
     * @param hash SHA-256 十六进制，存数据库
     */
    public record RefreshToken(String raw, String hash) {
    }
}
