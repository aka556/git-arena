package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 加入/建立房间的响应：房间快照 + 本成员标识 + 本成员克隆沙盒的会话与初始图。
 * 后续该成员的命令都用此 sessionId 走既有命令链路（§3）。
 */
public record RoomJoinResponse(
        RoomView room,
        String memberId,
        String sessionId,
        GitGraph graph
) {}
