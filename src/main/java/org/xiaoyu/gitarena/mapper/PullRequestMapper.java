package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.PullRequestEntity;

/**
 * Pull Request Mapper（database.md §4.3，落库到 pull_requests 表）。
 */
@Mapper
public interface PullRequestMapper extends BaseMapper<PullRequestEntity> {
}