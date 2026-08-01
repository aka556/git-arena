package org.xiaoyu.gitarena.security;

import java.util.Set;

/**
 * 命令白名单（CLAUDE.md §7 最高优先级安全约束）。
 * <p>只放行教学所需的 git 子集与少量非 git 辅助命令；白名单之外一律拒绝。
 * M1 覆盖 {@code init/add/commit/log/status}；M2 增开 {@code branch/checkout/switch/merge/tag/rebase}（§11 路线图）。
 */
public final class CommandWhitelist {

    /** 已支持的 git 子命令。后续里程碑在此增量开放（§11 路线图）。 */
    public static final Set<String> GIT_SUBCOMMANDS = Set.of(
            "init", "add", "commit", "log", "status",
            "branch", "checkout", "switch", "merge", "tag", "rebase"
    );

    /**
     * 非 git 辅助命令：git 本身没有"写文件"的命令，教学中用它们创建/写入文件
     * （对齐 docs/level-spec.md §6 的 writeFile 语义）。执行以纯 Java Files API 实现，
     * 不经 shell、不拼进程（§7）。
     */
    public static final Set<String> HELPERS = Set.of("touch", "echo");

    private CommandWhitelist() {
    }

    public static boolean isGitSubcommand(String sub) {
        return GIT_SUBCOMMANDS.contains(sub);
    }

    public static boolean isHelper(String program) {
        return HELPERS.contains(program);
    }
}
