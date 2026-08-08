package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.ScoreDtos;

import java.util.List;

/** 积分流水与排行榜服务。 */
public interface ScoreService {

    /** 写入一条不可变流水，并原子更新用户积分缓存。 */
    void award(Long userId, String sourceType, String sourceRef, int points);

    ScoreDtos.Me me(Long userId);

    List<ScoreDtos.LeaderboardEntry> leaderboard();
}
