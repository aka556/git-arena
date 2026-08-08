package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.RoomEntity;

/**
 * 协作房间 Mapper（database.md §4.1，落库到 rooms 表）。
 */
@Mapper
public interface RoomMapper extends BaseMapper<RoomEntity> {
}