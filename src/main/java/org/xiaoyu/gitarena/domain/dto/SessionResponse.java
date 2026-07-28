package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 会话（沙盒）创建/重置的响应：会话标识 + 初始图快照。
 * <p>M1 阶段 sessionId 仅为内存沙盒登记的键，不落库（沙盒台账属 P1 用户体系）。
 */
public record SessionResponse(String sessionId, GitGraph graph) {}
