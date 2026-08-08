package org.xiaoyu.gitarena.domain.dto;

import java.util.List;

/**
 * PR 差异（database.md §4.5 行级评论的渲染与锚点基础）。
 *
 * <p><b>三点差异</b>：以 {@code merge-base(target, source)} 为基线比到 source 分支 HEAD，
 * 与 GitHub 的 PR diff 口径一致——只呈现"这个 PR 改了什么"，不把目标分支上别人的新提交算进来。
 *
 * <p>{@code baseSha}/{@code headSha} 是<b>派生的只读快照</b>，随分支移动而变，不落库当状态
 * （§1 存储边界）；落库的只有写评论那一刻的 {@code anchor_commit_sha}，属不可变历史标记。
 */
public record PrDiff(
        String baseSha,
        String headSha,
        List<FileDiff> files
) {

    /** 单个文件的差异。{@code oldPath} 仅重命名时与 {@code path} 不同。 */
    public record FileDiff(
            String path,
            String oldPath,
            /** ADD / MODIFY / DELETE / RENAME / COPY，取自 JGit DiffEntry.ChangeType */
            String changeType,
            boolean binary,
            List<DiffLine> lines
    ) {}

    /**
     * 差异中的一行。
     *
     * @param kind    hunk（@@ 头）/ context / add / del
     * @param oldLine 旧侧行号，add 行为 null
     * @param newLine 新侧行号，del 行为 null
     */
    public record DiffLine(
            String kind,
            Integer oldLine,
            Integer newLine,
            String content
    ) {

        public static final String KIND_HUNK = "hunk";
        public static final String KIND_CONTEXT = "context";
        public static final String KIND_ADD = "add";
        public static final String KIND_DEL = "del";
    }
}
