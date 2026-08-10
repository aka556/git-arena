package org.xiaoyu.gitarena.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.HintDtos;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.domain.entity.LevelHintEntity;
import org.xiaoyu.gitarena.domain.entity.ScoreEventEntity;
import org.xiaoyu.gitarena.domain.entity.User;
import org.xiaoyu.gitarena.domain.entity.UserHintUsageEntity;
import org.xiaoyu.gitarena.domain.entity.UserLevelProgress;
import org.xiaoyu.gitarena.mapper.LevelHintMapper;
import org.xiaoyu.gitarena.mapper.ScoreEventMapper;
import org.xiaoyu.gitarena.mapper.UserHintUsageMapper;
import org.xiaoyu.gitarena.mapper.UserLevelProgressMapper;
import org.xiaoyu.gitarena.mapper.UserMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 积分、提示扣分与成就的落库闭环测试。 */
@SpringBootTest
@Transactional
class EngagementIntegrationTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private ProgressService progressService;
    @Autowired
    private HintService hintService;
    @Autowired
    private AchievementService achievementService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ScoreEventMapper scoreEventMapper;
    @Autowired
    private LevelHintMapper levelHintMapper;
    @Autowired
    private UserHintUsageMapper usageMapper;
    @Autowired
    private UserLevelProgressMapper progressMapper;
    @Autowired
    private LevelRegistry levelRegistry;

    @Test
    void first_level_completion_awards_points_only_once() {
        Long userId = register().user().id();

        progressService.record(userId, "first-commit", true);
        progressService.record(userId, "first-commit", true);

        assertThat(scoreService.me(userId).totalPoints()).isEqualTo(20);
        assertThat(scoreEventMapper.selectCount(new LambdaQueryWrapper<ScoreEventEntity>()
                .eq(ScoreEventEntity::getUserId, userId)
                .eq(ScoreEventEntity::getSourceType, "level_complete")
                .eq(ScoreEventEntity::getSourceRef, "first-commit"))).isEqualTo(1);
        assertThat(scoreEventMapper.selectCount(new LambdaQueryWrapper<ScoreEventEntity>()
                .eq(ScoreEventEntity::getUserId, userId)
                .eq(ScoreEventEntity::getSourceType, "achievement")
                .eq(ScoreEventEntity::getSourceRef, "first_level"))).isEqualTo(1);
    }

    @Test
    void hint_use_is_recorded_and_duplicate_use_is_rejected() {
        Long userId = register().user().id();
        Long levelId = levelRegistry.idOf("resolve-conflict");
        LevelHintEntity hint = levelHintMapper.selectList(new LambdaQueryWrapper<LevelHintEntity>()
                        .eq(LevelHintEntity::getLevelId, levelId)
                        .gt(LevelHintEntity::getCostPoints, 0)
                        .orderByAsc(LevelHintEntity::getOrderIndex))
                .stream().findFirst().orElseThrow();

        HintDtos.UseResponse response = hintService.use(userId, "resolve-conflict", hint.getId());

        // 使用提示不扣分：只记使用，不产生 hint_penalty 流水
        assertThat(response.pointsCharged()).isZero();
        assertThat(response.hintsUsed()).isEqualTo(1);
        assertThat(scoreService.me(userId).totalPoints()).isZero();
        assertThat(usageMapper.selectCount(new LambdaQueryWrapper<UserHintUsageEntity>()
                .eq(UserHintUsageEntity::getUserId, userId)
                .eq(UserHintUsageEntity::getHintId, hint.getId()))).isEqualTo(1);
        UserLevelProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<UserLevelProgress>()
                .eq(UserLevelProgress::getUserId, userId)
                .eq(UserLevelProgress::getLevelId, levelId));
        assertThat(progress.getHintsUsed()).isEqualTo(1);
        assertThatThrownBy(() -> hintService.use(userId, "resolve-conflict", hint.getId()))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("已经使用");
    }

    @Test
    void achievement_unlock_is_idempotent_and_awards_definition_points() {
        Long userId = register().user().id();

        assertThat(achievementService.unlock(userId, "first_commit", Map.of("test", true))).isTrue();
        assertThat(achievementService.unlock(userId, "first_commit", Map.of("test", true))).isFalse();

        assertThat(scoreService.me(userId).totalPoints()).isEqualTo(5);
        assertThat(achievementService.mine(userId))
                .filteredOn(view -> view.code().equals("first_commit"))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.unlocked()).isTrue();
                    assertThat(view.points()).isEqualTo(5);
                });
    }

    @Test
    void leaderboard_orders_by_cached_total_points() {
        Long lowerId = register().user().id();
        Long higherId = register().user().id();
        scoreService.award(lowerId, "manual", "test-lower", 3);
        scoreService.award(higherId, "manual", "test-higher", 8);

        ScoreDtos.Board board = scoreService.leaderboard(ScoreDtos.PERIOD_ALL);

        assertThat(board.metric()).isEqualTo(ScoreDtos.METRIC_TOTAL);
        assertThat(board.entries()).extracting(entry -> entry.userId())
                .containsSubsequence(higherId, lowerId);
    }

    private AuthDtos.AuthResponse register() {
        String username = "engage-" + UUID.randomUUID().toString().substring(0, 8);
        return authService.register(new AuthDtos.Register(username, "secret123", null, null));
    }
}
