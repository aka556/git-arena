package org.xiaoyu.gitarena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.git.GitExecutor;
import org.xiaoyu.gitarena.git.GraphMapper;
import org.xiaoyu.gitarena.git.LevelBuilder;
import org.xiaoyu.gitarena.git.RepoInspector;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方关卡自证闭环（docs/level-spec.md §7 质量门）。对 classpath levels/*.level.json 逐个执行：
 * <ol>
 *   <li>加载 + 语义校验（LevelCatalog 的 fail-closed 链路）；</li>
 *   <li><b>零步不通关</b>：构建 initial 后直接跑 goal 校验必须失败（防"一进门就赢"的配置错误）；</li>
 *   <li><b>参考解通关</b>：重放 solution 步骤后 goal 校验必须通过（initial/goal/solution 三者互洽）。</li>
 * </ol>
 * 全程真实 JGit + 临时目录，不启 Spring、不连 DB。官方关卡即 fixture（CLAUDE.md §9）。
 */
class LevelSelfCheckTest {

    @TempDir
    Path tmp;

    private LevelCatalog catalog;
    private LevelBuilder builder;
    private GraphMapper mapper;
    private GoalMatcher matcher;
    private SolutionReplayer replayer;
    private RepoInspector inspector;

    @BeforeEach
    void setUp() {
        PathGuard pathGuard = new PathGuard();
        catalog = new LevelCatalog(new ObjectMapper(), new LevelValidator());
        catalog.load();
        builder = new LevelBuilder(pathGuard);
        mapper = new GraphMapper();
        matcher = new GoalMatcher();
        replayer = new SolutionReplayer(new CommandParser(), new GitExecutor(pathGuard), pathGuard);
        inspector = new RepoInspector();
    }

    @Test
    void catalogLoadsOfficialLevels() {
        assertThat(catalog.list()).isNotEmpty();
        assertThat(catalog.list()).extracting(l -> l.meta().slug())
                .contains("first-commit", "merge-feature", "resolve-conflict");
    }

    @Test
    void everyLevel_zeroStepMustFail_solutionMustPass() throws IOException {
        for (LevelFile level : catalog.list()) {
            String slug = level.meta().slug();
            SandboxRepo sandbox = new SandboxRepo(slug, Files.createDirectories(tmp.resolve(slug)));
            builder.build(level.initial(), sandbox);

            // 质量门 1：零步不通关
            MatchResult zeroStep = validate(sandbox, level);
            assertThat(zeroStep.passed())
                    .as("关卡 [%s] 初始状态不应直接通关", slug)
                    .isFalse();

            // 质量门 2：参考解通关
            assertThat(level.solution())
                    .as("官方关卡 [%s] 必须提供参考解（自证闭环需要）", slug)
                    .isNotNull();
            replayer.replay(sandbox, level.solution());
            MatchResult solved = validate(sandbox, level);
            assertThat(solved.passed())
                    .as("关卡 [%s] 重放参考解后应通关，差异：%s", slug, solved.reasons())
                    .isTrue();
        }
    }

    private MatchResult validate(SandboxRepo sandbox, LevelFile level) {
        GitGraph snapshot = mapper.map(sandbox);
        return matcher.match(snapshot, level.goal(), path -> inspector.fileAtHead(sandbox, path));
    }
}
