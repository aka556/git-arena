package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 命令执行请求（终端输入与图形面板动作都封成此请求，走同一 API——CLAUDE.md §3 黄金法则）。
 */
public record CommandRequest(
        @NotBlank(message = "sessionId 不能为空") String sessionId,
        @NotBlank(message = "命令不能为空") @Size(max = 2000, message = "命令过长") String command
) {}
