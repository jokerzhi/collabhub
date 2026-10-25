package com.collabhub.identity.internal.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * access token 的签发与校验。
 *
 * <p><b>算法选 HS256（对称）而不是 RS256（非对称）的原因：</b>
 * RS256 的好处是「验证方只需要公钥」，适合多个服务共享验证能力。
 * 但本项目只有一个部署单元，密钥不出进程，对称签名更简单也更快（RS256 签名约慢 100 倍）。
 * 将来若拆出独立服务、需要让别的服务只验签不签发，再换 RS256 —— 那时候用 JWKS 分发公钥。
 *
 * <p><b>access token 里刻意只放最少的信息</b>（userId + jti + 时间），不放邮箱、昵称、角色。
 * 原因：JWT 是 base64 编码不是加密，任何人拿到都能解开看。而且它不能撤回，
 * 放进去的信息会"过期前一直为真"。需要用户资料时应该调接口查，不要塞进令牌。
 *
 * <p>不负责：refresh token（那是不透明随机串，见 {@link RefreshTokenFactory}）、
 * 会话存储、登录逻辑。
 */
@Component
public class JwtTokenIssuer {

    /** HS256 要求密钥至少 256 位；短了 JJWT 会抛 WeakKeyException。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTtl;

    public JwtTokenIssuer(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.issuer:collabhub}") String issuer,
            @Value("${app.security.jwt.access-ttl:PT15M}") Duration accessTtl) {

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 失败要快速且明确：配置错密钥长度这种事必须在启动时暴露，而不是等第一个用户登录
            throw new IllegalStateException(
                    "app.security.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节（当前 "
                            + keyBytes.length + " 字节）。HS256 的密钥太短等于没有签名。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = issuer;
        this.accessTtl = accessTtl;
    }

    /**
     * 签发一个 access token。
     *
     * @param userId 用户 ID，会放进 {@code sub} 声明
     * @param now    签发时刻（由调用方传入而不是内部取 now，方便测试固定时间）
     * @return 令牌字符串与过期时刻
     */
    public IssuedAccessToken issue(long userId, Instant now) {
        Instant expiresAt = now.plus(accessTtl);

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(Long.toString(userId))
                .id(UUID.randomUUID().toString())   // jti：将来做"单个令牌吊销"时要用
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("typ", "access")             // 防止把 refresh 当 access 用（反之亦然）
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedAccessToken(token, expiresAt);
    }

    /** access token 的有效期，Controller 要把它换算成秒告诉前端。 */
    public Duration accessTtl() {
        return accessTtl;
    }

    /**
     * 校验并解析令牌。
     *
     * <p>校验内容：签名、是否过期、issuer 是否为本系统。
     * 校验失败会抛 {@link io.jsonwebtoken.JwtException} 的子类，调用方统一转成 401。
     *
     * @param token 待校验的令牌
     * @return 用户 ID
     * @throws io.jsonwebtoken.JwtException 签名不对、已过期、issuer 不匹配
     */
    public long verifyAndGetUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.parseLong(subject);
    }

    /** 签发结果：令牌 + 过期时刻（避免调用方自己再算一次，算错了就对不上）。 */
    public record IssuedAccessToken(String token, Instant expiresAt) {
    }
}
