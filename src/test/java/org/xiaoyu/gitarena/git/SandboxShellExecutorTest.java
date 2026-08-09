package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxShellExecutorTest {

    @TempDir
    Path tmp;

    private SandboxRepo sandbox;
    private SandboxShellExecutor executor;
    private CommandParser parser;

    @BeforeEach
    void setUp() {
        PathGuard pathGuard = new PathGuard();
        sandbox = new SandboxRepo("shell-test", tmp);
        executor = new SandboxShellExecutor(pathGuard);
        parser = new CommandParser();
    }

    @Test
    void supportsCwdPwdLsAndVirtualRoot() {
        out("mkdir -p docs/sub");
        out("touch docs/a.txt");
        out("cd docs");

        assertThat(out("pwd")).isEqualTo("~/docs");
        assertThat(out("ls")).contains("a.txt").doesNotContain(".git");

        out("cd /");
        assertThat(out("pwd")).isEqualTo("~");
        assertThat(out("cat ~/docs/a.txt")).isEmpty();
    }

    @Test
    void supportsTextCommandsPipelineAndRedirect() throws IOException {
        Files.writeString(tmp.resolve("data.txt"), "b\na\na\n", StandardCharsets.UTF_8);

        assertThat(out("cat data.txt")).isEqualTo("b\na\na\n");
        assertThat(out("head -n 2 data.txt")).isEqualTo("b\na");
        assertThat(out("tail -1 data.txt")).isEqualTo("a");
        assertThat(out("wc -l data.txt")).isEqualTo("3");
        assertThat(out("grep -n a data.txt")).isEqualTo("2:a\n3:a");

        out("sort data.txt | uniq > out.txt");
        assertThat(Files.readString(tmp.resolve("out.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .isEqualTo("a\nb\n");
    }

    @Test
    void supportsFileMutationCommands() throws IOException {
        out("mkdir -p src/sub");
        Files.writeString(tmp.resolve("src/sub/a.txt"), "hello", StandardCharsets.UTF_8);

        out("cp -r src copy");
        assertThat(Files.readString(tmp.resolve("copy/sub/a.txt"), StandardCharsets.UTF_8)).isEqualTo("hello");

        out("cp copy/sub/a.txt moved.txt");
        out("mv moved.txt renamed.txt");
        assertThat(Files.exists(tmp.resolve("renamed.txt"))).isTrue();

        out("rm copy/sub/a.txt");
        out("rmdir copy/sub");
        out("rm -r copy");
        assertThat(Files.exists(tmp.resolve("copy"))).isFalse();
    }

    @Test
    void clearReturnsAnsiClearSequence() {
        assertThat(out("clear")).isEqualTo("\u001b[2J\u001b[H");
    }

    @Test
    void rejectsTraversalGitMetadataWindowsPathsAndDestructiveRoot() {
        assertThatThrownBy(() -> out("cat ../../outside.txt"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> out("ls .git"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> out("touch .GIT/config"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> out("cat C:\\Windows\\win.ini"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> out("rm -r /"))
                .isInstanceOf(CommandException.class);

        out("mkdir a");
        out("cd a");
        assertThatThrownBy(() -> out("rm -r ."))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void rejectsOversizedReads() throws IOException {
        Files.write(tmp.resolve("big.txt"), new byte[257 * 1024]);

        assertThatThrownBy(() -> out("cat big.txt"))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void rejectsSymbolicLinksWhenPlatformAllowsCreatingThem() throws IOException {
        Files.writeString(tmp.resolve("target.txt"), "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(tmp.resolve("link.txt"), tmp.resolve("target.txt"));
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links are not available in this test environment");
        }

        assertThatThrownBy(() -> out("cat link.txt"))
                .isInstanceOf(CommandException.class);
    }

    private String out(String raw) {
        ExecOutput output = executor.execute(sandbox, parser.parse(raw));
        assertThat(output.ok()).isTrue();
        return output.stdout();
    }
}
