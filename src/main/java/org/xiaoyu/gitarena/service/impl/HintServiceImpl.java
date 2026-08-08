package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.HintDtos;
import org.xiaoyu.gitarena.domain.entity.LevelHintEntity;
import org.xiaoyu.gitarena.domain.entity.UserHintUsageEntity;
import org.xiaoyu.gitarena.domain.entity.UserLevelProgress;
import org.xiaoyu.gitarena.mapper.LevelHintMapper;
import org.xiaoyu.gitarena.mapper.UserHintUsageMapper;
import org.xiaoyu.gitarena.mapper.UserLevelProgressMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.HintService;
import org.xiaoyu.gitarena.service.LevelRegistry;
import org.xiaoyu.gitarena.service.ScoreService;

import java.time.OffsetDateTime;

/** 提示使用实现：唯一约束保证同一用户对同一提示只扣一次分。 */
@Service
@RequiredArgsConstructor
public class HintServiceImpl implements HintService {

    private final LevelRegistry levelRegistry;
    private final LevelHintMapper levelHintMapper;
    private final UserHintUsageMapper usageMapper;
    private final UserLevelProgressMapper progressMapper;
    private final ScoreService scoreService;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public HintDtos.UseResponse use(Long userId, String slug, Long hintId) {
        if (userId == null) {
            throw new CommandException("请先登录");
        }
        Long levelId = levelRegistry.idOf(slug);
        if (levelId == null) {
            throw new CommandException("关卡不存在：" + slug);
        }
        LevelHintEntity hint = levelHintMapper.selectOne(new LambdaQueryWrapper<LevelHintEntity>()
                .eq(LevelHintEntity::getId, hintId)
                .eq(LevelHintEntity::getLevelId, levelId));
        if (hint == null) {
            throw new CommandException("提示不存在或不属于该关卡");
        }
        if (usageMapper.selectOne(new LambdaQueryWrapper<UserHintUsageEntity>()
                .eq(UserHintUsageEntity::getUserId, userId)
                .eq(UserHintUsageEntity::getHintId, hintId)) != null) {
            throw new CommandException("这条提示已经使用过了");
        }

        int inserted = jdbc.update("""
                INSERT INTO user_hint_usage (user_id, level_id, hint_id, used_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, hint_id) DO NOTHING
                """, userId, levelId, hintId, OffsetDateTime.now());
        if (inserted != 1) {
            throw new CommandException("这条提示已经使用过了");
        }

        int cost = hint.getCostPoints() == null ? 0 : Math.max(hint.getCostPoints(), 0);
        scoreService.award(userId, "hint_penalty", String.valueOf(hintId), -cost);
        int hintsUsed = incrementProgress(userId, levelId);
        int totalPoints = scoreService.me(userId).totalPoints();
        return new HintDtos.UseResponse(
                hint.getId(),
                hint.getTier() == null ? 1 : hint.getTier(),
                hint.getBody(),
                cost,
                -cost,
                hintsUsed,
                totalPoints);
    }

    private int incrementProgress(Long userId, Long levelId) {
        UserLevelProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<UserLevelProgress>()
                .eq(UserLevelProgress::getUserId, userId)
                .eq(UserLevelProgress::getLevelId, levelId));
        if (progress == null) {
            progress = new UserLevelProgress();
            progress.setUserId(userId);
            progress.setLevelId(levelId);
            progress.setStatus("in_progress");
            progress.setAttempts(0);
            progress.setHintsUsed(1);
            progressMapper.insert(progress);
            return 1;
        }
        int count = (progress.getHintsUsed() == null ? 0 : progress.getHintsUsed()) + 1;
        progress.setHintsUsed(count);
        if ("unlocked".equals(progress.getStatus())) {
            progress.setStatus("in_progress");
        }
        progressMapper.updateById(progress);
        return count;
    }
}
