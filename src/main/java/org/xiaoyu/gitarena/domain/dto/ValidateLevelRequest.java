package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** 关卡校验请求体：指明用哪个会话沙盒来比对目标。 */
public record ValidateLevelRequest(
        @NotBlank(message = "sessionId 不能为空") String sessionId
) {}
