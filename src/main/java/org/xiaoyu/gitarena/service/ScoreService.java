package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.ScoreDtos;

/** 积分流水与排行榜服务。 */
public interface ScoreService {

    /** 写入一条不可变流水，并原子更新用户积分缓存。 */
    void award(Long userId, String sourceType, String sourceRef, int points);

    ScoreDtos.Me me(Long userId);

    /**
     * 按口径取排行榜（database.md §5.7）。
     *
     * @param period {@code all} 读 {@code users.total_points} 累计值；{@code weekly}/{@code monthly}
     *               读物化视图的窗口内新增积分——两者口径不同，响应的 {@code metric} 供前端分别标注
     */
    ScoreDtos.Board leaderboard(String period);

    /**
     * 刷新周/月榜物化视图（每 5 分钟，由 {@code MaintenanceScheduler} 触发）。
     * 视图定义里的 {@code now()} 在刷新时重新求值，故窗口随刷新滚动。
     */
    void refreshPeriodLeaderboards();
}
