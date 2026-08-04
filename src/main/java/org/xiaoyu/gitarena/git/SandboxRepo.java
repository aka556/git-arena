package org.xiaoyu.gitarena.git;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单个隔离沙盒仓库的句柄：会话标识 + 独立工作目录。
 * <p>仓库真相在文件系统（database.md §1）；本对象只持有路径与元信息，不缓存 git 状态。
 */
public final class SandboxRepo {

    private final String sessionId;
    private final Path root;

    public SandboxRepo(String sessionId, Path root) {
        this.sessionId = sessionId;
        this.root = root;
    }

    public String sessionId() {
        return sessionId;
    }

    public Path root() {
        return root;
    }

    /**
     * 该会话模拟远程（origin 裸仓库）的目录：与工作区平级的兄弟目录，
     * 避免被工作树跟踪、也不暴露给用户命令的路径空间（PathGuard 只放行 root 内）。
     */
    public Path originPath() {
        return root.resolveSibling(root.getFileName() + ".origin.git");
    }

    /** 是否已 {@code git init}（存在 .git 目录）。 */
    public boolean isInitialized() {
        return Files.isDirectory(root.resolve(".git"));
    }
}
