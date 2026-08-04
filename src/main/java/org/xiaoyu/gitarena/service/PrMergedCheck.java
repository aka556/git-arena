package org.xiaoyu.gitarena.service;

/**
 * 判定某个 PR 是否已合并（供 GoalMatcher 求值 prMerged 断言）。number 为 null 表示"任意 PR 已合并"。
 * 生产实现查房间 PR 状态；solo 关卡与单测用桩。
 */
@FunctionalInterface
public interface PrMergedCheck {

    boolean isMerged(Integer number);

    /** solo 场景默认：没有任何 PR 已合并。 */
    PrMergedCheck NONE = number -> false;
}
