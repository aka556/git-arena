package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.PrCommentEntity;

/** PR 评论 Mapper（database.md §4.5）。 */
@Mapper
public interface PrCommentMapper extends BaseMapper<PrCommentEntity> {
}
