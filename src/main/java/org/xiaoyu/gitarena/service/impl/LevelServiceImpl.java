package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.LevelDetail;
import org.xiaoyu.gitarena.domain.dto.LevelSummary;
import org.xiaoyu.gitarena.domain.dto.StartLevelResponse;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;
import org.xiaoyu.gitarena.domain.entity.LevelHintEntity;
import org.xiaoyu.gitarena.domain.entity.UserHintUsageEntity;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.git.LevelBuilder;
import org.xiaoyu.gitarena.git.RepoInspector;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.mapper.LevelHintMapper;
import org.xiaoyu.gitarena.service.GoalMatcher;
import org.xiaoyu.gitarena.service.GraphService;
import org.xiaoyu.gitarena.service.LevelCatalog;
import org.xiaoyu.gitarena.service.LevelRegistry;
import org.xiaoyu.gitarena.service.LevelService;
import org.xiaoyu.gitarena.service.MatchResult;
import org.xiaoyu.gitarena.service.ProgressService;
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
    private final ProgressService progressService;
    private final LevelHintMapper levelHintMapper;
    private final org.xiaoyu.gitarena.mapper.UserHintUsageMapper userHintUsageMapper;
    private final LevelRegistry levelRegistry;

    @Override
    public List<LevelSummary> list(Long userId) {
        var progressBySlug = progressService.myProgress(userId).stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.slug(), p -> p));
        List<LevelSummary> result = new ArrayList<>();
        for (LevelFile level : catalog.list()) {
            LevelFile.Meta m = level.meta();
            var progress = progressBySlug.get(m.slug());
            String status = progress == null ? "unlocked" : progress.status();
            int attempts = progress == null ? 0 : progress.attempts();
            result.add(new LevelSummary(
                    m.slug(), m.title(), m.category(), m.difficulty(), m.mode(),
                    status, attempts,
                    m.orderIndex() == null ? 0 : m.orderIndex()));
        }
        return result;
    }

    @Override
    public LevelDetail detail(String slug, Long userId) {
        LevelFile level = catalog.get(slug);
        LevelFile.Meta m = level.meta();
        var progress = progressService.myProgress(userId).stream()
                .filter(p -> p.slug().equals(slug))
                .findFirst();
        String status = progress.map(p -> p.status()).orElse("unlocked");
        int attempts = progress.map(p -> p.attempts()).orElse(0);
        List<LevelDetail.HintView> hints = hintsOf(slug, userId);
        return new LevelDetail(
                m.slug(), m.title(), m.description(), m.category(), m.difficulty(), m.mode(),
                status, attempts,
                converter.fromInitial(level.initial()),
                converter.fromGoal(level.goal().graph()),
                hints);
    }

    /**
     * 提示以数据库为真相（LevelSeeder 已把关卡文件 hints 拆行写入 level_hints，database.md §5.4）。
     * 库中无行（如旧库未 seed）时回退到关卡文件，保证详情页不空。
     */
    private List<LevelDetail.HintView> hintsOf(String slug, Long userId) {
        Long levelId = levelRegistry.idOf(slug);
        if (levelId != null) {
            List<LevelHintEntity> rows = levelHintMapper.selectList(new LambdaQueryWrapper<LevelHintEntity>()
                    .eq(LevelHintEntity::getLevelId, levelId)
                    .orderByAsc(LevelHintEntity::getOrderIndex));
            if (!rows.isEmpty()) {
                java.util.Set<Long> usedIds = userId == null ? java.util.Set.of() :
                        userHintUsageMapper.selectList(new LambdaQueryWrapper<UserHintUsageEntity>()
                                        .eq(UserHintUsageEntity::getUserId, userId)
                                        .eq(UserHintUsageEntity::getLevelId, levelId))
                                .stream().map(UserHintUsageEntity::getHintId).collect(java.util.stream.Collectors.toSet());
                List<LevelDetail.HintView> out = new ArrayList<>(rows.size());
                for (LevelHintEntity row : rows) {
                    out.add(new LevelDetail.HintView(
                            row.getId(),
                            row.getTier() == null ? 1 : row.getTier(),
                            row.getBody(),
                            row.getCostPoints() == null ? 0 : row.getCostPoints(),
                            usedIds.contains(row.getId())));
                }
                return out;
            }
        }
        // 回退：classpath 关卡文件（与 seed 内容同源，仅兜底旧库）
        List<LevelDetail.HintView> fallback = new ArrayList<>();
        LevelFile level = catalog.get(slug);
        if (level.hints() != null) {
            for (LevelFile.Hint h : level.hints()) {
                fallback.add(new LevelDetail.HintView(
                        null,
                        h.tier() == null ? 1 : h.tier(),
                        h.body(),
                        h.costPoints() == null ? 0 : h.costPoints(),
                        false));
            }
        }
        return fallback;
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
