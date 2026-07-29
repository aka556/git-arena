package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SandboxManager} 沙盒生命周期单测：create / require / reset 的隔离与清理语义。
 *
 * <p>M1 登记仅在内存、临时目录落在系统 tmp 下（CLAUDE.md 更新记录）；每个用例后调 cleanupAll 清盘。
 */
class SandboxManagerTest {

    private SandboxManager manager;

    @BeforeEach
    void setUp() {
        manager = new SandboxManager();
    }

    @AfterEach
    void tearDown() {
        manager.cleanupAll(); // 同包可见，清理本次创建的临时目录
    }

    @Test
    void createMakesEmptyNonGitDirectory() {
        SandboxRepo r = manager.create();

        assertThat(r.sessionId()).isNotBlank();
        assertThat(Files.isDirectory(r.root())).isTrue();
        assertThat(r.isInitialized()).isFalse(); // 交由用户执行 git init
    }

    @Test
    void createGivesUniqueSessionsAndDirs() {
        SandboxRepo a = manager.create();
        SandboxRepo b = manager.create();

        assertThat(a.sessionId()).isNotEqualTo(b.sessionId());
        assertThat(a.root()).isNotEqualTo(b.root());
    }

    @Test
    void requireReturnsCreatedSandbox() {
        SandboxRepo r = manager.create();

        assertThat(manager.require(r.sessionId())).isSameAs(r);
    }

    @Test
    void requireUnknownSessionThrows() {
        assertThatThrownBy(() -> manager.require("does-not-exist"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void resetClearsDirectoryButKeepsSession() throws IOException {
        SandboxRepo r = manager.create();
        Files.createDirectory(r.root().resolve(".git")); // 模拟已 init
        Files.writeString(r.root().resolve("a.txt"), "hi");
        assertThat(r.isInitialized()).isTrue();

        SandboxRepo after = manager.reset(r.sessionId());

        assertThat(after).isSameAs(r); // 同一会话、同一根路径
        assertThat(r.isInitialized()).isFalse(); // .git 被清除
        assertThat(Files.exists(r.root().resolve("a.txt"))).isFalse();
        assertThat(Files.isDirectory(r.root())).isTrue(); // 目录被重建
    }

    @Test
    void resetUnknownSessionThrows() {
        assertThatThrownBy(() -> manager.reset("does-not-exist"))
                .isInstanceOf(CommandException.class);
    }
}
