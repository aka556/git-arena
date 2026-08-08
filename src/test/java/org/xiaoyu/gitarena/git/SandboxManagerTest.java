package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

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

    @Test
    void resolveKeyMapsSessionAndRoomNamespacesToSeparateTrees() {
        SandboxRepo r = manager.create();

        assertThat(manager.resolveKey(r.sessionId())).isEqualTo(r.root());
        assertThat(manager.resolveKey("rooms/abc.git"))
                .isEqualTo(manager.roomsBaseDir().resolve("abc.git"));
    }

    @Test
    void resolveKeyRejectsEscapeAttempts() {
        // 台账行同样按不可信输入处理（CLAUDE.md §7.4）：越界 key 不能变成宿主机路径
        assertThatThrownBy(() -> manager.resolveKey("../../etc"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> manager.resolveKey("/etc/passwd"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> manager.resolveKey("C:/Windows"))
                .isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> manager.resolveKey("  "))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void deleteByKeyRemovesWorkTreeAndItsSimulatedRemote() throws IOException {
        SandboxRepo r = manager.create();
        Files.createDirectories(r.originPath());

        manager.deleteByKey(r.sessionId());

        assertThat(Files.exists(r.root())).isFalse();
        assertThat(Files.exists(r.originPath())).isFalse();
        assertThat(manager.find(r.sessionId())).isNull(); // 内存登记一并摘除
    }

    @Test
    void reapIdleDropsUntouchedSandboxesAndKeepsFreshOnes() throws InterruptedException {
        SandboxRepo stale = manager.create();
        Thread.sleep(20);
        SandboxRepo fresh = manager.create();

        // 阈值取两次 create 的间隔之内：stale 已超时，fresh 刚被 create 摸过
        int reaped = manager.reapIdle(Duration.ofMillis(15));

        assertThat(reaped).isEqualTo(1);
        assertThat(Files.exists(stale.root())).isFalse();
        assertThat(Files.exists(fresh.root())).isTrue();
        assertThat(manager.liveCount()).isEqualTo(1);
    }

    @Test
    void requireRefreshesIdleClockSoActiveSessionsSurvive() throws InterruptedException {
        SandboxRepo r = manager.create();
        Thread.sleep(30);
        manager.require(r.sessionId()); // 一次访问即续命

        assertThat(manager.reapIdle(Duration.ofMillis(25))).isZero();
        assertThat(Files.exists(r.root())).isTrue();
    }

    @Test
    void ledgerManagedSandboxIsExemptFromIdleReaping() throws InterruptedException {
        // 房间成员的克隆挂了 sandbox_repos 台账，生死由 expires_at（随活动滑动）决定。
        // 若内存侧的固定空闲阈值也来插一手，会抢先删掉目录、连带用户还没 push 的提交。
        SandboxRepo clone = manager.create();
        manager.markLedgerManaged(clone.sessionId());
        SandboxRepo anonymous = manager.create();
        Thread.sleep(30);

        int reaped = manager.reapIdle(Duration.ofMillis(10));

        assertThat(reaped).isEqualTo(1);
        assertThat(Files.exists(clone.root())).isTrue();
        assertThat(Files.exists(anonymous.root())).isFalse();
    }

    @Test
    void deleteByKeyStillRemovesLedgerManagedSandbox() {
        // 豁免只针对"空闲回收"，台账回收作业（走 deleteByKey）仍须能删掉它
        SandboxRepo clone = manager.create();
        manager.markLedgerManaged(clone.sessionId());

        manager.deleteByKey(clone.sessionId());

        assertThat(Files.exists(clone.root())).isFalse();
    }
}
