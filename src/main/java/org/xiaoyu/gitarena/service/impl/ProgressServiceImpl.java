package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.dto.ProgressView;
import org.xiaoyu.gitarena.domain.entity.UserLevelProgress;
import org.xiaoyu.gitarena.mapper.UserLevelProgressMapper;
import org.xiaoyu.gitarena.service.AchievementService;
import org.xiaoyu.gitarena.service.LevelRegistry;
import org.xiaoyu.gitarena.service.ProgressService;
import org.xiaoyu.gitarena.service.ScoreService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressServiceImpl implements ProgressService {

    private final UserLevelProgressMapper progressMapper;
    private final LevelRegistry levelRegistry;
    private final ScoreService scoreService;
    private final AchievementService achievementService;

    @Override
    public void record(Long userId, String slug, boolean passed) {
        if (userId == null) {
            return; // 匿名可玩但不落库（CurrentUser 可选认证）
        }
        Long levelId = levelRegistry.idOf(slug);
        if (levelId == null) {
            return; // 关卡未 seed 进库（理论不该发生），无外键可指，跳过
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            boolean firstCompletion = false;
            UserLevelProgress existing = progressMapper.selectOne(new LambdaQueryWrapper<UserLevelProgress>()
                    .eq(UserLevelProgress::getUserId, userId)
                    .eq(UserLevelProgress::getLevelId, levelId));
            if (existing == null) {
                UserLevelProgress row = new UserLevelProgress();
                row.setUserId(userId);
                row.setLevelId(levelId);
                row.setAttempts(1);
                row.setHintsUsed(0);
                row.setLastAttemptAt(now);
                if (passed) {
                    row.setStatus("completed");
                    row.setStarRating(3);
                    row.setFirstCompletedAt(now);
                    firstCompletion = true;
                } else {
                    row.setStatus("in_progress");
                    row.setStarRating(0);
                }
                progressMapper.insert(row);
            } else {
                existing.setAttempts((existing.getAttempts() == null ? 0 : existing.getAttempts()) + 1);
                existing.setLastAttemptAt(now);
                if (passed && !"completed".equals(existing.getStatus())) {
                    existing.setStatus("completed");
                    existing.setStarRating(3);
                    existing.setFirstCompletedAt(now);
                    firstCompletion = true;
                } else if (!passed && "unlocked".equals(existing.getStatus())) {
                    existing.setStatus("in_progress");
                }
                progressMapper.updateById(existing);
            }
            if (firstCompletion) {
                scoreService.award(userId, "level_complete", slug, 10);
                achievementService.onLevelCompleted(userId, slug);
            }
        } catch (Exception e) {
            // 进度落库是非关键副作用，失败只记日志，不影响校验结果返回（§3）
            log.warn("记录关卡进度失败 user={} slug={}: {}", userId, slug, e.getMessage());
        }
    }

    @Override
    public List<ProgressView> myProgress(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<UserLevelProgress> rows = progressMapper.selectList(new LambdaQueryWrapper<UserLevelProgress>()
                .eq(UserLevelProgress::getUserId, userId));
        List<ProgressView> out = new ArrayList<>(rows.size());
        for (UserLevelProgress row : rows) {
            String slug = levelRegistry.slugOf(row.getLevelId());
            if (slug == null) {
                continue; // 关卡已下架/未登记，跳过（进度行仍在库，仅不对外展示）
            }
            out.add(new ProgressView(
                    slug,
                    row.getStatus(),
                    row.getAttempts() == null ? 0 : row.getAttempts(),
                    row.getStarRating() == null ? 0 : row.getStarRating(),
                    row.getBestCommandCount(),
                    row.getFirstCompletedAt()));
        }
        return out;
    }
}
