package org.xiaoyu.gitarena.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.service.MaintenanceService;
import org.xiaoyu.gitarena.service.ScoreService;

/**
 * 定时作业触发器（database.md §9 登记表的代码对应物）。
 *
 * <p>本类只管"何时触发"，业务在 {@link MaintenanceService} / {@link ScoreService}，
 * 便于集成测试绕开时钟直接验证动作。
 *
 * <p>作业内一律吞掉异常：调度线程抛出未捕获异常会让该作业<b>永久停摆</b>（后续周期不再触发），
 * 对回收类任务来说等于静默的磁盘泄漏，故失败只记日志、等下一周期重试。
 *
 * <p>{@code git-arena.maintenance.enabled=false} 可整体关闭定时触发（本 bean 不注册）。
 * 首轮延迟错开 1/2/3 分钟：既避开应用启动高峰，也让集成测试（跑完远早于首轮）不与调度线程抢库。
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "git-arena.maintenance", name = "enabled", matchIfMissing = true)
public class MaintenanceScheduler {

    private final MaintenanceService maintenanceService;
    private final ScoreService scoreService;

    /** 时段榜刷新：每 5 分钟（database.md §9）。启动后延迟 1 分钟，避开应用启动高峰。 */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    void refreshLeaderboards() {
        run("时段榜刷新", scoreService::refreshPeriodLeaderboards);
    }

    /** 游客过期清理：每小时（database.md §9 / §3.1 保留 24h）。 */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    void cleanupGuests() {
        run("游客过期清理", maintenanceService::cleanupExpiredGuests);
    }

    /** 沙盒空闲回收：每小时，台账侧与内存侧各扫一轮（database.md §9 / CLAUDE.md §7.7）。 */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1H")
    void reclaimSandboxes() {
        run("过期沙盒回收", maintenanceService::reclaimExpiredSandboxes);
        run("空闲沙盒回收", maintenanceService::reapIdleSessionSandboxes);
    }

    private void run(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("定时作业「{}」执行失败，等下一周期重试：{}", name, e.getMessage());
        }
    }
}
