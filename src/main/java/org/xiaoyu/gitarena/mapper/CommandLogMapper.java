package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.CommandLogEntity;

/** 命令审计日志 Mapper。 */
@Mapper
public interface CommandLogMapper extends BaseMapper<CommandLogEntity> {
}
