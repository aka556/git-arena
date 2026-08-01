package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.security.PathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LevelBuilder} 单测：InitialSpec → 真实仓库的构建语义（docs/level-spec.md §4）。
 * 覆盖确定性（同 spec 双仓库同 hash）、文件继承/删除、merge 结构、workingDir 配方、空仓库。
 */
class LevelBuilderTest {

    @TempDir
    Path tmpA;
    @TempDir
    Path tmpB;

    private LevelBuilder builder;
    private GraphMapper mapper;

    @BeforeEach
    void setUp() {
        builder = new LevelBuilder(new PathGuard());
        mapper = new GraphMapper();
    }

    @Test
    void buildsLinearHistoryWithRefsAndHead() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(
                        commit("C1", List.of(), "base", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "second", Map.of("b.txt", "2\n"))),
                List.of(new LevelFile.Ref("main", "C2")),
                null,
                new LevelFile.Head("branch", "main"),
                null, null);
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);
        GitGraph graph = mapper.map(repo);

        assertThat(graph.commits()).hasSize(2);
        assertThat(graph.commits().get(0).message()).isEqualTo("second"); // 最新在前
        assertThat(graph.commits().get(0).parents()).containsExactly(graph.commits().get(1).id());
        assertThat(graph.branches()).extracting(GitGraph.BranchRef::name).containsExactly("main");
        assertThat(graph.head().type()).isEqualTo("branch");
        assertThat(graph.head().ref()).isEqualTo("main");
        // 工作区被填充且干净
        assertThat(Files.exists(tmpA.resolve("a.txt"))).isTrue();
        assertThat(Files.exists(tmpA.resolve("b.txt"))).isTrue();
        assertThat(graph.workingDir().untracked()).isEmpty();
        assertThat(graph.workingDir().modified()).isEmpty();
    }

    @Test
    void buildIsDeterministic_sameSpecSameHashes() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(
                        commit("C1", List.of(), "base", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "second", null)),
                List.of(new LevelFile.Ref("main", "C2")),
                null,
                new LevelFile.Head("branch", "main"),
                null, null);

        builder.build(spec, new SandboxRepo("a", tmpA));
        builder.build(spec, new SandboxRepo("b", tmpB));

        GitGraph ga = mapper.map(new SandboxRepo("a", tmpA));
        GitGraph gb = mapper.map(new SandboxRepo("b", tmpB));
        // 确定性构建：两个独立目录产出完全相同的 commit hash（§4.1）
        assertThat(ga.commits()).extracting(GitGraph.CommitNode::id)
                .containsExactlyElementsOf(gb.commits().stream().map(GitGraph.CommitNode::id).toList());
    }

    @Test
    void buildsMergeCommitWithOrderedParents() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(
                        commit("C1", List.of(), "base", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "main side", Map.of("m.txt", "m\n")),
                        commit("C3", List.of("C1"), "feature side", Map.of("f.txt", "f\n")),
                        commit("C4", List.of("C2", "C3"), "merged", null)),
                List.of(new LevelFile.Ref("main", "C4"), new LevelFile.Ref("feature", "C3")),
                null,
                new LevelFile.Head("branch", "main"),
                null, null);
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);
        GitGraph graph = mapper.map(repo);

        GitGraph.CommitNode merge = graph.commits().get(0);
        assertThat(merge.message()).isEqualTo("merged");
        assertThat(merge.parents()).hasSize(2);
        // 首父在前（C2=main 侧）
        GitGraph.CommitNode c2 = graph.commits().stream().filter(c -> c.message().equals("main side")).findFirst().orElseThrow();
        assertThat(merge.parents().get(0)).isEqualTo(c2.id());
        // merge 提交继承首父文件 + C4 未声明 files → 工作区含 a/m 不含 f？——继承规则只沿首父：f.txt 不在
        assertThat(Files.exists(tmpA.resolve("m.txt"))).isTrue();
        assertThat(Files.exists(tmpA.resolve("f.txt"))).isFalse();
    }

    @Test
    void nullFileEntryDeletesInheritedFile() {
        java.util.Map<String, String> delete = new java.util.HashMap<>();
        delete.put("a.txt", null); // null=删除（Map.of 不允许 null 值）
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(
                        commit("C1", List.of(), "base", Map.of("a.txt", "1\n", "keep.txt", "k\n")),
                        commit("C2", List.of("C1"), "remove a", delete)),
                List.of(new LevelFile.Ref("main", "C2")),
                null,
                new LevelFile.Head("branch", "main"),
                null, null);
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);

        assertThat(Files.exists(tmpA.resolve("a.txt"))).isFalse();
        assertThat(Files.exists(tmpA.resolve("keep.txt"))).isTrue();
    }

    @Test
    void emptyRepoWithUnbornBranch() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(), List.of(), null,
                new LevelFile.Head("branch", "main"),
                null,
                new LevelFile.InitialWorkingDir(Map.of("hello.txt", "hi\n"), List.of()));
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);
        GitGraph graph = mapper.map(repo);

        assertThat(graph.commits()).isEmpty();
        assertThat(graph.branches()).isEmpty();
        assertThat(graph.head().ref()).isEqualTo("main");
        assertThat(graph.workingDir().untracked()).containsExactly("hello.txt");
    }

    @Test
    void workingDirStagedSubsetIsAdded() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(commit("C1", List.of(), "base", Map.of("a.txt", "1\n"))),
                List.of(new LevelFile.Ref("main", "C1")),
                null,
                new LevelFile.Head("branch", "main"),
                null,
                new LevelFile.InitialWorkingDir(Map.of("staged.txt", "s\n", "loose.txt", "l\n"), List.of("staged.txt")));
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);
        GitGraph graph = mapper.map(repo);

        assertThat(graph.workingDir().staged()).containsExactly("staged.txt");
        assertThat(graph.workingDir().untracked()).containsExactly("loose.txt");
    }

    @Test
    void detachedHeadPointsAtCommit() {
        LevelFile.InitialSpec spec = new LevelFile.InitialSpec(
                List.of(
                        commit("C1", List.of(), "base", Map.of("a.txt", "1\n")),
                        commit("C2", List.of("C1"), "tip", null)),
                List.of(new LevelFile.Ref("main", "C2")),
                null,
                new LevelFile.Head("detached", "C1"),
                null, null);
        SandboxRepo repo = new SandboxRepo("s", tmpA);

        builder.build(spec, repo);
        GitGraph graph = mapper.map(repo);

        assertThat(graph.head().type()).isEqualTo("detached");
        GitGraph.CommitNode c1 = graph.commits().stream().filter(c -> c.message().equals("base")).findFirst().orElseThrow();
        assertThat(graph.head().ref()).isEqualTo(c1.id());
    }

    private LevelFile.Commit commit(String seq, List<String> parents, String message, Map<String, String> files) {
        return new LevelFile.Commit(seq, parents, message, null, files);
    }
}
