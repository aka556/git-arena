package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.ExecOutput;
import org.xiaoyu.gitarena.git.GitExecutor;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.service.CommandService;
import org.xiaoyu.gitarena.service.GraphService;

/**
 * 命令执行链路（CLAUDE.md §3）：定位沙盒 → 解析+白名单校验 → JGit 执行 → 读回同一份图快照。
 * 执行后返回的 {@link GitGraph} 同时驱动前端图视图与终端视图，不产生第二份状态。
 */
@Service
@RequiredArgsConstructor
public class CommandServiceImpl implements CommandService {

    private final SandboxManager sandboxManager;
    private final CommandParser commandParser;
    private final GitExecutor gitExecutor;
    private final GraphService graphService;

    @Override
    public CommandResponse execute(String sessionId, String rawCommand) {
        SandboxRepo sandbox = sandboxManager.require(sessionId);
        ParsedCommand parsed = commandParser.parse(rawCommand);
        ExecOutput output = gitExecutor.execute(sandbox, parsed);
        GitGraph graph = graphService.readGraph(sandbox);
        return new CommandResponse(output.ok(), output.stdout(), output.stderr(), graph);
    }
}
