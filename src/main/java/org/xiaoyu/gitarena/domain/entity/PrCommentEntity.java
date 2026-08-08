package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * PR 评论（database.md §4.5，落库到 pr_comments 表）。
 *
 * <p><b>稳健 diff 锚点</b>：行级评论不靠裸「文件+行号」（源分支推一次提交就错位），而是锚在
 * <i>写评论那一刻</i>的提交快照上——{@code anchorCommitSha} + {@code originalLine} + {@code diffHunk}
 * 三者是<b>不可变事实</b>，永不改写；{@code currentLine}/{@code isOutdated} 才是后端在每次
 * 源分支更新后用 {@code diffHunk} 上下文重算出来的<b>派生值</b>。
 *
 * <p>{@code anchorCommitSha} 是 §1 存储边界的唯一例外（不可变历史标记）：它记录"当时针对哪个版本"，
 * 永不当"现在是什么"读，也不作外键。
 */
@Data
@TableName("pr_comments")
public class PrCommentEntity {

    public static final String KIND_GENERAL = "general";
    public static final String KIND_INLINE = "inline";

    public static final String SIDE_OLD = "old";
    public static final String SIDE_NEW = "new";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pullRequestId;
    /** 归属某次评审；null 表示独立评论。 */
    private Long reviewId;
    private Long authorMemberId;
    private String body;
    /** general / inline */
    private String commentKind;

    // ---- 以下仅 inline 有值：前三个不可变，后两个由后端重算 ----
    private String anchorCommitSha;
    private String filePath;
    /** old / new */
    private String diffSide;
    private Integer originalLine;
    private String diffHunk;
    /** 针对当前 HEAD 重算出的行号；null = 无法再定位。 */
    private Integer currentLine;
    private Boolean isOutdated;

    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime。
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
