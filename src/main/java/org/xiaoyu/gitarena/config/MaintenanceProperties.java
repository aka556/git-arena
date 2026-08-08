package org.xiaoyu.gitarena.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 运维作业配置（database.md §9 定时任务登记表）。
 *
 * <p>周期与 TTL 全部可配；{@code enabled=false} 时 {@code MaintenanceScheduler} 整个 bean 不注册，
 * 定时触发随之消失，但 {@code MaintenanceService} 的方法仍可被手动/测试调用。
 *
 * @param enabled          总开关，缺省视为开启（见 MaintenanceScheduler 的 ConditionalOnProperty）
 * @param sandboxTtl       台账沙盒的存活窗口，随成员活动滑动续期
 * @param sessionIdleTtl   无台账的会话/关卡沙盒的空闲回收阈值（内存侧判定）
 * @param batchSize        单轮回收的最大条数，防一次扫库压垮连接池
 */
@ConfigurationProperties(prefix = "git-arena.maintenance")
public record MaintenanceProperties(
        Boolean enabled,
        Duration sandboxTtl,
        Duration sessionIdleTtl,
        Integer batchSize
) {

    public Duration sandboxTtlOrDefault() {
        return sandboxTtl == null ? Duration.ofHours(24) : sandboxTtl;
    }

    public Duration sessionIdleTtlOrDefault() {
        return sessionIdleTtl == null ? Duration.ofHours(6) : sessionIdleTtl;
    }

    public int batchSizeOrDefault() {
        return batchSize == null || batchSize <= 0 ? 200 : batchSize;
    }
}
