package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.RoomMemberEntity;

/**
 * 房间成员 Mapper（database.md §4.2，落库到 room_members 表）。
 */
@Mapper
public interface RoomMemberMapper extends BaseMapper<RoomMemberEntity> {
}