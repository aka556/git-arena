package org.xiaoyu.gitarena.domain.dto;

import java.time.OffsetDateTime;

/** 成就接口 DTO。 */
public final class AchievementDtos {

    private AchievementDtos() {
    }

    public record View(
            Long id,
            String code,
            String name,
            String description,
            String icon,
            int points,
            String category,
            boolean unlocked,
            OffsetDateTime unlockedAt
    ) {
    }
}
