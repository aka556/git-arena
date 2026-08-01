package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.LevelDetail;
import org.xiaoyu.gitarena.domain.dto.LevelSummary;
import org.xiaoyu.gitarena.domain.dto.StartLevelResponse;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.git.LevelBuilder;
import org.xiaoyu.gitarena.git.RepoInspector;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.service.GoalMatcher;
import org.xiaoyu.gitarena.service.GraphService;
import org.xiaoyu.gitarena.service.LevelCatalog;
import org.xiaoyu.gitarena.service.LevelService;
import org.xiaoyu.gitarena.service.MatchResult;
import org.xiaoyu.gitarena.service.SpecGraphConverter;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LevelServiceImpl implements LevelService {

    private final LevelCatalog catalog;
    private final LevelBuilder levelBuilder;
    private final SandboxManager sandboxManager;
    private final GraphService graphService;
    private final SpecGraphConverter converter;
    private final GoalMatcher goalMatcher;
    private final RepoInspector repoInspector;

    @Override
    public List<LevelSummary> list() {
        List<LevelSummary> result = new ArrayList<>();
        for (LevelFile level : catalog.list()) {
            LevelFile.Meta m = level.meta();
            result.add(new LevelSummary(
                    m.slug(), m.title(), m.category(), m.difficulty(), m.mode(),
                    m.orderIndex() == null ? 0 : m.orderIndex()));
        }
        return result;
    }

    @Override
    public LevelDetail detail(String slug) {
        LevelFile level = catalog.get(slug);
        LevelFile.Meta m = level.meta();
        List<LevelDetail.HintView> hints = new ArrayList<>();
        if (level.hints() != null) {
            for (LevelFile.Hint h : level.hints()) {
                hints.add(new LevelDetail.HintView(
                        h.tier() == null ? 1 : h.tier(),
                        h.body(),
                        h.costPoints() == null ? 0 : h.costPoints()));
            }
        }
        return new LevelDetail(
                m.slug(), m.title(), m.description(), m.category(), m.difficulty(), m.mode(),
                converter.fromInitial(level.initial()),
                converter.fromGoal(level.goal().graph()),
                hints);
    }

    @Override
    public StartLevelResponse start(String slug) {
        LevelFile level = catalog.get(slug);
        SandboxRepo sandbox = sandboxManager.create();
        levelBuilder.build(level.initial(), sandbox);
        GitGraph graph = graphService.readGraph(sandbox);
        GitGraph goalGraph = converter.fromGoal(level.goal().graph());
        return new StartLevelResponse(sandbox.sessionId(), slug, graph, goalGraph);
    }

    @Override
    public ValidateResponse validate(String sessionId, String slug) {
        SandboxRepo sandbox = sandboxManager.require(sessionId);
        LevelFile level = catalog.get(slug);
        GitGraph snapshot = graphService.readGraph(sandbox);
        MatchResult result = goalMatcher.match(
                snapshot,
                level.goal(),
                path -> repoInspector.fileAtHead(sandbox, path));
        return new ValidateResponse(result.passed(), result.reasons());
    }
}
