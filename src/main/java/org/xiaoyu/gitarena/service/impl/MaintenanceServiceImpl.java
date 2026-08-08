package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.config.MaintenanceProperties;
import org.xiaoyu.gitarena.domain.entity.SandboxRepoEntity;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.service.MaintenanceService;

import java.util.List;
import java.util.Map;

/**
 * 回收作业实现（database.md §9）。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：删目录是不可回滚的文件系统副作用，若包在事务里，
 * 事务回滚会留下"库里还在、磁盘已空"的幽灵台账。改为按行推进——先在库里标记意图，
 * 再动磁盘，最后落终态；任一行失败只跳过该行，不影响整批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceServiceImpl implements MaintenanceService {

    /**
     * 过期游客：排除仍拥有"房内还有别人"的房间的游客。
     * 那种房间的共享 origin 挂在房主名下，删了会波及其他成员，留到房间散场后的下一轮再收。
     */
    private static final String EXPIRED_GUESTS = """
            SELECT u.id
            FROM users u
            WHERE u.is_guest = true
              AND u.expires_at IS NOT NULL
              AND u.expires_at < now()
              AND NOT EXISTS (
                    SELECT 1 FROM rooms r
                    WHERE r.owner_user_id = u.id
                      AND r.deleted_at IS NULL
                      AND EXISTS (SELECT 1 FROM room_members m
                                  WHERE m.room_id = r.id AND m.user_id <> u.id))
            ORDER BY u.expires_at
            LIMIT ?
            """;

    /** 同理排除仍有其他成员在用的房间 origin（其 room_id 指向的房间还有别人）。 */
    private static final String EXPIRED_SANDBOXES = """
            SELECT s.id, s.sandbox_key
            FROM sandbox_repos s
            WHERE s.status IN ('active', 'idle')
              AND s.expires_at IS NOT NULL
              AND s.expires_at < now()
              AND NOT (s.repo_kind = 'room_origin'
                       AND EXISTS (SELECT 1 FROM room_members m WHERE m.room_id = s.room_id))
            ORDER BY s.expires_at
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final SandboxManager sandboxManager;
    private final MaintenanceProperties properties;

    @Override
    public int cleanupExpiredGuests() {
        List<Long> guestIds = jdbc.queryForList(EXPIRED_GUESTS, Long.class, properties.batchSizeOrDefault());
        int removed = 0;
        for (Long guestId : guestIds) {
            try {
                // 先取 key：删用户后台账行随 CASCADE 消失，届时已无从知道该删哪个目录。
                List<String> keys = jdbc.queryForList(
                        "SELECT sandbox_key FROM sandbox_repos WHERE owner_user_id = ?", String.class, guestId);
                if (jdbc.update("DELETE FROM users WHERE id = ? AND is_guest = true", guestId) == 0) {
                    continue;
                }
                keys.forEach(sandboxManager::deleteByKey);
                removed++;
            } catch (RuntimeException e) {
                log.warn("清理过期游客 {} 失败：{}", guestId, e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("清理过期游客 {} 个", removed);
        }
        return removed;
    }

    @Override
    public int reclaimExpiredSandboxes() {
        List<Map<String, Object>> rows = jdbc.queryForList(EXPIRED_SANDBOXES, properties.batchSizeOrDefault());
        int reclaimed = 0;
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String key = (String) row.get("sandbox_key");
            try {
                // 先置 cleaning 再动磁盘：中途崩溃时该行停在 cleaning，肉眼可辨是"删了一半"。
                if (jdbc.update("UPDATE sandbox_repos SET status = 'cleaning' WHERE id = ? AND status IN ('active','idle')",
                        id) == 0) {
                    continue; // 已被并发的另一轮取走
                }
                sandboxManager.deleteByKey(key);
                jdbc.update("UPDATE sandbox_repos SET status = ?, cleaned_at = now() WHERE id = ?",
                        SandboxRepoEntity.STATUS_CLEANED, id);
                reclaimed++;
            } catch (RuntimeException e) {
                log.warn("回收沙盒 {}（key={}）失败：{}", id, key, e.getMessage());
            }
        }
        if (reclaimed > 0) {
            log.info("回收过期沙盒 {} 个", reclaimed);
        }
        return reclaimed;
    }

    @Override
    public int reapIdleSessionSandboxes() {
        return sandboxManager.reapIdle(properties.sessionIdleTtlOrDefault());
    }
}
