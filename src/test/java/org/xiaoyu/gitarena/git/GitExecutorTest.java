package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GitExecutor} 命令执行链路单测（CLAUDE.md §9：git/ 包正确性核心）。
 *
 * <p>每个测试用例在独立 {@link TempDir} 上跑真实 JGit，不启 Spring、不连 DB——
 * 直接 new 组件，验证 init/add/commit/log/status + touch/echo 的成功输出与友好报错。
 */
class GitExecutorTest {

    @TempDir
    Path tmp;

    private GitExecutor executor;
    private SandboxRepo sandbox;

    @BeforeEach
    void setUp() {
        executor = new GitExecutor(new PathGuard());
        sandbox = new SandboxRepo("test-session", tmp);
    }

    // ---- git init ----------------------------------------------------------

    @Test
    void initCreatesRepository() {
        ExecOutput out = git("init");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Initialized empty Git repository").contains("main");
        assertThat(sandbox.isInitialized()).isTrue();
    }

    @Test
    void initTwiceReportsReinitialized() {
        git("init");
        ExecOutput out = git("init");

        assertThat(out.stdout()).contains("Reinitialized existing Git repository");
    }

    @Test
    void gitSubcommandBeforeInitIsRejected() {
        assertThatThrownBy(() -> git("status"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("git init");
    }

    @Test
    void unsupportedProgramIsRejected() {
        // execute() 是 public 入口：即使解析层被绕过，也须挡住白名单外的程序
        ParsedCommand cmd = new ParsedCommand("ls", null, List.of(), "ls");
        assertThatThrownBy(() -> executor.execute(sandbox, cmd))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不允许的命令");
    }

    @Test
    void unsupportedGitSubcommandIsRejected() {
        git("init");
        // reset 尚未进 executor 的 switch —— 纵深防御：即便过了白名单也要挡下
        assertThatThrownBy(() -> git("reset"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("暂不支持的 git 子命令");
    }

    // ---- touch / echo ------------------------------------------------------

    @Test
    void touchCreatesFile() {
        ExecOutput out = helper("touch", "a.txt");

        assertThat(out.ok()).isTrue();
        assertThat(Files.exists(tmp.resolve("a.txt"))).isTrue();
    }

    @Test
    void touchNoArgsIsRejected() {
        assertThatThrownBy(() -> helper("touch"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("touch");
    }

    @Test
    void touchCreatesParentDirectories() {
        helper("touch", "dir/sub/a.txt");

        assertThat(Files.exists(tmp.resolve("dir/sub/a.txt"))).isTrue();
    }

    @Test
    void echoWithoutRedirectReturnsText() {
        ExecOutput out = helper("echo", "hello", "world");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).isEqualTo("hello world");
    }

    @Test
    void echoRedirectWritesFile() throws IOException {
        helper("echo", "hello", ">", "a.txt");

        assertThat(Files.readString(tmp.resolve("a.txt")))
                .isEqualTo("hello" + System.lineSeparator());
    }

    @Test
    void echoAppendAddsToFile() throws IOException {
        helper("echo", "hello", ">", "a.txt");
        helper("echo", "world", ">>", "a.txt");

        assertThat(Files.readString(tmp.resolve("a.txt")))
                .isEqualTo("hello" + System.lineSeparator() + "world" + System.lineSeparator());
    }

    @Test
    void echoRedirectWithoutTargetIsRejected() {
        assertThatThrownBy(() -> helper("echo", "hello", ">"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("缺少目标文件");
    }

    @Test
    void echoRedirectWithTooManyTargetsIsRejected() {
        assertThatThrownBy(() -> helper("echo", "hello", ">", "a.txt", "b.txt"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("只能跟一个文件名");
    }

    // ---- git add -----------------------------------------------------------

    @Test
    void addNoArgsIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("add"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("git add");
    }

    @Test
    void addStagesFile() {
        git("init");
        helper("touch", "a.txt");
        ExecOutput add = git("add", "a.txt");

        assertThat(add.ok()).isTrue();
        assertThat(git("status").stdout())
                .contains("Changes to be committed")
                .contains("new file:   a.txt");
    }

    @Test
    void addDotStagesEverything() {
        git("init");
        helper("touch", "a.txt");
        helper("touch", "b.txt");
        git("add", ".");

        assertThat(git("status").stdout())
                .contains("new file:   a.txt")
                .contains("new file:   b.txt");
    }

    @Test
    void addRejectsPathTraversal() {
        git("init");
        // 越权路径必须被 PathGuard 拦下（CLAUDE.md §7.4）
        assertThatThrownBy(() -> git("add", "../evil.txt"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("越界");
    }

    // ---- git commit --------------------------------------------------------

    @Test
    void commitCreatesCommit() {
        git("init");
        helper("touch", "a.txt");
        git("add", "a.txt");
        ExecOutput out = git("commit", "-m", "first commit");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).startsWith("[main ").contains("first commit");
        assertThat(git("log").stdout()).contains("first commit");
    }

    @Test
    void commitWithoutMessageIsRejected() {
        git("init");
        helper("touch", "a.txt");
        git("add", "a.txt");
        assertThatThrownBy(() -> git("commit"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("提交信息");
    }

    @Test
    void commitDashMWithoutValueIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("commit", "-m"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("-m 后缺少提交信息");
    }

    @Test
    void commitWithNothingStagedIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("commit", "-m", "empty"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("nothing to commit");
    }

    // ---- git log / status --------------------------------------------------

    @Test
    void logListsCommitsNewestFirst() {
        git("init");
        commitFile("a.txt", "first");
        commitFile("b.txt", "second");

        String[] lines = git("log").stdout().split("\\R");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("second"); // 最新在前
        assertThat(lines[1]).contains("first");
    }

    @Test
    void logWithoutCommitsIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("log"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("还没有任何提交");
    }

    @Test
    void statusIsCleanAfterCommit() {
        git("init");
        commitFile("a.txt", "c1");

        assertThat(git("status").stdout())
                .contains("On branch main")
                .contains("nothing to commit, working tree clean");
    }

    @Test
    void statusShowsUntrackedFiles() {
        git("init");
        helper("touch", "a.txt");

        assertThat(git("status").stdout())
                .contains("Untracked files")
                .contains("a.txt");
    }

    @Test
    void statusShowsModifiedFiles() {
        git("init");
        helper("echo", "hello", ">", "a.txt");
        git("add", "a.txt");
        git("commit", "-m", "c1");
        helper("echo", "more", ">>", "a.txt"); // 改动已跟踪文件但未 add

        assertThat(git("status").stdout())
                .contains("Changes not staged for commit")
                .contains("modified:   a.txt");
    }

    // ---- git branch (M2) ---------------------------------------------------

    @Test
    void branchListMarksCurrentWithStar() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature");

        String out = git("branch").stdout();
        assertThat(out).contains("* main").contains("feature");
    }

    @Test
    void branchCreateBeforeCommitIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("branch", "feature"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("尚无提交");
    }

    @Test
    void branchCreateDuplicateIsRejected() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature");
        assertThatThrownBy(() -> git("branch", "feature"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("分支已存在");
    }

    @Test
    void branchDeleteRemovesBranch() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature");
        ExecOutput out = git("branch", "-d", "feature");

        assertThat(out.stdout()).contains("Deleted branch feature");
        assertThat(git("branch").stdout()).doesNotContain("feature");
    }

    @Test
    void branchDeleteCurrentIsRejected() {
        git("init");
        commitFile("a.txt", "c1");
        assertThatThrownBy(() -> git("branch", "-d", "main"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("当前所在分支");
    }

    // ---- git checkout / switch (M2) ---------------------------------------

    @Test
    void checkoutSwitchesToExistingBranch() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature");
        ExecOutput out = git("checkout", "feature");

        assertThat(out.stdout()).contains("Switched to branch 'feature'");
        assertThat(git("status").stdout()).contains("On branch feature");
    }

    @Test
    void checkoutDashBCreatesAndSwitches() {
        git("init");
        commitFile("a.txt", "c1");
        ExecOutput out = git("checkout", "-b", "dev");

        assertThat(out.stdout()).contains("Switched to a new branch 'dev'");
        assertThat(git("status").stdout()).contains("On branch dev");
    }

    @Test
    void checkoutNonexistentIsRejected() {
        git("init");
        commitFile("a.txt", "c1");
        assertThatThrownBy(() -> git("checkout", "nope"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("未找到分支或提交");
    }

    @Test
    void checkoutCommitDetachesHead() {
        git("init");
        commitFile("a.txt", "c1");
        commitFile("b.txt", "c2");
        // log 为最新在前：第 2 行是 c1 的短 sha
        String[] logLines = git("log").stdout().split("\\R");
        String c1Sha = logLines[1].split(" ")[0];

        ExecOutput out = git("checkout", c1Sha);
        assertThat(out.stdout()).contains("detached HEAD");
        assertThat(git("branch").stdout()).contains("HEAD detached at");
    }

    @Test
    void switchToExistingBranch() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature");
        ExecOutput out = git("switch", "feature");

        assertThat(out.stdout()).contains("Switched to branch 'feature'");
    }

    @Test
    void switchDashCCreatesBranch() {
        git("init");
        commitFile("a.txt", "c1");
        ExecOutput out = git("switch", "-c", "dev");

        assertThat(out.stdout()).contains("Switched to a new branch 'dev'");
        assertThat(git("status").stdout()).contains("On branch dev");
    }

    @Test
    void switchToNonexistentIsRejected() {
        git("init");
        commitFile("a.txt", "c1");
        assertThatThrownBy(() -> git("switch", "nope"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("没有名为");
    }

    // ---- git merge (M2) ----------------------------------------------------

    @Test
    void mergeFastForward() {
        git("init");
        commitFile("a.txt", "c1");
        git("checkout", "-b", "feature");
        commitFile("b.txt", "c2");
        git("checkout", "main");
        ExecOutput out = git("merge", "feature");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Fast-forward");
        assertThat(git("log").stdout()).contains("c2").contains("c1");
    }

    @Test
    void mergeAlreadyUpToDate() {
        git("init");
        commitFile("a.txt", "c1");
        git("branch", "feature"); // feature 指向 c1，是 main 的祖先
        ExecOutput out = git("merge", "feature");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Already up to date");
    }

    @Test
    void mergeDivergedCreatesMergeCommit() {
        git("init");
        commitContent("shared.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitContent("f.txt", "feat", "fc");
        git("checkout", "main");
        commitContent("m.txt", "main", "mc");
        ExecOutput out = git("merge", "feature");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Merge made");
    }

    @Test
    void mergeConflictReportsFailureAndLeavesState() {
        git("init");
        commitContent("file.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitContent("file.txt", "feature-change", "fc");
        git("checkout", "main");
        commitContent("file.txt", "main-change", "mc");
        ExecOutput out = git("merge", "feature");

        assertThat(out.ok()).isFalse();
        assertThat(out.stderr()).contains("CONFLICT").contains("file.txt");
    }

    @Test
    void mergeUnknownBranchIsRejected() {
        git("init");
        commitFile("a.txt", "c1");
        assertThatThrownBy(() -> git("merge", "nope"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("未找到要合并的分支或提交");
    }

    // ---- git tag (M2) ------------------------------------------------------

    @Test
    void tagCreatesAndLists() {
        git("init");
        commitFile("a.txt", "c1");
        git("tag", "v1.0");

        assertThat(git("tag").stdout()).contains("v1.0");
    }

    @Test
    void tagOnGivenCommit() {
        git("init");
        commitFile("a.txt", "c1");
        commitFile("b.txt", "c2");
        // 在上一个提交（HEAD~1 = c1）打标签
        ExecOutput out = git("tag", "old", "HEAD~1");

        assertThat(out.ok()).isTrue();
        assertThat(git("tag").stdout()).contains("old");
    }

    @Test
    void tagDeleteRemovesTag() {
        git("init");
        commitFile("a.txt", "c1");
        git("tag", "v1.0");
        ExecOutput out = git("tag", "-d", "v1.0");

        assertThat(out.stdout()).contains("Deleted tag v1.0");
        assertThat(git("tag").stdout()).doesNotContain("v1.0");
    }

    // ---- git commit --amend (M2) -------------------------------------------

    @Test
    void commitAmendReplacesLastCommitKeepingSingleHistory() {
        git("init");
        commitContent("a.txt", "v1", "original");
        helper("echo", "v2", ">", "a.txt");
        git("add", "a.txt");
        ExecOutput out = git("commit", "--amend", "-m", "amended");

        assertThat(out.stdout()).contains("amended");
        // 仍只有一个提交（amend 不新增历史）
        assertThat(git("log").stdout().split("\\R")).hasSize(1);
        assertThat(git("log").stdout()).contains("amended").doesNotContain("original");
    }

    @Test
    void commitAmendBeforeAnyCommitIsRejected() {
        git("init");
        assertThatThrownBy(() -> git("commit", "--amend", "-m", "x"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("无法 --amend");
    }

    // ---- git rebase (M2) ---------------------------------------------------

    @Test
    void rebaseLinearizesDivergedHistory() {
        git("init");
        commitContent("base.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitContent("f.txt", "feat", "fc");
        git("checkout", "main");
        commitContent("m.txt", "main", "mc");
        git("checkout", "feature");
        ExecOutput out = git("rebase", "main");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Successfully rebased");
        // feature 现在接在 main 之后：c1 -> mc -> fc' 共 3 个
        assertThat(git("log").stdout().split("\\R")).hasSize(3);
    }

    @Test
    void rebaseConflictStopsThenContinues() throws java.io.IOException {
        git("init");
        commitContent("x.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitContent("x.txt", "feature", "fc");
        git("checkout", "main");
        commitContent("x.txt", "main", "mc");
        git("checkout", "feature");

        ExecOutput stopped = git("rebase", "main");
        assertThat(stopped.ok()).isFalse();
        assertThat(stopped.stderr()).contains("CONFLICT");

        // 解决冲突后 add + --continue
        java.nio.file.Files.writeString(tmp.resolve("x.txt"), "resolved\n");
        git("add", "x.txt");
        ExecOutput done = git("rebase", "--continue");
        assertThat(done.ok()).isTrue();
        assertThat(git("log").stdout().split("\\R")).hasSize(3);
    }

    // ---- git merge --squash (M2) -------------------------------------------

    @Test
    void mergeSquashStagesWithoutCommitting() {
        git("init");
        commitContent("base.txt", "base", "c1");
        git("checkout", "-b", "feature");
        commitContent("b.txt", "b", "fb");
        commitContent("c.txt", "c", "fc");
        git("checkout", "main");
        ExecOutput out = git("merge", "--squash", "feature");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).contains("Squash");
        assertThat(git("status").stdout()).contains("Changes to be committed");

        // 提交后 main 只多一个单亲提交（feature 的两个提交不进入 main 历史）
        git("commit", "-m", "squashed");
        assertThat(git("log").stdout().split("\\R")).hasSize(2);
    }

    // ---- helpers -----------------------------------------------------------

    private ExecOutput git(String sub, String... args) {
        return executor.execute(sandbox,
                new ParsedCommand("git", sub, List.of(args), "git " + sub));
    }

    private ExecOutput helper(String program, String... args) {
        return executor.execute(sandbox,
                new ParsedCommand(program, null, List.of(args), program));
    }

    private void commitFile(String name, String message) {
        helper("touch", name);
        git("add", name);
        git("commit", "-m", message);
    }

    private void commitContent(String name, String content, String message) {
        helper("echo", content, ">", name);
        git("add", name);
        git("commit", "-m", message);
    }
}
