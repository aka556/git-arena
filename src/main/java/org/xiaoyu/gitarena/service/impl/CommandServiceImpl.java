package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.entity.User;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.CommitIdentity;
import org.xiaoyu.gitarena.git.ExecOutput;
import org.xiaoyu.gitarena.git.GitExecutor;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.mapper.UserMapper;
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
    private final UserMapper userMapper;

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
        ExecOutput output = gitExecutor.execute(sandbox, parsed, resolveIdentity(parsed));
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
        return new CommandResponse(output.ok(), output.stdout(), output.stderr(), graph, sandbox.displayCurrentDirectory());
    }

    /** 登录用户以真实用户名/邮箱提交（git log 才能辨认是谁）；匿名或非 git 命令用缺省身份。 */
    private CommitIdentity resolveIdentity(ParsedCommand parsed) {
        if (!parsed.isGit()) {
            return CommitIdentity.PLAYER;
        }
        Long userId = CurrentUser.id();
        if (userId == null) {
            return CommitIdentity.PLAYER;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return CommitIdentity.PLAYER;
        }
        return CommitIdentity.of(user.getUsername(), user.getEmail());
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
