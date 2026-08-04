package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程模拟流程单测（M3 阶段 A）：用 {@link LevelBuilder} 造出"带 origin 的本地仓库"，
 * 再经真实 {@link GitExecutor} 跑 fetch/push/pull/remote，校验远程跟踪分支的移动与非快进拒绝。
 * origin 是沙盒兄弟目录里的裸仓库，file 协议传输、不出网（CLAUDE.md §7）。
 */
class RemoteFlowTest {

    @TempDir
    Path base;

    private LevelBuilder builder;
    private GitExecutor executor;
    private GraphMapper mapper;
    private SandboxRepo sandbox;

    @BeforeEach
    void setUp() {
        PathGuard guard = new PathGuard();
        builder = new LevelBuilder(guard);
        executor = new GitExecutor(guard);
        mapper = new GraphMapper();
        sandbox = new SandboxRepo("remote-test", base.resolve("work"));
    }

    @Test
    void remoteListShowsOrigin() {
        // 本地 main=C1，origin/main=C1
        builder.build(new LevelFile.InitialSpec(
                List.of(commit("C1", List.of(), "c1", Map.of("a.txt", "1\n"))),
                List.of(ref("main", "C1")), null, head("main"),
                List.of(remote("origin", "main", "C1", "C1")), null), sandbox);

        assertThat(git("remote").stdout()).contains("origin");
        assertThat(git("remote", "-v").stdout()).contains("origin").contains("(fetch)").contains("(push)");
    }

    @Test
    void fetchAdvancesTrackingRef() {
        // origin/main 真实在 C2，本地 tracking 停在 C1，本地 main 在 C1
        builder.build(new LevelFile.InitialSpec(
                List.of(commit("C1", List.of(), "c1", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "c2", Map.of("b.txt", "2\n"))),
                List.of(ref("main", "C1")), null, head("main"),
                List.of(remote("origin", "main", "C2", "C1")), null), sandbox);

        ExecOutput out = git("fetch");
        assertThat(out.ok()).isTrue();

        GitGraph g = mapper.map(sandbox);
        String c2 = idOfMessage(g, "c2"); // fetch 后 C2 因 tracking 前移而可达
        assertThat(trackingTarget(g, "origin", "main")).isEqualTo(c2);
        assertThat(localTarget(g, "main")).isNotEqualTo(c2); // 本地 main 未动
    }

    @Test
    void pushFastForwardUpdatesOrigin() {
        // 本地 main 领先到 C2，origin 在 C1
        builder.build(new LevelFile.InitialSpec(
                List.of(commit("C1", List.of(), "c1", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "c2", Map.of("b.txt", "2\n"))),
                List.of(ref("main", "C2")), null, head("main"),
                List.of(remote("origin", "main", "C1", "C1")), null), sandbox);

        ExecOutput out = git("push", "origin", "main");
        assertThat(out.ok()).isTrue();

        GitGraph g = mapper.map(sandbox);
        String c2 = idOfMessage(g, "c2");
        assertThat(trackingTarget(g, "origin", "main")).isEqualTo(c2);
        assertThat(localTarget(g, "main")).isEqualTo(c2);
    }

    @Test
    void pushNonFastForwardIsRejected() {
        // 本地 C3 与 origin C2 分叉（都基于 C1）
        builder.build(new LevelFile.InitialSpec(
                List.of(commit("C1", List.of(), "c1", Map.of("base.txt", "base\n")),
                        commit("C2", List.of("C1"), "c2", Map.of("r.txt", "r\n")),
                        commit("C3", List.of("C1"), "c3", Map.of("l.txt", "l\n"))),
                List.of(ref("main", "C3")), null, head("main"),
                List.of(remote("origin", "main", "C2", "C2")), null), sandbox);

        ExecOutput out = git("push", "origin", "main");
        assertThat(out.ok()).isFalse();
        assertThat(out.stderr()).contains("non-fast-forward");
    }

    @Test
    void pushWithoutRemoteConfigIsRejected() {
        SandboxRepo plain = new SandboxRepo("plain", base.resolve("plain"));
        run(plain, "git", "init");
        run(plain, "touch", "a.txt");
        run(plain, "git", "add", "a.txt");
        run(plain, "git", "commit", "-m", "c1");

        assertThatThrownBy(() -> executor.execute(plain,
                new ParsedCommand("git", "push", List.of("origin", "main"), "git push origin main")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("没有配置远程");
    }

    // ---- helpers -----------------------------------------------------------

    private LevelFile.Commit commit(String seq, List<String> parents, String msg, Map<String, String> files) {
        return new LevelFile.Commit(seq, parents, msg, null, files);
    }

    private LevelFile.Ref ref(String name, String target) {
        return new LevelFile.Ref(name, target);
    }

    private LevelFile.Head head(String branch) {
        return new LevelFile.Head("branch", branch);
    }

    private LevelFile.Remote remote(String name, String branch, String target, String tracked) {
        return new LevelFile.Remote(name, List.of(new LevelFile.RemoteBranch(branch, target, tracked)));
    }

    private ExecOutput git(String sub, String... args) {
        return executor.execute(sandbox, new ParsedCommand("git", sub, List.of(args), "git " + sub));
    }

    private void run(SandboxRepo repo, String program, String... args) {
        boolean isGit = "git".equals(program);
        executor.execute(repo, new ParsedCommand(program, isGit ? args[0] : null,
                isGit ? List.of(args).subList(1, args.length) : List.of(args), program));
    }

    private String idOfMessage(GitGraph g, String message) {
        return g.commits().stream().filter(c -> c.message().equals(message))
                .findFirst().orElseThrow().id();
    }

    private String trackingTarget(GitGraph g, String remote, String branch) {
        return g.remotes().stream().filter(r -> r.name().equals(remote)).findFirst().orElseThrow()
                .branches().stream().filter(b -> b.name().equals(branch)).findFirst().orElseThrow().target();
    }

    private String localTarget(GitGraph g, String branch) {
        return g.branches().stream().filter(b -> b.name().equals(branch)).findFirst().orElseThrow().target();
    }
}
