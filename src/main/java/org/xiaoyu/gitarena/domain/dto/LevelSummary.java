package org.xiaoyu.gitarena.domain.dto;

/** 关卡列表项（GET /api/levels）。 */
public record LevelSummary(
        String slug,
        String title,
        String category,
        int difficulty,
        String mode,
        int orderIndex
) {}
