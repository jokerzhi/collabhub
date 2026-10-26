package com.collabhub.identity.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.collabhub.identity.internal.entity.UserCredentialEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code user_credential} 的数据访问接口。
 *
 * <p>目前所有操作都能用 {@link BaseMapper} 自带的 CRUD 完成，所以这里没有任何自定义方法。
 * 这正好体现了换 MyBatis-Plus 的收益：一个空接口就等于过去 40 行手写 SQL + 映射。
 *
 * <p>唯一约束 {@code uk_user_credential_user_type (user_id, credential_type)} 在数据库层
 * 保证"一个用户每种登录方式只有一条"，所以这里不需要先查后插。
 */
@Mapper
public interface UserCredentialMapper extends BaseMapper<UserCredentialEntity> {
}
