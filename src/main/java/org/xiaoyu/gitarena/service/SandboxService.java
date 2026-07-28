package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.SessionResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 沙盒会话生命周期（P0 沙盒仓库管理）：创建、重置、读图。
 */
public interface SandboxService {

    SessionResponse create();

    SessionResponse reset(String sessionId);

    GitGraph graph(String sessionId);
}
