package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.domain.entity.ScoreEventEntity;
import org.xiaoyu.gitarena.domain.entity.User;
import org.xiaoyu.gitarena.mapper.ScoreEventMapper;
import org.xiaoyu.gitarena.mapper.UserMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.ScoreService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 积分服务实现：流水与聚合缓存处于同一事务，避免两者长期漂移。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreServiceImpl implements ScoreService {

    /**
     * 时段榜取数：INNER JOIN users 天然过滤掉视图刷新后才消失的用户（如已清理的游客），
     * 留下的名次空档是真实的——rank 由视图按全窗口人数算定，不在应用层重排。
     */
    private static final String PERIOD_QUERY = """
            SELECT lb.rank, lb.points, u.id, u.username, u.display_name, u.avatar_color
            FROM %s lb
            JOIN users u ON u.id = lb.user_id AND u.status = 'active'
            ORDER BY lb.rank, u.id
            LIMIT 100
            """;

    private final ScoreEventMapper scoreEventMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbc;

    /** 最近一次成功刷新时段榜的时刻；进程内观测值，重启后为 null 直到首次刷新。 */
    private final AtomicReference<OffsetDateTime> periodRefreshedAt = new AtomicReference<>();

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
    public ScoreDtos.Board leaderboard(String period) {
        String normalized = period == null || period.isBlank() ? ScoreDtos.PERIOD_ALL : period.trim();
        return switch (normalized) {
            case ScoreDtos.PERIOD_ALL -> new ScoreDtos.Board(
                    ScoreDtos.PERIOD_ALL, ScoreDtos.METRIC_TOTAL, null, totalBoard());
            // 视图名不来自用户输入——只有这两个字面量能进 SQL，杜绝拼接注入。
            case ScoreDtos.PERIOD_WEEKLY -> periodBoard(ScoreDtos.PERIOD_WEEKLY, "leaderboard_weekly");
            case ScoreDtos.PERIOD_MONTHLY -> periodBoard(ScoreDtos.PERIOD_MONTHLY, "leaderboard_monthly");
            default -> throw new CommandException("不支持的榜单口径：" + period);
        };
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshPeriodLeaderboards() {
        // 挂起外层事务：CONCURRENTLY 在事务块内会被 PG 拒绝，且一旦报错整个事务会被置为 aborted，
        // 连回退语句都执行不了。改在自动提交连接上跑，两条视图各自独立成败。
        refreshOne("leaderboard_weekly");
        refreshOne("leaderboard_monthly");
        periodRefreshedAt.set(OffsetDateTime.now());
    }

    private void refreshOne(String view) {
        try {
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
        } catch (DataAccessException e) {
            // CONCURRENTLY 要求视图已被填充过一次；全新库首刷会失败，退回普通刷新（会短暂锁读）。
            log.debug("并发刷新 {} 失败，回退普通刷新：{}", view, e.getMessage());
            jdbc.execute("REFRESH MATERIALIZED VIEW " + view);
        }
    }

    private List<ScoreDtos.LeaderboardEntry> totalBoard() {
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

    private ScoreDtos.Board periodBoard(String period, String view) {
        List<ScoreDtos.LeaderboardEntry> entries = jdbc.query(
                PERIOD_QUERY.formatted(view),
                (rs, rowNum) -> new ScoreDtos.LeaderboardEntry(
                        rs.getInt("rank"),
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("avatar_color"),
                        rs.getInt("points")));
        return new ScoreDtos.Board(period, ScoreDtos.METRIC_WINDOW, periodRefreshedAt.get(), entries);
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
