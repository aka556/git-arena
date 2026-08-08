package org.xiaoyu.gitarena.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.AchievementDtos;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.AchievementService;

import java.util.List;

/** 成就查询入口。 */
@RestController
@RequestMapping("/api/achievements")
@RequiredArgsConstructor
public class AchievementController {

    private final AchievementService achievementService;

    @GetMapping("/me")
    public Result<List<AchievementDtos.View>> me() {
        return Result.ok(achievementService.mine(CurrentUser.require()));
    }
}
