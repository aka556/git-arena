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
    private Path currentDirectory;

    public SandboxRepo(String sessionId, Path root) {
        this.sessionId = sessionId;
        this.root = root.toAbsolutePath().normalize();
        this.currentDirectory = this.root;
    }

    public String sessionId() {
        return sessionId;
    }

    public Path root() {
        return root;
    }

    /** 安全终端当前目录；仅保存沙盒内路径，不影响 GitGraph 快照真相。 */
    public synchronized Path currentDirectory() {
        return currentDirectory;
    }

    public synchronized void changeDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("terminal cwd must stay inside sandbox");
        }
        currentDirectory = normalized;
    }

    public synchronized void resetCurrentDirectory() {
        currentDirectory = root;
    }

    /** 面向终端显示的虚拟路径，不暴露宿主机临时目录。 */
    public synchronized String displayCurrentDirectory() {
        if (currentDirectory.equals(root)) {
            return "~";
        }
        return "~/" + root.relativize(currentDirectory).toString().replace('\\', '/');
    }

    /**
     * 该会话模拟远程（origin 裸仓库）的目录：与工作区平级的兄弟目录，
     * 避免被工作树跟踪、也不暴露给用户命令的路径空间（PathGuard 只放行 root 内）。
     */
    public Path originPath() {
        return root.resolveSibling(root.getFileName() + ".origin.git");
    }

    /**
     * 任意命名远程的裸仓库目录（fork 工作流的 upstream 等）；origin 沿用既有布局。
     * 远程名来自关卡 spec（已过引用名校验），此处再收一道防目录逃逸。
     */
    public Path remotePath(String name) {
        if ("origin".equals(name)) {
            return originPath();
        }
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("illegal remote name: " + name);
        }
        return root.resolveSibling(root.getFileName() + ".remote-" + name + ".git");
    }

    /** 是否已 {@code git init}（存在 .git 目录）。 */
    public boolean isInitialized() {
        return Files.isDirectory(root.resolve(".git"));
    }
}
