package org.xiaoyu.gitarena.git;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒仓库管理（P0：为每个会话创建隔离临时仓库；支持重置）。
 *
 * <p><b>M1 决策</b>：登记仅在内存（{@link ConcurrentHashMap}），不写数据库——沙盒台账属 P1 用户体系
 * （database.md §3.2）。重启即丢，M1 可接受。会话结束/应用关闭清理临时目录（CLAUDE.md §7.7）。
 */
@Slf4j
@Component
public class SandboxManager {

    private final Path baseDir;
    private final Map<String, SandboxRepo> repos = new ConcurrentHashMap<>();

    public SandboxManager() {
        try {
            this.baseDir = Files.createDirectories(
                    Path.of(System.getProperty("java.io.tmpdir"), "git-arena-sandboxes"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙盒根目录", e);
        }
    }

    /** 新建一个空工作目录（尚未 git init，交由用户执行 git init 学习首个命令）。 */
    public SandboxRepo create() {
        String sessionId = UUID.randomUUID().toString();
        Path dir = baseDir.resolve(sessionId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙盒目录", e);
        }
        SandboxRepo repo = new SandboxRepo(sessionId, dir);
        repos.put(sessionId, repo);
        log.debug("created sandbox {}", sessionId);
        return repo;
    }

    public SandboxRepo require(String sessionId) {
        SandboxRepo repo = repos.get(sessionId);
        if (repo == null) {
            throw new CommandException("会话不存在或已过期，请新建会话");
        }
        return repo;
    }

    /** 重置：清空并重建同一 sessionId 的工作目录（回到 git init 之前的空态；连同模拟远程一并清除）。 */
    public SandboxRepo reset(String sessionId) {
        SandboxRepo repo = require(sessionId);
        deleteQuietly(repo.root());
        deleteQuietly(repo.originPath());
        try {
            Files.createDirectories(repo.root());
        } catch (IOException e) {
            throw new IllegalStateException("无法重建沙盒目录", e);
        }
        return repo;
    }

    @PreDestroy
    void cleanupAll() {
        repos.values().forEach(r -> {
            deleteQuietly(r.root());
            deleteQuietly(r.originPath());
        });
        repos.clear();
    }

    /**
     * 递归删除目录。用 JGit 的 FileUtils（RETRY 应对 Windows 上 .pack 文件的短暂锁定），
     * IGNORE_ERRORS 保证清理不因个别文件失败而中断。
     */
    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            FileUtils.delete(dir.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.IGNORE_ERRORS);
        } catch (IOException e) {
            log.warn("failed to delete sandbox dir {}: {}", dir, e.getMessage());
        }
    }
}
