package com.collabhub.identity.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.collabhub.identity.internal.entity.UserSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code user_session} 的数据访问接口。
 *
 * <p>同样只用 {@link BaseMapper} 的 CRUD：写入用 insert，按哈希查用条件构造器
 * （见 {@code UserSessionRepository#findIdByHash}）。
 *
 * <p>后续做"设备管理"（FR-AUTH-009）时会加两个方法：
 * {@code selectActiveByUserId}（列出未吊销未过期的会话）、
 * {@code revokeAllByUserId}（改密后批量踢下线）。
 */
@Mapper
public interface UserSessionMapper extends BaseMapper<UserSessionEntity> {
}
