package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 用户成就解锁记录（database.md §5.2）。 */
@Data
@TableName("user_achievements")
public class UserAchievementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long achievementId;
    private OffsetDateTime unlockedAt;
    private String context;
}
