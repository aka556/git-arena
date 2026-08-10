package org.xiaoyu.gitarena.service;

import java.util.List;

/**
 * 关卡加载/校验失败（形状或语义不合契约）。fail-closed：官方关卡有问题就拒绝启动
 * （docs/level-spec.md §0）。message 汇总所有问题，便于一次性修正。
 */
public class LevelException extends RuntimeException {

    /** 逐条问题（编辑器直接展示；单条构造时为空列表）。 */
    private final List<String> problems;

    public LevelException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    public LevelException(String slug, List<String> problems) {
        super("关卡 [" + slug + "] 不合契约：\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
