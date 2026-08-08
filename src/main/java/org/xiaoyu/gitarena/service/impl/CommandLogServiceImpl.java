package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.entity.CommandLogEntity;
import org.xiaoyu.gitarena.domain.entity.SandboxRepoEntity;
import org.xiaoyu.gitarena.mapper.CommandLogMapper;
import org.xiaoyu.gitarena.mapper.SandboxRepoMapper;
import org.xiaoyu.gitarena.service.CommandLogService;

import java.time.OffsetDateTime;

/** 命令审计实现：异步、截断错误输出，审计故障静默记录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandLogServiceImpl implements CommandLogService {

    private final CommandLogMapper commandLogMapper;
    private final SandboxRepoMapper sandboxRepoMapper;

    @Override
    @Async
    public void log(Long userId, String sessionId, String rawInput, String parsedCommand,
                    boolean allowed, boolean success, String stderr, long durationMs) {
        try {
            SandboxRepoEntity sandbox = sandboxRepoMapper.selectOne(new LambdaQueryWrapper<SandboxRepoEntity>()
                    .eq(SandboxRepoEntity::getSandboxKey, sessionId));
            CommandLogEntity row = new CommandLogEntity();
            row.setUserId(userId);
            row.setSandboxId(sandbox == null ? null : sandbox.getId());
            row.setRoomId(sandbox == null ? null : sandbox.getRoomId());
            row.setRawInput(rawInput);
            row.setParsedCommand(parsedCommand);
            row.setAllowed(allowed);
            row.setSuccess(success);
            row.setStderrExcerpt(excerpt(stderr));
            row.setDurationMs((int) Math.min(Math.max(durationMs, 0), Integer.MAX_VALUE));
            row.setCreatedAt(OffsetDateTime.now());
            commandLogMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("写入命令审计失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private String excerpt(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return null;
        }
        return stderr.length() <= 500 ? stderr : stderr.substring(0, 500);
    }
}
