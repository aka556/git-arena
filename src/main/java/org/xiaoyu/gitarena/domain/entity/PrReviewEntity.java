package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** PR 评审（database.md §4.4，落库到 pr_reviews 表）。一位评审者可多次提交，以最新一条为准。 */
@Data
@TableName("pr_reviews")
public class PrReviewEntity {

    public static final String STATE_APPROVED = "approved";
    public static final String STATE_CHANGES_REQUESTED = "changes_requested";
    public static final String STATE_COMMENTED = "commented";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pullRequestId;
    private Long reviewerMemberId;
    /** approved / changes_requested / commented */
    private String state;
    private String body;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime。
    private OffsetDateTime submittedAt;
}
