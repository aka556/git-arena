package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.dto.LevelDraftDtos;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.security.CommandException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 关卡编辑器闭环（M4，docs/level-spec.md §7 质量门）。
 *
 * <p>重点覆盖<b>发布闸门</b>：零步就能通关、参考解走不通、缺参考解——三类坏关卡都必须挡在发布之外，
 * 好关卡发布后要能被 {@link LevelSource} 与官方关卡一起列出（走同一套构建/校验链路）。
 *
 * <p>不加 {@code @Transactional}：自证会真实建沙盒（文件系统副作用），且发布后要跨 service 读回。
 */
@SpringBootTest
class LevelDraftIntegrationTest {

    @Autowired
    private LevelDraftService levelDraftService;
    @Autowired
    private LevelSource levelSource;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<String> createdSlugs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSlugs.forEach(slug -> jdbc.update("DELETE FROM levels WHERE slug = ?", slug));
        createdSlugs.clear();
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        createdUserIds.clear();
    }

    @Test
    void good_level_passes_self_check_publishes_and_becomes_playable() {
        Long userId = guest();
        String slug = slug();

        levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, goodLevel(slug)));

        LevelDraftDtos.SelfCheckResult check = levelDraftService.selfCheck(userId, slug);
        assertThat(check.ok()).as("自证问题：%s", check.problems()).isTrue();
        assertThat(check.zeroStepFails()).isTrue();
        assertThat(check.solutionPasses()).isTrue();

        levelDraftService.publish(userId, slug);

        // 发布后与官方关卡同列，且能被引擎按 slug 取出来构建
        assertThat(levelSource.list()).extracting(l -> l.meta().slug()).contains(slug);
        assertThat(levelSource.get(slug).meta().title()).isEqualTo("我的关卡");
        assertThat(levelDraftService.listMine(userId))
                .extracting(LevelDraftDtos.DraftSummary::status).containsExactly("published");
    }

    @Test
    void level_that_is_already_solved_at_start_cannot_publish() {
        Long userId = guest();
        String slug = slug();
        // goal 与 initial 完全一致 —— 玩家一进门就赢，属典型配置错误
        LevelFile level = new LevelFile(1, meta(slug),
                new LevelFile.InitialSpec(
                        List.of(new LevelFile.Commit("C1", List.of(), "base", null,
                                Map.of("a.txt", "a\n"))),
                        List.of(new LevelFile.Ref("main", "C1")), null,
                        new LevelFile.Head("branch", "main"), null, null),
                new LevelFile.GoalSpec(
                        new LevelFile.GoalGraph(
                                List.of(new LevelFile.Commit("C1", List.of(), null, null, null)),
                                List.of(new LevelFile.Ref("main", "C1")), null,
                                new LevelFile.Head("branch", "main"), null, null),
                        null, null),
                new LevelFile.SolutionSpec(List.of(new LevelFile.SolutionStep("git status", null)), null),
                List.of());
        levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, level));

        LevelDraftDtos.SelfCheckResult check = levelDraftService.selfCheck(userId, slug);
        assertThat(check.ok()).isFalse();
        assertThat(check.zeroStepFails()).isFalse();
        assertThat(String.join(" ", check.problems())).contains("一进门就赢");

        assertThatThrownBy(() -> levelDraftService.publish(userId, slug))
                .isInstanceOf(LevelException.class);
        assertThat(levelSource.list()).extracting(l -> l.meta().slug()).doesNotContain(slug);
    }

    @Test
    void level_without_working_solution_cannot_publish() {
        Long userId = guest();
        String slug = slug();
        LevelFile level = goodLevel(slug);
        // 参考解换成一条不产生提交的命令：重放后仍达不成目标
        LevelFile broken = new LevelFile(1, level.meta(), level.initial(), level.goal(),
                new LevelFile.SolutionSpec(List.of(new LevelFile.SolutionStep("git status", null)), null),
                level.hints());
        levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, broken));

        LevelDraftDtos.SelfCheckResult check = levelDraftService.selfCheck(userId, slug);
        assertThat(check.ok()).isFalse();
        assertThat(check.zeroStepFails()).isTrue();      // 零步这关是过的
        assertThat(check.solutionPasses()).isFalse();    // 卡在参考解
        assertThatThrownBy(() -> levelDraftService.publish(userId, slug))
                .isInstanceOf(LevelException.class);
    }

    @Test
    void level_missing_solution_cannot_publish() {
        Long userId = guest();
        String slug = slug();
        LevelFile level = goodLevel(slug);
        LevelFile noSolution = new LevelFile(1, level.meta(), level.initial(), level.goal(), null, List.of());
        levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, noSolution));

        LevelDraftDtos.SelfCheckResult check = levelDraftService.selfCheck(userId, slug);
        assertThat(check.ok()).isFalse();
        assertThat(String.join(" ", check.problems())).contains("参考解");
    }

    @Test
    void invalid_spec_is_rejected_at_save_time() {
        Long userId = guest();
        String slug = slug();
        // C2 的父 C9 不存在 —— 语义校验（fail-closed）应在保存阶段就拦下
        LevelFile level = new LevelFile(1, meta(slug),
                new LevelFile.InitialSpec(
                        List.of(new LevelFile.Commit("C1", List.of(), null, null, null),
                                new LevelFile.Commit("C2", List.of("C9"), null, null, null)),
                        List.of(new LevelFile.Ref("main", "C2")), null,
                        new LevelFile.Head("branch", "main"), null, null),
                new LevelFile.GoalSpec(
                        new LevelFile.GoalGraph(
                                List.of(new LevelFile.Commit("C1", List.of(), null, null, null)),
                                List.of(new LevelFile.Ref("main", "C1")), null,
                                new LevelFile.Head("branch", "main"), null, null),
                        null, null),
                null, List.of());

        assertThatThrownBy(() -> levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, level)))
                .isInstanceOf(LevelException.class);
    }

    @Test
    void official_slug_cannot_be_hijacked_and_others_levels_are_not_editable() {
        Long author = guest();
        Long stranger = guest();
        String slug = slug();

        // 官方 slug 不许被自定义关卡顶替
        assertThatThrownBy(() -> levelDraftService.save(author,
                new LevelDraftDtos.SaveRequest("first-commit", goodLevel("first-commit"))))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("官方关卡");

        levelDraftService.save(author, new LevelDraftDtos.SaveRequest(slug, goodLevel(slug)));
        // 他人不能读写我的草稿
        assertThatThrownBy(() -> levelDraftService.get(stranger, slug))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("只能编辑自己");
        assertThatThrownBy(() -> levelDraftService.publish(stranger, slug))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void unpublish_removes_level_from_the_public_catalog() {
        Long userId = guest();
        String slug = slug();
        levelDraftService.save(userId, new LevelDraftDtos.SaveRequest(slug, goodLevel(slug)));
        levelDraftService.publish(userId, slug);
        assertThat(levelSource.list()).extracting(l -> l.meta().slug()).contains(slug);

        levelDraftService.unpublish(userId, slug);
        assertThat(levelSource.list()).extracting(l -> l.meta().slug()).doesNotContain(slug);
    }

    /** 一个真正合格的关卡：空仓库起步，add + commit 通关。 */
    private LevelFile goodLevel(String slug) {
        return new LevelFile(1, meta(slug),
                new LevelFile.InitialSpec(
                        List.of(), List.of(), null,
                        new LevelFile.Head("branch", "main"), null,
                        new LevelFile.InitialWorkingDir(Map.of("hello.txt", "hi\n"), List.of())),
                new LevelFile.GoalSpec(
                        new LevelFile.GoalGraph(
                                List.of(new LevelFile.Commit("C1", List.of(), null, null, null)),
                                List.of(new LevelFile.Ref("main", "C1")), null,
                                new LevelFile.Head("branch", "main"), null, null),
                        null, null),
                new LevelFile.SolutionSpec(List.of(
                        new LevelFile.SolutionStep("git add hello.txt", null),
                        new LevelFile.SolutionStep("git commit -m \"init\"", null)), null),
                List.of());
    }

    private LevelFile.Meta meta(String slug) {
        return new LevelFile.Meta(slug, "我的关卡", "说明", "basics", 1, "solo", 1, "public");
    }

    private String slug() {
        String slug = "test-" + UUID.randomUUID().toString().substring(0, 8);
        createdSlugs.add(slug);
        return slug;
    }

    private Long guest() {
        AuthDtos.AuthResponse response = authService.guest();
        createdUserIds.add(response.user().id());
        return response.user().id();
    }
}
