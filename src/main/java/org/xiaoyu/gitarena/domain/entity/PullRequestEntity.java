package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Pull Request（database.md §4.3，落库到 pull_requests 表）。<b>只存分支名不存 commit hash</b>——
 * 分支指向随推拉变化，PR 的"最新提交"实时从仓库读（§1 存储边界）。
 */
@Data
@TableName("pull_requests")
public class PullRequestEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomId;
    /** 房内自增编号（#1、#2），唯一约束 (room_id, number)。 */
    private Integer number;
    private String title;
    private String description;
    private Long authorMemberId;
    private String sourceBranch;
    private String targetBranch;
    /** open / merged / closed */
    private String status;
    /** unknown / clean / conflict */
    private String mergeable;
    private Long mergedByMemberId;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime。
    private OffsetDateTime mergedAt;
    private OffsetDateTime closedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
