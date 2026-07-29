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
        // branch 尚未进 M1 executor 的 switch —— 纵深防御：即便过了白名单也要挡下
        assertThatThrownBy(() -> git("branch"))
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
}
