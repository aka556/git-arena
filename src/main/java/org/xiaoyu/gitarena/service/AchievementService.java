package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.AchievementDtos;

import java.util.List;
import java.util.Map;

/** 成就定义、解锁及事件触发服务。 */
public interface AchievementService {

    /** 幂等解锁；首次解锁返回 true，并发放定义中的奖励积分。 */
    boolean unlock(Long userId, String code, Map<String, Object> context);

    void onCommit(Long userId);

    void onLevelCompleted(Long userId, String slug);

    void onPullRequestMerged(Long userId, String sourceRef);

    List<AchievementDtos.View> mine(Long userId);
}
