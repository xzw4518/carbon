package com.carbonbal.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 会话吊销表访问入口（{@code revoked_token}）。 */
@Mapper
public interface RevokedTokenMapper extends BaseMapper<RevokedToken> {
}
