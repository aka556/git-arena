package org.xiaoyu.gitarena.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.ProgressView;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.ProgressService;

import java.util.List;

/**
 * 关卡进度入口（薄 controller，§7）：我的进度。需登录（CurrentUser.require）。
 */
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping
    public Result<List<ProgressView>> mine() {
        return Result.ok(progressService.myProgress(CurrentUser.require()));
    }
}
