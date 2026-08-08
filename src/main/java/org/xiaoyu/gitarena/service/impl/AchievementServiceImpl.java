package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.AchievementDtos;
import org.xiaoyu.gitarena.domain.entity.AchievementEntity;
import org.xiaoyu.gitarena.domain.entity.UserAchievementEntity;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.mapper.AchievementMapper;
import org.xiaoyu.gitarena.mapper.UserAchievementMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.AchievementService;
import org.xiaoyu.gitarena.service.LevelCatalog;
import org.xiaoyu.gitarena.service.ScoreService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 成就服务实现。定义由 AchievementSeeder 管理，用户解锁记录由唯一约束保证幂等。 */
@Service
@RequiredArgsConstructor
public class AchievementServiceImpl implements AchievementService {

    private final AchievementMapper achievementMapper;
    private final UserAchievementMapper userAchievementMapper;
    private final ObjectMapper objectMapper;
    private final ScoreService scoreService;
    private final LevelCatalog levelCatalog;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public boolean unlock(Long userId, String code, Map<String, Object> context) {
        if (userId == null) {
            return false;
        }
        AchievementEntity achievement = achievementMapper.selectOne(new LambdaQueryWrapper<AchievementEntity>()
                .eq(AchievementEntity::getCode, code)
                .eq(AchievementEntity::getIsActive, true));
        if (achievement == null) {
            return false;
        }
        UserAchievementEntity existing = userAchievementMapper.selectOne(new LambdaQueryWrapper<UserAchievementEntity>()
                .eq(UserAchievementEntity::getUserId, userId)
                .eq(UserAchievementEntity::getAchievementId, achievement.getId()));
        if (existing != null) {
            return false;
        }

        int inserted = jdbc.update("""
                INSERT INTO user_achievements (user_id, achievement_id, unlocked_at, context)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (user_id, achievement_id) DO NOTHING
                """, userId, achievement.getId(), OffsetDateTime.now(), json(context));
        if (inserted != 1) {
            return false;
        }
        scoreService.award(userId, "achievement", achievement.getCode(),
                achievement.getPoints() == null ? 0 : achievement.getPoints());
        return true;
    }

    @Override
    public void onCommit(Long userId) {
        unlock(userId, "first_commit", Map.of("event", "commit"));
    }

    @Override
    public void onLevelCompleted(Long userId, String slug) {
        Map<String, Object> context = Map.of("slug", slug);
        unlock(userId, "first_level", context);
        try {
            LevelFile level = levelCatalog.get(slug);
            if ("conflict".equals(level.meta().category())) {
                unlock(userId, "conflict_slayer", context);
            }
        } catch (RuntimeException ignored) {
            // 关卡已被下架时不影响通关记录与基础积分。
        }
    }

    @Override
    public void onPullRequestMerged(Long userId, String sourceRef) {
        unlock(userId, "first_merge", Map.of("sourceRef", sourceRef));
    }

    @Override
    public List<AchievementDtos.View> mine(Long userId) {
        if (userId == null) {
            throw new CommandException("请先登录");
        }
        Map<Long, UserAchievementEntity> unlocked = new HashMap<>();
        for (UserAchievementEntity row : userAchievementMapper.selectList(new LambdaQueryWrapper<UserAchievementEntity>()
                .eq(UserAchievementEntity::getUserId, userId))) {
            unlocked.put(row.getAchievementId(), row);
        }
        List<AchievementEntity> definitions = achievementMapper.selectList(new LambdaQueryWrapper<AchievementEntity>()
                .eq(AchievementEntity::getIsActive, true)
                .orderByAsc(AchievementEntity::getId));
        List<AchievementDtos.View> result = new ArrayList<>(definitions.size());
        for (AchievementEntity definition : definitions) {
            UserAchievementEntity row = unlocked.get(definition.getId());
            result.add(new AchievementDtos.View(
                    definition.getId(),
                    definition.getCode(),
                    definition.getName(),
                    definition.getDescription(),
                    definition.getIcon(),
                    definition.getPoints() == null ? 0 : definition.getPoints(),
                    definition.getCategory(),
                    row != null,
                    row == null ? null : row.getUnlockedAt()));
        }
        return result;
    }

    private String json(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("成就上下文序列化失败", e);
        }
    }
}
