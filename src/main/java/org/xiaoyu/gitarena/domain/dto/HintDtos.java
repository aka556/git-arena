package org.xiaoyu.gitarena.domain.dto;

/** 提示接口 DTO。 */
public final class HintDtos {

    private HintDtos() {
    }

    public record UseResponse(
            Long hintId,
            int tier,
            String body,
            int costPoints,
            int pointsCharged,
            int hintsUsed,
            int totalPoints
    ) {
    }
}
