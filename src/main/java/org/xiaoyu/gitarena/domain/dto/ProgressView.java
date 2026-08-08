package org.xiaoyu.gitarena.domain.dto;

import java.time.OffsetDateTime;

/**
 * 关卡进度视图（GET /api/progress，database.md §3.4）。以稳定的关卡 slug 对外，
 * 而非内部 level_id。{@code firstCompletedAt} 由 Jackson 序列化为 ISO 字符串。
 */
public record ProgressView(
        String slug,
        String status,
        int attempts,
        int starRating,
        Integer bestCommandCount,
        OffsetDateTime firstCompletedAt
) {}
