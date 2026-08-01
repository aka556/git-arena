package org.xiaoyu.gitarena.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.LevelDetail;
import org.xiaoyu.gitarena.domain.dto.LevelSummary;
import org.xiaoyu.gitarena.domain.dto.StartLevelResponse;
import org.xiaoyu.gitarena.domain.dto.ValidateLevelRequest;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;
import org.xiaoyu.gitarena.service.LevelService;

import java.util.List;

/**
 * 关卡入口（薄 controller，§7）：列表 / 详情 / 开始 / 校验。
 */
@RestController
@RequestMapping("/api/levels")
@RequiredArgsConstructor
public class LevelController {

    private final LevelService levelService;

    @GetMapping
    public Result<List<LevelSummary>> list() {
        return Result.ok(levelService.list());
    }

    @GetMapping("/{slug}")
    public Result<LevelDetail> detail(@PathVariable String slug) {
        return Result.ok(levelService.detail(slug));
    }

    @PostMapping("/{slug}/start")
    public Result<StartLevelResponse> start(@PathVariable String slug) {
        return Result.ok(levelService.start(slug));
    }

    @PostMapping("/{slug}/validate")
    public Result<ValidateResponse> validate(@PathVariable String slug,
                                             @Valid @RequestBody ValidateLevelRequest request) {
        return Result.ok(levelService.validate(request.sessionId(), slug));
    }
}
