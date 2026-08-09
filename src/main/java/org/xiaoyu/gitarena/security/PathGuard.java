package org.xiaoyu.gitarena.security;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 路径守卫（CLAUDE.md §7.4 沙盒隔离）。把用户提供的相对路径规范化并强制限定在沙盒根内，
 * 杜绝 {@code ../} 越权、绝对路径逃逸，并禁止直接操作 {@code .git} 目录。
 */
@Component
public class PathGuard {

    /**
     * 校验并解析用户路径。
     *
     * @param sandboxRoot 沙盒根目录（绝对）
     * @param userPath    用户输入的相对路径
     * @return 规范化后、确保位于沙盒内的绝对路径
     * @throws CommandException 路径非法或越界
     */
    public Path resolveInside(Path sandboxRoot, String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new CommandException("路径为空");
        }
        String normalizedInput = userPath.replace('\\', '/');

        // 拒绝绝对路径：POSIX 前导 / 或 Windows 盘符 X:
        if (normalizedInput.startsWith("/") || normalizedInput.matches("^[A-Za-z]:.*")) {
            throw new CommandException("不允许绝对路径：" + userPath);
        }

        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(normalizedInput).normalize();
        } catch (InvalidPathException e) {
            throw new CommandException("非法路径：" + userPath);
        }

        return validate(root, resolved, userPath, false);
    }

    /**
     * 解析安全终端中的虚拟路径。终端把沙盒根显示为 {@code ~}，因此 {@code /foo} 与 {@code ~/foo}
     * 都映射到沙盒内，而不是宿主机绝对路径；普通相对路径从当前目录解析。
     */
    public Path resolveShellPath(Path sandboxRoot, Path currentDirectory, String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new CommandException("路径为空");
        }
        String normalizedInput = userPath.replace('\\', '/');
        if (normalizedInput.matches("^[A-Za-z]:.*")) {
            throw new CommandException("不允许宿主机绝对路径：" + userPath);
        }

        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path cwd = currentDirectory.toAbsolutePath().normalize();
        if (!cwd.startsWith(root)) {
            throw new CommandException("当前目录已失效，请返回仓库根目录");
        }

        Path resolved;
        try {
            if ("~".equals(normalizedInput) || "/".equals(normalizedInput)) {
                resolved = root;
            } else if (normalizedInput.startsWith("~/")) {
                resolved = root.resolve(normalizedInput.substring(2)).normalize();
            } else if (normalizedInput.startsWith("/")) {
                resolved = root.resolve(normalizedInput.substring(1)).normalize();
            } else {
                resolved = cwd.resolve(normalizedInput).normalize();
            }
        } catch (InvalidPathException e) {
            throw new CommandException("非法路径：" + userPath);
        }

        return validate(root, resolved, userPath, true);
    }

    private Path validate(Path root, Path resolved, String userPath, boolean allowRoot) {

        if (!resolved.startsWith(root)) {
            throw new CommandException("路径越界：" + userPath);
        }
        if (!allowRoot && resolved.equals(root)) {
            throw new CommandException("路径不能是仓库根本身：" + userPath);
        }

        // 禁止穿入 .git 目录（含子路径）
        Path relative = root.relativize(resolved);
        for (Path segment : relative) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                throw new CommandException("不允许操作 .git 目录");
            }
        }
        rejectSymbolicLinks(root, resolved, userPath);
        return resolved;
    }

    /** 任何已存在的路径段只要是符号链接就拒绝，防止链接跳出沙盒或绕入 .git。 */
    private void rejectSymbolicLinks(Path root, Path resolved, String userPath) {
        Path cursor = root;
        for (Path segment : root.relativize(resolved)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new CommandException("不允许访问符号链接：" + userPath);
            }
        }
    }
}
