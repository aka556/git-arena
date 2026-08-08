package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.ProgressView;

import java.util.List;

/**
 * 关卡进度落库（P1，database.md §3.4）。进度是仓库真相之外的<b>元数据</b>（§1 存储边界），
 * 由校验事件驱动写入 user_level_progress。匿名用户（userId 为 null）不落库，照常可玩。
 */
public interface ProgressService {

    /**
     * 记录一次校验尝试：attempts+1、刷新 last_attempt_at；首次通过则置 completed 并记 first_completed_at。
     * <p><b>非关键副作用</b>：进度落库失败绝不能中断核心游玩链路（§3 黄金法则在文件系统仓库上），
     * 故本方法自行吞掉并记录异常，永不向调用方抛出。
     */
    void record(Long userId, String slug, boolean passed);

    /** 我的进度列表（未登录返回空）。 */
    List<ProgressView> myProgress(Long userId);
}
