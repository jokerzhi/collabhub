package com.collabhub.identity.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.collabhub.identity.internal.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code user_account} 的数据访问接口。
 *
 * <p>继承 {@link BaseMapper} 就免费得到 insert / selectById / updateById / deleteById 等方法，
 * 这是换 MyBatis-Plus 最直接的收益 —— 不用再手写 INSERT 和 {@code GeneratedKeyHolder}
 * 那套拿自增主键的样板代码。
 *
 * <p>只写三个 MyBatis 做不到「自动」的语句：
 * <ul>
 *   <li>联表查密码哈希（BaseMapper 只操作单表）</li>
 *   <li>原子自增失败次数（读-改-写会有并发丢失）</li>
 *   <li>清零 + 把 locked_until 置 NULL（updateById 默认忽略 null 字段，改不动）</li>
 * </ul>
 *
 * <p>标 {@code @Mapper} 就不需要 {@code @MapperScan}：MyBatis 的自动配置会扫描
 * 启动类所在包及子包里的 {@code @Mapper} 接口。在模块化单体里这比全局扫描更安全 ——
 * 每个模块自己声明自己的 Mapper，不会互相串。
 *
 * <p>不负责：业务规则、事务边界、Instant ↔ LocalDateTime 的转换（那些在 Repository）。
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    /**
     * 登录专用：一次查出账号状态 + 锁定信息 + 密码哈希。
     *
     * <p>为什么不用 BaseMapper 的两条查询拼起来：登录是高频路径，少一次往返就少一次
     * 网络抖动；而且这些字段必须来自同一时刻的快照。
     *
     * <p>注意 {@code LEFT JOIN}：没有密码凭证的账号（只绑定 OAuth）也要能查出来，
     * 只是 {@code password_hash} 为 null，由上层判断"不能用密码登录"。
     *
     * @param email 已转小写的邮箱
     * @return 账号实体（含{@code passwordHash}），不存在或已软删除时返回 null
     */
    @Select("""
            SELECT a.id, a.email, a.display_name, a.status,
                   a.failed_login_count, a.locked_until, a.created_at,
                   c.password_hash
            FROM user_account a
            LEFT JOIN user_credential c
                   ON c.user_id = a.id AND c.credential_type = 'PASSWORD'
            WHERE a.email = #{email} AND a.deleted_at IS NULL
            """)
    UserAccountEntity selectAuthByEmail(@Param("email") String email);

    /**
     * 失败次数原子 +1。
     *
     * <p>必须在 SQL 里做 {@code count = count + 1}，不能"查出来加一再写回"：
     * 后者在并发下会丢计数，攻击者用并发请求就能绕过 5 次锁定。
     *
     * @return 受影响行数（正常是 1）
     */
    @Update("""
            UPDATE user_account
            SET failed_login_count = failed_login_count + 1, updated_at = #{now}
            WHERE id = #{id}
            """)
    int incrementFailedLogin(@Param("id") long id, @Param("now") LocalDateTime now);

    /**
     * 登录成功后的重置。
     *
     * <p>为什么要单独写 SQL 而不是 {@code updateById}：MyBatis-Plus 默认的字段策略是
     * "非 null 才更新"，而这里恰恰要把 {@code locked_until} 改回 <b>NULL</b>。
     * updateById 传 null 等于"这个字段不动"，锁定就解不掉了。
     *
     * <p>三个字段一起改是必须的：只清零不解除锁定，用户仍被挡在门外；
     * 只解除锁定不清零，下次再错一次立刻又被锁。
     */
    @Update("""
            UPDATE user_account
            SET failed_login_count = 0,
                locked_until = NULL,
                last_login_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
            """)
    int markLoginSucceeded(@Param("id") long id, @Param("now") LocalDateTime now);
}
