package org.xiaoyu.gitarena.security;

/**
 * 用户命令导致的可预期错误（非法命令、越权路径、无仓库等）。
 * <p>消息面向终端用户展示，须友好、不泄漏内部路径（CLAUDE.md §7）。
 */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }
}
