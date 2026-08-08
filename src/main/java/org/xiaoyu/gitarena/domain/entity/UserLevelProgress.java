package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 关卡进度（database.md §3.4，落库到 user_level_progress 表）。唯一约束 (user_id, level_id)——每人每关一行。
 */
@Data
@TableName("user_level_progress")
public class UserLevelProgress {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long levelId;
    /** locked / unlocked / in_progress / completed */
    private String status;
    private Integer attempts;
    private Integer bestCommandCount;
    private Integer starRating;
    private Integer hintsUsed;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime，pgjdbc 拒绝把 timestamptz 读进 LocalDateTime。
    private OffsetDateTime firstCompletedAt;
    private OffsetDateTime lastAttemptAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
