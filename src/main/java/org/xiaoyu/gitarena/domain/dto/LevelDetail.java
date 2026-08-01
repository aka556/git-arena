package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

import java.util.List;

/**
 * 关卡详情（GET /api/levels/{slug}）。initialGraph / goalGraph 均为 GitGraph 形状（seq 作 id），
 * 供前端用同一 GitGraphView 渲染"初始预览"与"目标图"（§6.3 当前图 vs 目标图对照）。
 */
public record LevelDetail(
        String slug,
        String title,
        String description,
        String category,
        int difficulty,
        String mode,
        GitGraph initialGraph,
        GitGraph goalGraph,
        List<HintView> hints
) {
    public record HintView(int tier, String body, int costPoints) {}
}
