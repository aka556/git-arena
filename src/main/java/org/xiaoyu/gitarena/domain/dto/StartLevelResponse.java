package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 开始关卡响应（POST /api/levels/{slug}/start）：新建沙盒并把 initial 构建进去后的
 * 当前图 + 目标图 + 会话标识。后续命令与校验都用此 sessionId。
 */
public record StartLevelResponse(
        String sessionId,
        String slug,
        GitGraph graph,
        GitGraph goalGraph
) {}
