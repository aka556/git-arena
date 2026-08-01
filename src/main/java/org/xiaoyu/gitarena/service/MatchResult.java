package org.xiaoyu.gitarena.service;

import java.util.List;

/**
 * 目标匹配结果。passed=false 时 reasons 列出结构化差异，供前端提示"还差什么"（level-spec.md §5.3）。
 */
public record MatchResult(boolean passed, List<String> reasons) {

    public static MatchResult pass() {
        return new MatchResult(true, List.of());
    }

    public static MatchResult fail(List<String> reasons) {
        return new MatchResult(false, reasons);
    }
}
