package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.User;

/**
 * 用户 Mapper（MyBatis-Plus BaseMapper 提供 CRUD；条件查询用 LambdaQueryWrapper）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
