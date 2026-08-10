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
import org.xiaoyu.gitarena.git.SandboxShellExecutor;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突关卡的内容断言（docs/level-spec.md §5.4 fileAtHeadNotContains）。
 *
 * <p>回归一个真实误判现场：玩家 merge 冲突后<b>没编辑文件</b>就 add+commit，DAG 结构与目标图完全一致，
 * 但 greeting.txt 仍留着 {@code <<<<<<<} 标记——此时必须判未达成，且原因要能说清是哪条断言。
 */
class ConflictMarkerAssertionTest {

    @TempDir
    Path tmp;

    private LevelCatalog catalog;
    private LevelBuilder builder;
    private GraphMapper mapper;
    private GoalMatcher matcher;
    private RepoInspector inspector;
    private GitExecutor executor;
    private SandboxRepo sandbox;
    private LevelFile level;

    @BeforeEach
    void setUp() throws Exception {
        PathGuard pathGuard = new PathGuard();
        catalog = new LevelCatalog(new ObjectMapper(), new LevelValidator());
        catalog.load();
        builder = new LevelBuilder(pathGuard);
        mapper = new GraphMapper();
        matcher = new GoalMatcher();
        inspector = new RepoInspector();
        executor = new GitExecutor(pathGuard, new SandboxShellExecutor(pathGuard));
        level = catalog.get("resolve-conflict");
        sandbox = new SandboxRepo("conflict", Files.createDirectories(tmp.resolve("repo")));
        builder.build(level.initial(), sandbox);
    }

    @Test
    void committing_unresolved_markers_matches_the_graph_but_fails_the_assertion() {
        git("merge", "feature");
        git("add", ".");            // 未编辑文件，直接暂存带标记的内容
        git("commit", "-m", "resolved conflict");

        MatchResult result = validate();
        assertThat(result.passed()).isFalse();
        // 结构其实已经对上了，唯一的差距是内容断言——原因必须点名它，否则玩家无从下手
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().get(0)).contains("greeting.txt").contains("<<<<<<<");
    }

    @Test
    void editing_the_file_before_committing_passes() throws Exception {
        git("merge", "feature");
        Files.writeString(sandbox.root().resolve("greeting.txt"), "hello world arena\n");
        git("add", ".");
        git("commit", "-m", "resolved conflict");

        MatchResult result = validate();
        assertThat(result.passed()).as("差异：%s", result.reasons()).isTrue();
    }

    private MatchResult validate() {
        GitGraph snapshot = mapper.map(sandbox);
        return matcher.match(snapshot, level.goal(), path -> inspector.fileAtHead(sandbox, path));
    }

    private void git(String sub, String... args) {
        executor.execute(sandbox, new ParsedCommand("git", sub, java.util.List.of(args), "git " + sub));
    }
}
