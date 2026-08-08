package org.xiaoyu.gitarena.service;

/** 异步命令审计，不得影响命令执行结果。 */
public interface CommandLogService {

    void log(Long userId, String sessionId, String rawInput, String parsedCommand,
             boolean allowed, boolean success, String stderr, long durationMs);
}
