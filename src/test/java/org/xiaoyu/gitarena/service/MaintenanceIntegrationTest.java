package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.dto.ScoreDtos;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时运维作业（database.md §9）的落库闭环测试。
 *
 * <p>刻意不加 {@code @Transactional}：被测方法本身是非事务的（回收按行提交、删目录不可回滚），
 * 包进测试事务既测不到真实提交语义，也会让物化视图的 CONCURRENTLY 刷新被 PG 拒绝。
 * 改为手工登记创建的用户，事后靠外键 CASCADE 清干净。
 */
@SpringBootTest
class MaintenanceIntegrationTest {

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private SandboxManager sandboxManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // users 上的外键均为 ON DELETE CASCADE：删用户即带走沙盒台账 / 房间成员 / 积分流水
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        createdUserIds.clear();
    }

    @Test
    void expired_guest_is_deleted_together_with_its_sandbox_directory() {
        Long guestId = guest();
        expireGuest(guestId);
        SandboxRepo sandbox = sandboxManager.create();
        insertLedger(sandbox.sessionId(), guestId, "personal", null, true);

        assertThat(maintenanceService.cleanupExpiredGuests()).isGreaterThanOrEqualTo(1);

        assertThat(userExists(guestId)).isFalse();
        assertThat(Files.exists(sandbox.root())).isFalse(); // 台账随 CASCADE 消失，目录必须显式删掉
    }

    @Test
    void expired_guest_still_owning_a_shared_room_is_kept() {
        Long ownerId = guest();
        Long peerId = guest();
        expireGuest(ownerId);
        long roomId = insertRoom(ownerId);
        insertMember(roomId, peerId, "contributor");

        maintenanceService.cleanupExpiredGuests();

        // 删房主会连带清掉房间共享 origin，把还在房里的 peer 现场清空——本轮必须跳过
        assertThat(userExists(ownerId)).isTrue();
    }

    @Test
    void expired_sandbox_row_is_reclaimed_and_marked_cleaned() {
        Long userId = guest();
        SandboxRepo sandbox = sandboxManager.create();
        insertLedger(sandbox.sessionId(), userId, "personal", null, true);

        assertThat(maintenanceService.reclaimExpiredSandboxes()).isGreaterThanOrEqualTo(1);

        assertThat(Files.exists(sandbox.root())).isFalse();
        assertThat(statusOf(sandbox.sessionId())).isEqualTo("cleaned");
    }

    @Test
    void room_origin_with_live_members_is_never_reclaimed() {
        Long ownerId = guest();
        long roomId = insertRoom(ownerId);
        SandboxRepo origin = sandboxManager.create();
        insertLedger(origin.sessionId(), ownerId, "room_origin", roomId, true);
        insertMember(roomId, ownerId, "owner");

        maintenanceService.reclaimExpiredSandboxes();

        assertThat(Files.exists(origin.root())).isTrue();
        assertThat(statusOf(origin.sessionId())).isEqualTo("active");
    }

    @Test
    void unexpired_sandbox_row_is_left_alone() {
        Long userId = guest();
        SandboxRepo sandbox = sandboxManager.create();
        insertLedger(sandbox.sessionId(), userId, "personal", null, false);

        maintenanceService.reclaimExpiredSandboxes();

        assertThat(Files.exists(sandbox.root())).isTrue();
        assertThat(statusOf(sandbox.sessionId())).isEqualTo("active");
    }

    @Test
    void period_leaderboards_refresh_and_expose_window_metric() {
        Long userId = guest();
        scoreService.award(userId, "manual", "maint-" + UUID.randomUUID().toString().substring(0, 8), 7);

        scoreService.refreshPeriodLeaderboards();
        ScoreDtos.Board weekly = scoreService.leaderboard(ScoreDtos.PERIOD_WEEKLY);

        assertThat(weekly.period()).isEqualTo(ScoreDtos.PERIOD_WEEKLY);
        // 口径必须与总榜区分开（database.md §5.7），前端据此改副标题
        assertThat(weekly.metric()).isEqualTo(ScoreDtos.METRIC_WINDOW);
        assertThat(weekly.refreshedAt()).isNotNull();
        assertThat(weekly.entries()).extracting(ScoreDtos.LeaderboardEntry::userId).contains(userId);
    }

    @Test
    void unknown_period_is_rejected_rather_than_silently_falling_back() {
        assertThat(catchThrowable(() -> scoreService.leaderboard("yearly")))
                .hasMessageContaining("不支持的榜单口径");
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return new AssertionError("期望抛出异常但没有");
        } catch (RuntimeException e) {
            return e;
        }
    }

    private Long guest() {
        AuthDtos.AuthResponse response = authService.guest();
        createdUserIds.add(response.user().id());
        return response.user().id();
    }

    private void expireGuest(Long userId) {
        jdbc.update("UPDATE users SET expires_at = now() - interval '1 day' WHERE id = ?", userId);
    }

    private long insertRoom(Long ownerId) {
        return jdbc.queryForObject("""
                        INSERT INTO rooms (join_code, name, owner_user_id, status)
                        VALUES (?, ?, ?, 'open') RETURNING id
                        """,
                Long.class, UUID.randomUUID().toString().substring(0, 8), "maint-room", ownerId);
    }

    private void insertMember(long roomId, Long userId, String role) {
        jdbc.update("INSERT INTO room_members (room_id, user_id, role) VALUES (?, ?, ?)", roomId, userId, role);
    }

    private void insertLedger(String key, Long ownerId, String kind, Long roomId, boolean expired) {
        jdbc.update("""
                        INSERT INTO sandbox_repos (sandbox_key, owner_user_id, repo_kind, room_id, status, expires_at)
                        VALUES (?, ?, ?, ?, 'active', now() + make_interval(hours => ?))
                        """,
                key, ownerId, kind, roomId, expired ? -1 : 24);
    }

    private boolean userExists(Long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId) > 0;
    }

    private String statusOf(String sandboxKey) {
        return jdbc.queryForObject("SELECT status FROM sandbox_repos WHERE sandbox_key = ?", String.class, sandboxKey);
    }
}
