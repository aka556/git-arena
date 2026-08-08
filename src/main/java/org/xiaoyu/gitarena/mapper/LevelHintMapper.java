package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.LevelHintEntity;

/**
 * 分级提示 Mapper（database.md §5.4，落库到 level_hints 表）。
 */
@Mapper
public interface LevelHintMapper extends BaseMapper<LevelHintEntity> {
}