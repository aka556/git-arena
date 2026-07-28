package org.xiaoyu.gitarena.security;

import org.springframework.stereotype.Component;

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
        Path resolved = root.resolve(normalizedInput).normalize();

        if (!resolved.startsWith(root)) {
            throw new CommandException("路径越界：" + userPath);
        }
        if (resolved.equals(root)) {
            throw new CommandException("路径不能是仓库根本身：" + userPath);
        }

        // 禁止穿入 .git 目录（含子路径）
        Path relative = root.relativize(resolved);
        for (Path segment : relative) {
            if (".git".equals(segment.toString())) {
                throw new CommandException("不允许操作 .git 目录");
            }
        }
        return resolved;
    }
}
