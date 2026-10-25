package com.collabhub.identity.api;

/**
 * 登录成功后返回的令牌对。
 *
 * <p>为什么是两个令牌而不是一个：
 * <ul>
 *   <li><b>accessToken</b> 是 JWT，自带签名和过期时间，服务端<b>不查库</b>就能验，
 *       所以快。代价是签发后无法立刻作废，因此寿命必须短（15 分钟）。</li>
 *   <li><b>refreshToken</b> 是一串随机数，数据库里存它的哈希。每次续期都要查库，
 *       所以能随时吊销（登出、改密、检测到盗用）。代价是慢一点，但它一天只用几次。</li>
 * </ul>
 * 一个长寿命、不可吊销的 JWT 是常见的安全事故，所以这里坚持双令牌。
 *
 * @param accessToken  访问令牌（JWT），放在 Authorization: Bearer 里
 * @param refreshToken 刷新令牌（不透明随机串），只应发给认证服务器换新令牌
 * @param tokenType    固定 "Bearer"，方便前端直接拼 Authorization 头
 * @param expiresIn    accessToken 有效期（秒）
 * @param user         登录的用户信息（不含任何凭证）
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserView user
) {
}
