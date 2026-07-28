package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.SessionResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.service.GraphService;
import org.xiaoyu.gitarena.service.SandboxService;

@Service
@RequiredArgsConstructor
public class SandboxServiceImpl implements SandboxService {

    private final SandboxManager sandboxManager;
    private final GraphService graphService;

    @Override
    public SessionResponse create() {
        SandboxRepo repo = sandboxManager.create();
        return new SessionResponse(repo.sessionId(), graphService.readGraph(repo));
    }

    @Override
    public SessionResponse reset(String sessionId) {
        SandboxRepo repo = sandboxManager.reset(sessionId);
        return new SessionResponse(repo.sessionId(), graphService.readGraph(repo));
    }

    @Override
    public GitGraph graph(String sessionId) {
        return graphService.readGraph(sandboxManager.require(sessionId));
    }
}
