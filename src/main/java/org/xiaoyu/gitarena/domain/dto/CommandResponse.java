package org.xiaoyu.gitarena.domain.dto;

import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 命令执行结果：命令输出（stdout/stderr）+ 执行后的最新图快照。
 * <p>图与终端由同一份 {@link GitGraph} 刷新（CLAUDE.md §3）。
 */
public record CommandResponse(
        boolean ok,
        String stdout,
        String stderr,
        GitGraph graph,
        String cwd
) {}
