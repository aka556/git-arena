package org.xiaoyu.gitarena.domain.dto;

/** 积分与排行榜接口 DTO。 */
public final class ScoreDtos {

    private ScoreDtos() {
    }

    public record Me(Long userId, int totalPoints) {
    }

    public record LeaderboardEntry(
            int rank,
            Long userId,
            String username,
            String displayName,
            String avatarColor,
            int totalPoints
    ) {
    }
}
