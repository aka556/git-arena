package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.Test;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GoalMatcher} 匹配算法单测（docs/level-spec.md §5.3 规范性算法）。
 * 纯内存构造快照与 goal，不碰仓库；文件断言用桩注入。
 */
class GoalMatcherTest {

    private final GoalMatcher matcher = new GoalMatcher();
    private final FileAtHead noFiles = path -> Optional.empty();

    // ---- 基础匹配 -----------------------------------------------------------

    @Test
    void linearHistoryMatches() {
        GitGraph snapshot = snapshot(
                List.of(node("bbb", List.of("aaa"), "second"), node("aaa", List.of(), "first")),
                List.of(branch("main", "bbb")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of()), commit("C2", List.of("C1"))),
                List.of(ref("main", "C2")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void missingBranchFails() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1"), ref("feature", "C1")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("缺少分支") && r.contains("feature"));
    }

    @Test
    void wrongParentStructureFails() {
        // 实际：两个根提交并列（无父子关系）；目标：链式
        GitGraph snapshot = snapshot(
                List.of(node("bbb", List.of(), "second"), node("aaa", List.of(), "first")),
                List.of(branch("main", "bbb")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of()), commit("C2", List.of("C1"))),
                List.of(ref("main", "C2")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("父提交结构不符"));
    }

    @Test
    void mergeParentOrderMatters() {
        // 实际 merge：首父 = feature 侧（方向反了）
        GitGraph snapshot = snapshot(
                List.of(
                        node("mmm", List.of("fff", "aab"), "merge"),
                        node("aab", List.of("base"), "main work"),
                        node("fff", List.of("base"), "feature work"),
                        node("base", List.of(), "base")),
                List.of(branch("main", "mmm"), branch("feature", "fff")),
                head("branch", "main"));
        // 目标 merge：首父应为 main 侧 C2
        LevelFile.GoalSpec goal = goal(
                List.of(
                        commit("C1", List.of()),
                        commit("C2", List.of("C1")),
                        commit("C3", List.of("C1")),
                        commit("C4", List.of("C2", "C3"))),
                List.of(ref("main", "C4"), ref("feature", "C3")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        // C4 首父绑定到 fff，但 feature 分支又把 C3 锚到 fff → 绑定冲突（方向可被察觉）
        assertThat(result.passed()).isFalse();
    }

    @Test
    void correctMergeDirectionPasses() {
        GitGraph snapshot = snapshot(
                List.of(
                        node("mmm", List.of("aab", "fff"), "merge"),
                        node("aab", List.of("base"), "main work"),
                        node("fff", List.of("base"), "feature work"),
                        node("base", List.of(), "base")),
                List.of(branch("main", "mmm"), branch("feature", "fff")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(
                        commit("C1", List.of()),
                        commit("C2", List.of("C1")),
                        commit("C3", List.of("C1")),
                        commit("C4", List.of("C2", "C3"))),
                List.of(ref("main", "C4"), ref("feature", "C3")),
                head2("branch", "main"), null, null);

        assertThat(matcher.match(snapshot, goal, noFiles).passed()).isTrue();
    }

    // ---- allowExtra* / head / message / workingDir --------------------------

    @Test
    void extraCommitFailsByDefault() {
        GitGraph snapshot = snapshot(
                List.of(node("ccc", List.of("bbb"), "extra"), node("bbb", List.of("aaa"), "second"), node("aaa", List.of(), "first")),
                List.of(branch("main", "ccc")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of()), commit("C2", List.of("C1"))),
                List.of(ref("main", "C2")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
    }

    @Test
    void extraBranchFailsByDefaultButAllowedByPolicy() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa"), branch("dev", "aaa")),
                head("branch", "main"));
        LevelFile.GoalSpec strict = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"), null, null);
        LevelFile.GoalSpec loose = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"),
                new LevelFile.MatchPolicy(null, true, null, null, null, null),
                null);

        assertThat(matcher.match(snapshot, strict, noFiles).passed()).isFalse();
        assertThat(matcher.match(snapshot, loose, noFiles).passed()).isTrue();
    }

    @Test
    void headOnWrongBranchFails() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa"), branch("dev", "aaa")),
                head("branch", "dev"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1"), ref("dev", "C1")),
                head2("branch", "main"), null, null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("HEAD 应在分支 main"));
    }

    @Test
    void messageComparedWhenIgnoreMessagesFalse() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "actual message")),
                List.of(branch("main", "aaa")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(new LevelFile.Commit("C1", List.of(), "expected message", null, null)),
                List.of(ref("main", "C1")),
                head2("branch", "main"),
                new LevelFile.MatchPolicy(null, null, null, false, null, null),
                null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("信息不符"));
    }

    @Test
    void workingDirComparedWhenEnabled() {
        GitGraph snapshot = new GitGraph(1,
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa")),
                List.of(),
                head("branch", "main"),
                List.of(),
                new GitGraph.WorkingDir(List.of(), List.of(), List.of("dirty.txt")));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"),
                new LevelFile.MatchPolicy(null, null, null, null, null, true),
                null);

        MatchResult result = matcher.match(snapshot, goal, noFiles);

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("untracked"));
    }

    // ---- assertions ---------------------------------------------------------

    @Test
    void branchExistsAssertion() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa"), branch("feature", "aaa")),
                head("branch", "main"));
        LevelFile.GoalSpec goal = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"),
                new LevelFile.MatchPolicy(null, true, null, null, null, null),
                List.of(new LevelFile.Assertion("branchExists", "feature", null, null, null, null)));

        assertThat(matcher.match(snapshot, goal, noFiles).passed()).isTrue();
    }

    @Test
    void fileAssertionsUseFileReader() {
        GitGraph snapshot = snapshot(
                List.of(node("aaa", List.of(), "first")),
                List.of(branch("main", "aaa")),
                head("branch", "main"));
        FileAtHead reader = path -> "greeting.txt".equals(path)
                ? Optional.of("hello world arena\n")
                : Optional.empty();

        LevelFile.GoalSpec contains = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"), null,
                List.of(new LevelFile.Assertion("fileAtHeadContains", null, "greeting.txt", "arena", null, null)));
        LevelFile.GoalSpec notContains = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"), null,
                List.of(new LevelFile.Assertion("fileAtHeadNotContains", null, "greeting.txt", "<<<<<<<", null, null)));
        LevelFile.GoalSpec failing = goal(
                List.of(commit("C1", List.of())),
                List.of(ref("main", "C1")),
                head2("branch", "main"), null,
                List.of(new LevelFile.Assertion("fileAtHeadContains", null, "missing.txt", "x", null, null)));

        assertThat(matcher.match(snapshot, contains, reader).passed()).isTrue();
        assertThat(matcher.match(snapshot, notContains, reader).passed()).isTrue();
        assertThat(matcher.match(snapshot, failing, reader).passed()).isFalse();
    }

    // ---- helpers ------------------------------------------------------------

    private GitGraph snapshot(List<GitGraph.CommitNode> commits, List<GitGraph.BranchRef> branches, GitGraph.HeadRef head) {
        return new GitGraph(1, commits, branches, List.of(), head, List.of(),
                new GitGraph.WorkingDir(List.of(), List.of(), List.of()));
    }

    private GitGraph.CommitNode node(String id, List<String> parents, String message) {
        return new GitGraph.CommitNode(id, parents, message, "player", 0, null);
    }

    private GitGraph.BranchRef branch(String name, String target) {
        return new GitGraph.BranchRef(name, target, false);
    }

    private GitGraph.HeadRef head(String type, String ref) {
        return new GitGraph.HeadRef(type, ref);
    }

    private LevelFile.Commit commit(String seq, List<String> parents) {
        return new LevelFile.Commit(seq, parents, null, null, null);
    }

    private LevelFile.Ref ref(String name, String target) {
        return new LevelFile.Ref(name, target);
    }

    private LevelFile.Head head2(String type, String ref) {
        return new LevelFile.Head(type, ref);
    }

    private LevelFile.GoalSpec goal(List<LevelFile.Commit> commits, List<LevelFile.Ref> branches,
                                    LevelFile.Head head, LevelFile.MatchPolicy match,
                                    List<LevelFile.Assertion> assertions) {
        return new LevelFile.GoalSpec(
                new LevelFile.GoalGraph(commits, branches, null, head, null, null),
                match, assertions);
    }
}
