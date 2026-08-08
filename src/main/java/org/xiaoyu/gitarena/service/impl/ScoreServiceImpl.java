package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.domain.entity.ScoreEventEntity;
import org.xiaoyu.gitarena.domain.entity.User;
import org.xiaoyu.gitarena.mapper.ScoreEventMapper;
import org.xiaoyu.gitarena.mapper.UserMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.ScoreService;

import java.util.ArrayList;
import java.util.List;

/** 积分服务实现：流水与聚合缓存处于同一事务，避免两者长期漂移。 */
@Service
@RequiredArgsConstructor
public class ScoreServiceImpl implements ScoreService {

    private final ScoreEventMapper scoreEventMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void award(Long userId, String sourceType, String sourceRef, int points) {
        if (userId == null) {
            return;
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("积分来源不能为空");
        }
        if (userMapper.selectById(userId) == null) {
            throw new CommandException("用户不存在：" + userId);
        }

        ScoreEventEntity event = new ScoreEventEntity();
        event.setUserId(userId);
        event.setSourceType(sourceType);
        event.setSourceRef(sourceRef);
        event.setPoints(points);
        scoreEventMapper.insert(event);

        int updated = jdbc.update(
                "UPDATE users SET total_points = total_points + ? WHERE id = ?",
                points, userId);
        if (updated != 1) {
            throw new CommandException("更新用户积分失败：" + userId);
        }
    }

    @Override
    public ScoreDtos.Me me(Long userId) {
        User user = requireUser(userId);
        return new ScoreDtos.Me(user.getId(), pointsOf(user));
    }

    @Override
    public List<ScoreDtos.LeaderboardEntry> leaderboard() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, "active")
                .orderByDesc(User::getTotalPoints)
                .orderByAsc(User::getId)
                .last("LIMIT 100"));
        List<ScoreDtos.LeaderboardEntry> entries = new ArrayList<>(users.size());
        int rank = 0;
        Integer previousPoints = null;
        for (int index = 0; index < users.size(); index++) {
            User user = users.get(index);
            int points = pointsOf(user);
            if (previousPoints == null || points != previousPoints) {
                rank = index + 1;
                previousPoints = points;
            }
            entries.add(new ScoreDtos.LeaderboardEntry(
                    rank,
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getAvatarColor(),
                    points));
        }
        return entries;
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new CommandException("请先登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new CommandException("用户不存在：" + userId);
        }
        return user;
    }

    private int pointsOf(User user) {
        return user.getTotalPoints() == null ? 0 : user.getTotalPoints();
    }
}
