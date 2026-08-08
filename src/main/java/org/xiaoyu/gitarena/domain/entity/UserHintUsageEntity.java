package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 用户提示使用记录（database.md §5.5）。 */
@Data
@TableName("user_hint_usage")
public class UserHintUsageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long levelId;
    private Long hintId;
    private OffsetDateTime usedAt;
}
