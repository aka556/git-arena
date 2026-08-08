package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.ExecOutput;
import org.xiaoyu.gitarena.git.GitExecutor;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.service.AchievementService;
import org.xiaoyu.gitarena.service.CommandLogService;
import org.xiaoyu.gitarena.service.CommandService;
import org.xiaoyu.gitarena.service.GraphService;

/**
 * 命令执行链路（CLAUDE.md §3）：定位沙盒 → 解析+白名单校验 → JGit 执行 → 读回同一份图快照。
 * 执行后返回的 {@link GitGraph} 同时驱动前端图视图与终端视图，不产生第二份状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommandServiceImpl implements CommandService {

    private final SandboxManager sandboxManager;
    private final CommandParser commandParser;
    private final GitExecutor gitExecutor;
    private final GraphService graphService;
    private final AchievementService achievementService;
    private final CommandLogService commandLogService;

    @Override
    public CommandResponse execute(String sessionId, String rawCommand) {
        long startedAt = System.nanoTime();
        SandboxRepo sandbox = sandboxManager.require(sessionId);
        ParsedCommand parsed;
        try {
            parsed = commandParser.parse(rawCommand);
        } catch (RuntimeException e) {
            commandLogService.log(CurrentUser.id(), sessionId, rawCommand, null, false, false,
                    e.getMessage(), elapsedMs(startedAt));
            throw e;
        }
        ExecOutput output = gitExecutor.execute(sandbox, parsed);
        GitGraph graph = graphService.readGraph(sandbox);
        if (output.ok() && parsed.isGit() && "commit".equals(parsed.subcommand())) {
            try {
                achievementService.onCommit(CurrentUser.id());
            } catch (RuntimeException e) {
                log.warn("提交成功但成就解锁失败 user={}: {}", CurrentUser.id(), e.getMessage());
            }
        }
        commandLogService.log(CurrentUser.id(), sessionId, rawCommand,
                parsed.isGit() ? parsed.subcommand() : parsed.program(),
                true, output.ok(), output.stderr(), elapsedMs(startedAt));
        return new CommandResponse(output.ok(), output.stdout(), output.stderr(), graph);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
