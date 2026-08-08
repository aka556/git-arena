package org.xiaoyu.gitarena.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.ScoreService;

/** 积分与排行榜入口。 */
@RestController
@RequestMapping("/api/score")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scoreService;

    @GetMapping("/me")
    public Result<ScoreDtos.Me> me() {
        return Result.ok(scoreService.me(CurrentUser.require()));
    }

    /**
     * 排行榜。{@code period} 取 all（默认，累计总分）/ weekly / monthly（窗口内新增分，database.md §5.7）；
     * 口径不同，响应带 metric 供前端标注。榜单对匿名开放，不需登录。
     */
    @GetMapping("/leaderboard")
    public Result<ScoreDtos.Board> leaderboard(
            @RequestParam(defaultValue = ScoreDtos.PERIOD_ALL) String period) {
        return Result.ok(scoreService.leaderboard(period));
    }
}
