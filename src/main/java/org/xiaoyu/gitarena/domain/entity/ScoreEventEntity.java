package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 积分流水（database.md §5.3）。流水只增不改，users.total_points 是聚合缓存。 */
@Data
@TableName("score_events")
public class ScoreEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String sourceType;
    private String sourceRef;
    private Integer points;
    private OffsetDateTime createdAt;
}
