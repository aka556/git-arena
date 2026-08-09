package org.xiaoyu.gitarena.security;

import java.util.List;

/**
 * 解析后的命令。program 为 {@code git} 或安全终端内建命令；
 * 仅当 program 为 git 时 subcommand 非空。args 为剩余参数（已按引号切分，未经 shell 解释）。
 */
public record ParsedCommand(String program, String subcommand, List<String> args, String raw) {

    public boolean isGit() {
        return "git".equals(program);
    }
}
