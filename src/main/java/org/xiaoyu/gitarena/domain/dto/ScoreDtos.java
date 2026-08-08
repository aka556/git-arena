package org.xiaoyu.gitarena.domain.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 积分与排行榜接口 DTO。 */
public final class ScoreDtos {

    /** 全时段总榜：累计 {@code users.total_points}。 */
    public static final String PERIOD_ALL = "all";
    /** 滚动 7 日榜，读 {@code leaderboard_weekly} 物化视图。 */
    public static final String PERIOD_WEEKLY = "weekly";
    /** 滚动 30 日榜，读 {@code leaderboard_monthly} 物化视图。 */
    public static final String PERIOD_MONTHLY = "monthly";

    /** 口径：累计总分。 */
    public static final String METRIC_TOTAL = "total";
    /** 口径：窗口内新增积分（database.md §5.7 要求与总榜分别标注）。 */
    public static final String METRIC_WINDOW = "window";

    private ScoreDtos() {
    }

    public record Me(Long userId, int totalPoints) {
    }

    /**
     * 一张榜单。
     *
     * @param period      all / weekly / monthly
     * @param metric      total / window——口径不同不可混排，前端据此改副标题
     * @param refreshedAt 时段榜物化视图的最近刷新时刻（最多滞后一个刷新周期）；总榜实时读，恒为 null
     */
    public record Board(
            String period,
            String metric,
            OffsetDateTime refreshedAt,
            List<LeaderboardEntry> entries
    ) {
    }

    /**
     * @param points 口径由所属 {@link Board#metric()} 决定：总榜为累计分，时段榜为窗口内新增分
     */
    public record LeaderboardEntry(
            int rank,
            Long userId,
            String username,
            String displayName,
            String avatarColor,
            int points
    ) {
    }
}
