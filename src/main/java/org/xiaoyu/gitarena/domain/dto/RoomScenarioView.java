package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

/** 房间场景关卡视图：collab 关卡的目标说明与目标图（§6.3 对照展示），供房间内渲染与校验入口。 */
public record RoomScenarioView(
        String slug,
        String title,
        String description,
        int difficulty,
        GitGraph goalGraph
) {}
