package org.xiaoyu.gitarena.service;

/**
 * 运维回收作业（database.md §9 定时任务登记表）。
 *
 * <p>三件事都属"台账与文件系统对账"：库里的 {@code sandbox_repos} 只是指针，真相在磁盘（§1），
 * 因此每个动作都必须<b>同时</b>处理两侧，否则要么留下孤儿目录（磁盘泄漏，CLAUDE.md §7.7），
 * 要么留下指向空目录的死台账。
 *
 * <p>接口与调度解耦：{@code MaintenanceScheduler} 只管"何时触发"，本接口管"做什么"，
 * 让集成测试能直接调用而不依赖时钟。
 */
public interface MaintenanceService {

    /**
     * 清理过期游客（每小时；保留期 24h 见 database.md §3.1）。
     *
     * <p>删用户行即由外键 {@code CASCADE} 带走进度/成就/积分流水/沙盒台账，本方法额外负责删沙盒目录。
     * <b>仍拥有活跃房间</b>（房内还有别人）的游客本轮跳过——否则会连房间共享 origin 一起删，
     * 把其他成员的协作现场清空。
     *
     * @return 实际删除的游客数
     */
    int cleanupExpiredGuests();

    /**
     * 回收过期的台账沙盒（每小时）：{@code active/idle} 且 {@code expires_at} 已过
     * → 置 {@code cleaning} → 删目录 → 置 {@code cleaned}。三段式让中途崩溃留下可辨识的中间态。
     *
     * @return 实际回收的沙盒数
     */
    int reclaimExpiredSandboxes();

    /**
     * 回收空闲的无台账沙盒（每小时）：匿名会话与关卡沙盒无 owner 可挂台账，
     * 按内存侧"最后活跃时刻"判定（{@code SandboxManager.reapIdle}）。
     *
     * @return 实际回收的沙盒数
     */
    int reapIdleSessionSandboxes();
}
