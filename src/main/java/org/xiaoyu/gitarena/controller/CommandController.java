package org.xiaoyu.gitarena.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.CommandRequest;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.service.CommandService;

/**
 * 命令执行入口（薄 controller，§7）。终端输入与图形面板动作都 POST 到这里，走同一链路（§3）。
 */
@RestController
@RequestMapping("/api/command")
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;

    @PostMapping
    public Result<CommandResponse> execute(@Valid @RequestBody CommandRequest request) {
        return Result.ok(commandService.execute(request.sessionId(), request.command()));
    }
}
