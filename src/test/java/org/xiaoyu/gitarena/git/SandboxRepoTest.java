package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxRepo} 句柄语义单测：会话/路径透传，以及 {@code isInitialized} 以 {@code .git}
 * 目录存在为准（非文件）。
 */
class SandboxRepoTest {

    @TempDir
    Path tmp;

    @Test
    void exposesSessionIdAndRoot() {
        SandboxRepo repo = new SandboxRepo("sess-1", tmp);

        assertThat(repo.sessionId()).isEqualTo("sess-1");
        assertThat(repo.root()).isEqualTo(tmp);
    }

    @Test
    void notInitializedWithoutGitDir() {
        SandboxRepo repo = new SandboxRepo("s", tmp);

        assertThat(repo.isInitialized()).isFalse();
    }

    @Test
    void initializedWhenGitDirExists() throws IOException {
        Files.createDirectory(tmp.resolve(".git"));
        SandboxRepo repo = new SandboxRepo("s", tmp);

        assertThat(repo.isInitialized()).isTrue();
    }

    @Test
    void notInitializedWhenGitIsAPlainFile() throws IOException {
        // .git 作为文件（gitlink 场景）不算已初始化——isInitialized 要求目录
        Files.createFile(tmp.resolve(".git"));
        SandboxRepo repo = new SandboxRepo("s", tmp);

        assertThat(repo.isInitialized()).isFalse();
    }
}
