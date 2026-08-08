package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.UserHintUsageEntity;

/** 用户提示使用记录 Mapper。 */
@Mapper
public interface UserHintUsageMapper extends BaseMapper<UserHintUsageEntity> {
}
