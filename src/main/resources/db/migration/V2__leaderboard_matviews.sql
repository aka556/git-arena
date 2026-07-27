-- ============================================================================
-- V2__leaderboard_matviews.sql  ·  时段排行榜物化视图（database.md §5.7）
-- ----------------------------------------------------------------------------
-- 全时段总榜直接读 users.total_points（V1 已建 users_points_idx）。
-- 周/月榜从 score_events 按时间窗聚合，物化以避免每次请求实时 sum。
--
-- 定义中的 now() 在每次 REFRESH 时重新求值，故窗口随刷新滚动（database.md §9 定时任务：
-- 每 5 分钟 REFRESH ... CONCURRENTLY）。CONCURRENTLY 要求下方唯一索引且视图已完成首次填充。
-- 口径提醒：时段榜按"窗口内新增积分"排名，与总榜（累计 total_points）不同，前端需分别标注。
-- ============================================================================

-- 周榜：滚动 7 日 --------------------------------------------------------------
CREATE MATERIALIZED VIEW leaderboard_weekly AS
SELECT user_id,
       SUM(points)                             AS points,
       RANK() OVER (ORDER BY SUM(points) DESC) AS rank
FROM score_events
WHERE created_at >= now() - INTERVAL '7 days'
GROUP BY user_id;

CREATE UNIQUE INDEX leaderboard_weekly_user_uq ON leaderboard_weekly (user_id);  -- REFRESH CONCURRENTLY 前置条件
CREATE INDEX        leaderboard_weekly_rank_idx ON leaderboard_weekly (rank);

-- 月榜：滚动 30 日 -------------------------------------------------------------
CREATE MATERIALIZED VIEW leaderboard_monthly AS
SELECT user_id,
       SUM(points)                             AS points,
       RANK() OVER (ORDER BY SUM(points) DESC) AS rank
FROM score_events
WHERE created_at >= now() - INTERVAL '30 days'
GROUP BY user_id;

CREATE UNIQUE INDEX leaderboard_monthly_user_uq ON leaderboard_monthly (user_id);
CREATE INDEX        leaderboard_monthly_rank_idx ON leaderboard_monthly (rank);
