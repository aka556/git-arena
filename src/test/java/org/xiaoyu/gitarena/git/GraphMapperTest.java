package org.xiaoyu.gitarena.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphMapper} 仓库 → GitGraph 快照映射单测（CLAUDE.md §5 契约 / §9 正确性核心）。
 *
 * <p>常规状态用 M1 命令链路（{@link GitExecutor}）构造；M1 尚不支持的场景（tag / 游离 HEAD /
 * merge 双父）直接用 JGit API 造仓库，验证 mapper 的读取分支。断言以 commit id / seq 为 key，
 * 不依赖数组下标或时间戳（呼应 §6.3 布局稳定性）。
 */
class GraphMapperTest {

    @TempDir
    Path tmp;

    private GraphMapper mapper;
    private GitExecutor executor;
    private SandboxRepo sandbox;

    @BeforeEach
    void setUp() {
        mapper = new GraphMapper();
        executor = new GitExecutor(new PathGuard());
        sandbox = new SandboxRepo("test", tmp);
    }

    @Test
    void uninitializedSandboxMapsToEmptyGraph() {
        GitGraph g = mapper.map(sandbox);

        assertThat(g.version()).isEqualTo(GitGraph.CONTRACT_VERSION);
        assertThat(g.commits()).isEmpty();
        assertThat(g.branches()).isEmpty();
        assertThat(g.tags()).isEmpty();
        assertThat(g.remotes()).isEmpty();
        assertThat(g.head().type()).isEqualTo("branch");
        assertThat(g.head().ref()).isEqualTo("main");
        assertThat(g.workingDir().staged()).isEmpty();
        assertThat(g.workingDir().modified()).isEmpty();
        assertThat(g.workingDir().untracked()).isEmpty();
    }

    @Test
    void initializedButEmptyRepoHasNoCommitsOrBranches() {
        git("init"); // 分支 main 尚未出生（无提交）

        GitGraph g = mapper.map(sandbox);
        assertThat(g.commits()).isEmpty();
        assertThat(g.branches()).isEmpty();
        assertThat(g.head().type()).isEqualTo("branch");
        assertThat(g.head().ref()).isEqualTo("main");
    }

    @Test
    void stagedFileBeforeFirstCommitShowsInWorkingDir() {
        git("init");
        helper("touch", "a.txt");
        git("add", "a.txt");

        GitGraph g = mapper.map(sandbox);
        assertThat(g.commits()).isEmpty(); // 尚无提交
        assertThat(g.workingDir().staged()).containsExactly("a.txt");
        assertThat(g.workingDir().untracked()).isEmpty();
    }

    @Test
    void singleCommitMapsToOneNode() {
        git("init");
        commitFile("a.txt", "hello", "first commit");

        GitGraph g = mapper.map(sandbox);
        assertThat(g.commits()).hasSize(1);
        GitGraph.CommitNode c1 = g.commits().get(0);
        assertThat(c1.seq()).isEqualTo("C1");
        assertThat(c1.parents()).isEmpty();
        assertThat(c1.message()).isEqualTo("first commit");
        assertThat(c1.author()).isEqualTo("player");

        assertThat(g.branches()).hasSize(1);
        GitGraph.BranchRef main = g.branches().get(0);
        assertThat(main.name()).isEqualTo("main");
        assertThat(main.target()).isEqualTo(c1.id());
        assertThat(main.isRemote()).isFalse();

        assertThat(g.head().type()).isEqualTo("branch");
        assertThat(g.head().ref()).isEqualTo("main");
        assertThat(g.workingDir().staged()).isEmpty();
        assertThat(g.workingDir().modified()).isEmpty();
        assertThat(g.workingDir().untracked()).isEmpty();
    }

    @Test
    void twoCommitsAreOrderedNewestFirstWithStableSeq() {
        git("init");
        commitFile("a.txt", "hello", "first");
        commitFile("b.txt", "world", "second");

        GitGraph g = mapper.map(sandbox);
        assertThat(g.commits()).hasSize(2);

        GitGraph.CommitNode c1 = bySeq(g, "C1"); // 最老为 C1
        GitGraph.CommitNode c2 = bySeq(g, "C2");
        assertThat(c1.parents()).isEmpty();
        assertThat(c2.parents()).containsExactly(c1.id());
        assertThat(c2.message()).isEqualTo("second");

        assertThat(g.commits().get(0).seq()).isEqualTo("C2"); // 最新在前
        assertThat(g.branches().get(0).target()).isEqualTo(c2.id());
    }

    @Test
    void untrackedFileAppearsInWorkingDir() {
        git("init");
        commitFile("a.txt", "hello", "c1");
        helper("touch", "new.txt");

        GitGraph g = mapper.map(sandbox);
        assertThat(g.workingDir().untracked()).containsExactly("new.txt");
        assertThat(g.workingDir().staged()).isEmpty();
        assertThat(g.workingDir().modified()).isEmpty();
    }

    @Test
    void modifiedFileAppearsInWorkingDir() {
        git("init");
        commitFile("a.txt", "hello", "c1");
        helper("echo", "more", ">>", "a.txt"); // 改动已提交文件，未 add

        GitGraph g = mapper.map(sandbox);
        assertThat(g.workingDir().modified()).containsExactly("a.txt");
        assertThat(g.workingDir().staged()).isEmpty();
    }

    @Test
    void lightweightTagMapsToItsCommit() throws Exception {
        git("init");
        commitFile("a.txt", "hello", "c1");
        try (Git g = Git.open(tmp.toFile())) {
            g.tag().setName("v1.0").setAnnotated(false).call();
        }

        GitGraph graph = mapper.map(sandbox);
        assertThat(graph.tags()).hasSize(1);
        assertThat(graph.tags().get(0).name()).isEqualTo("v1.0");
        assertThat(graph.tags().get(0).target()).isEqualTo(graph.commits().get(0).id());
    }

    @Test
    void detachedHeadMapsToDetachedType() throws Exception {
        git("init");
        commitFile("a.txt", "hello", "c1");
        commitFile("b.txt", "world", "c2");

        GitGraph before = mapper.map(sandbox);
        String c1Id = bySeq(before, "C1").id();

        try (Git g = Git.open(tmp.toFile())) {
            ObjectId c1 = g.getRepository().resolve("HEAD~1"); // 游离到首个提交
            g.checkout().setName(c1.getName()).call();
        }

        GitGraph g = mapper.map(sandbox);
        assertThat(g.head().type()).isEqualTo("detached");
        assertThat(g.head().ref()).isEqualTo(c1Id);
        assertThat(g.commits()).hasSize(2); // 游离不移动 main，提交仍可达
    }

    @Test
    void commitReachableOnlyFromDetachedHeadIsIncluded() {
        git("init");
        commitFile("a.txt", "hello", "c1");
        commitFile("b.txt", "world", "c2");
        String c1Id = bySeq(mapper.map(sandbox), "C1").id();

        git("checkout", c1Id); // 游离到 C1
        commitFile("c.txt", "detached", "on detached"); // 仅 HEAD 可达的提交

        GitGraph g = mapper.map(sandbox);
        assertThat(g.commits()).hasSize(3); // 图必须包含游离态提交（所见即真实仓库状态）
        assertThat(g.head().type()).isEqualTo("detached");
        // 同秒兄弟提交的 seq 归属有歧义，改按 HEAD 指向定位游离提交
        GitGraph.CommitNode detached = g.commits().stream()
                .filter(c -> c.id().equals(g.head().ref()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("HEAD 指向的提交不在图里"));
        assertThat(detached.message()).isEqualTo("on detached");
        assertThat(detached.parents()).containsExactly(c1Id);
    }

    @Test
    void movingHeadAwayDropsUnreachableDetachedCommit() {
        git("init");
        commitFile("a.txt", "hello", "c1");
        String c1Id = bySeq(mapper.map(sandbox), "C1").id();

        git("checkout", c1Id);
        commitFile("b.txt", "detached", "on detached");
        assertThat(mapper.map(sandbox).commits()).hasSize(2);

        git("checkout", c1Id); // 离开游离提交 → 不可达，快照只含可达提交（幽灵化是前端动画的事）
        assertThat(mapper.map(sandbox).commits()).hasSize(1);
    }

    @Test
    void mergeCommitHasTwoParents() throws Exception {
        String c2Short;
        String c3Short;
        // M1 命令集不含 branch/merge，直接用 JGit 造一个双父合并
        try (Git g = Git.init().setDirectory(tmp.toFile()).setInitialBranch("main").call()) {
            RevCommit c1 = writeAndCommit(g, "base.txt", "base", "c1 base");
            g.branchCreate().setName("feature").setStartPoint(c1.getName()).call();

            RevCommit c2 = writeAndCommit(g, "main.txt", "on main", "c2 on main");

            g.checkout().setName("feature").call();
            RevCommit c3 = writeAndCommit(g, "feature.txt", "on feature", "c3 on feature");

            g.checkout().setName("main").call();
            g.merge().include(c3)
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF) // 强制生成合并提交
                    .setMessage("merge feature")
                    .call();

            c2Short = shortId(c2.getName());
            c3Short = shortId(c3.getName());
        }

        GitGraph graph = mapper.map(sandbox);
        assertThat(graph.commits()).hasSize(4);

        GitGraph.CommitNode merge = graph.commits().get(0); // 合并提交最新
        assertThat(merge.message()).isEqualTo("merge feature");
        assertThat(merge.seq()).isEqualTo("C4");
        assertThat(merge.parents()).containsExactlyInAnyOrder(c2Short, c3Short);

        assertThat(graph.branches()).extracting(GitGraph.BranchRef::name)
                .containsExactlyInAnyOrder("main", "feature");
    }

    @Test
    void mergeViaCommandChainProducesTwoParentNode() {
        // 走 M2 executor 命令链路（checkout -b / merge），验证合并提交经 mapper 读出双父
        git("init");
        commitFile("shared.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitFile("f.txt", "feat", "fc");
        git("checkout", "main");
        commitFile("m.txt", "main", "mc");
        git("merge", "feature");

        GitGraph graph = mapper.map(sandbox);
        assertThat(graph.commits()).hasSize(4);

        GitGraph.CommitNode merge = graph.commits().get(0); // 合并提交最新
        assertThat(merge.parents()).hasSize(2);
        assertThat(graph.branches()).extracting(GitGraph.BranchRef::name)
                .contains("main", "feature");
        assertThat(graph.head().type()).isEqualTo("branch");
        assertThat(graph.head().ref()).isEqualTo("main");
    }

    // ---- helpers -----------------------------------------------------------

    private void git(String sub, String... args) {
        executor.execute(sandbox, new ParsedCommand("git", sub, List.of(args), "git " + sub));
    }

    private void helper(String program, String... args) {
        executor.execute(sandbox, new ParsedCommand(program, null, List.of(args), program));
    }

    private void commitFile(String name, String content, String message) {
        helper("echo", content, ">", name);
        git("add", name);
        git("commit", "-m", message);
    }

    private RevCommit writeAndCommit(Git g, String name, String content, String message) throws Exception {
        Files.writeString(tmp.resolve(name), content);
        g.add().addFilepattern(name).call();
        return g.commit().setMessage(message).setAuthor("player", "player@git-arena.local").call();
    }

    private GitGraph.CommitNode bySeq(GitGraph g, String seq) {
        return g.commits().stream()
                .filter(c -> seq.equals(c.seq()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no commit with seq " + seq));
    }

    private String shortId(String fullSha) {
        return fullSha.length() <= 7 ? fullSha : fullSha.substring(0, 7);
    }
}
