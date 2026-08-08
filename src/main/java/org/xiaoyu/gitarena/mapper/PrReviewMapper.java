package org.xiaoyu.gitarena.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xiaoyu.gitarena.domain.entity.PrReviewEntity;

/** PR 评审 Mapper（database.md §4.4）。 */
@Mapper
public interface PrReviewMapper extends BaseMapper<PrReviewEntity> {
}
