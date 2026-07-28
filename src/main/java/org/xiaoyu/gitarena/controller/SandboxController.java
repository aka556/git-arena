package org.xiaoyu.gitarena.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.SessionResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.service.SandboxService;

/**
 * 沙盒会话入口（薄 controller，§7）：创建 / 重置 / 读图。
 */
@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping
    public Result<SessionResponse> create() {
        return Result.ok(sandboxService.create());
    }

    @PostMapping("/{sessionId}/reset")
    public Result<SessionResponse> reset(@PathVariable String sessionId) {
        return Result.ok(sandboxService.reset(sessionId));
    }

    @GetMapping("/{sessionId}/graph")
    public Result<GitGraph> graph(@PathVariable String sessionId) {
        return Result.ok(sandboxService.graph(sessionId));
    }
}
