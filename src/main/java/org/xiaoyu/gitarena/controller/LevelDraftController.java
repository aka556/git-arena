package org.xiaoyu.gitarena.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.LevelDraftDtos;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.LevelDraftService;

import java.util.List;

/**
 * 关卡编辑器入口（薄 controller，§7）：我的关卡 CRUD + 自证试跑 + 发布/下架。
 * 一律以登录用户鉴权，只能操作自己创作的关卡（service 内复查）。
 */
@RestController
@RequestMapping("/api/level-drafts")
@RequiredArgsConstructor
public class LevelDraftController {

    private final LevelDraftService levelDraftService;

    @GetMapping
    public Result<List<LevelDraftDtos.DraftSummary>> listMine() {
        return Result.ok(levelDraftService.listMine(CurrentUser.require()));
    }

    @GetMapping("/{slug}")
    public Result<LevelDraftDtos.DraftDetail> get(@PathVariable String slug) {
        return Result.ok(levelDraftService.get(CurrentUser.require(), slug));
    }

    @PutMapping("/{slug}")
    public Result<LevelDraftDtos.DraftDetail> save(@PathVariable String slug,
                                                   @Valid @RequestBody LevelDraftDtos.SaveRequest request) {
        // 以路径 slug 为准，避免 body 与 URL 不一致
        LevelDraftDtos.SaveRequest normalized =
                new LevelDraftDtos.SaveRequest(slug, request.level());
        return Result.ok(levelDraftService.save(CurrentUser.require(), normalized));
    }

    /** 试跑自证闭环，不改状态。 */
    @PostMapping("/{slug}/self-check")
    public Result<LevelDraftDtos.SelfCheckResult> selfCheck(@PathVariable String slug) {
        return Result.ok(levelDraftService.selfCheck(CurrentUser.require(), slug));
    }

    @PostMapping("/{slug}/publish")
    public Result<LevelDraftDtos.SelfCheckResult> publish(@PathVariable String slug) {
        return Result.ok(levelDraftService.publish(CurrentUser.require(), slug));
    }

    @PostMapping("/{slug}/unpublish")
    public Result<Void> unpublish(@PathVariable String slug) {
        levelDraftService.unpublish(CurrentUser.require(), slug);
        return Result.ok(null);
    }

    @DeleteMapping("/{slug}")
    public Result<Void> delete(@PathVariable String slug) {
        levelDraftService.delete(CurrentUser.require(), slug);
        return Result.ok(null);
    }
}
