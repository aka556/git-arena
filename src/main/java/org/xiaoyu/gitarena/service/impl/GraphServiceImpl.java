package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.GraphMapper;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.service.GraphService;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class GraphServiceImpl implements GraphService {

    private final GraphMapper graphMapper;

    @Override
    public GitGraph readGraph(SandboxRepo sandbox) {
        return graphMapper.map(sandbox);
    }

    @Override
    public GitGraph readOriginGraph(Path bareDir) {
        return graphMapper.mapBareOrigin(bareDir);
    }
}
