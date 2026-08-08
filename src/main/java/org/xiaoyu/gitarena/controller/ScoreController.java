package org.xiaoyu.gitarena.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.ScoreService;

import java.util.List;

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

    @GetMapping("/leaderboard")
    public Result<List<ScoreDtos.LeaderboardEntry>> leaderboard() {
        return Result.ok(scoreService.leaderboard());
    }
}
