package com.collabhub.identity.internal.service;

import com.collabhub.identity.api.RegisterRequest;
import com.collabhub.identity.api.UserView;
import com.collabhub.identity.internal.domain.PasswordPolicy;
import com.collabhub.identity.internal.domain.UserStatus;
import com.collabhub.identity.internal.repository.UserAccountRepository;
import com.collabhub.platform.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * 用户注册。
 *
 * <p>流程：校验密码规则 → 规范化邮箱 → 哈希密码 → 写账号 → 写凭证。
 * 整个过程在一个事务里：只要凭证没写成功，账号也会回滚，不会留下"有账号没密码"的脏数据。
 */
@Service
public class RegistrationService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = requireNonNull(repository);
        this.passwordEncoder = requireNonNull(passwordEncoder);
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        PasswordPolicy.validate(request.password());

        // 邮箱统一小写存储，避免 "Alice@x.com" 和 "alice@x.com" 被当成两个账号。
        // 数据库的 collation 也是大小写不敏感的，两者互为保险。
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String displayName = request.displayName().trim();
        Instant now = Instant.now();

        // 先哈希再入库：明文密码在这行之后就不存在于任何变量里（除了 request 本身，
        // 它随请求结束被回收）。日志里也绝不能打印明文。
        String passwordHash = passwordEncoder.encode(request.password());

        long userId;
        try {
            userId = repository.insertAccount(email, displayName, UserStatus.PENDING, now);
        } catch (DuplicateKeyException ex) {
            // 靠唯一索引发现冲突，而不是"先 SELECT 再 INSERT"——后者在并发下会漏。
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                    "该邮箱已被注册");
        }

        repository.insertPasswordCredential(userId, passwordHash, now);

        return new UserView(userId, email, displayName, UserStatus.PENDING.name(), now);
    }
}
