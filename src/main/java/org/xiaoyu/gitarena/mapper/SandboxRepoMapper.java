package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.SandboxRepoEntity;

/**
 * 沙盒仓库台账 Mapper（database.md §3.2，落库到 sandbox_repos 表）。
 */
@Mapper
public interface SandboxRepoMapper extends BaseMapper<SandboxRepoEntity> {
}