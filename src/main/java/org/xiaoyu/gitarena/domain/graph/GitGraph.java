package org.xiaoyu.gitarena.domain.graph;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * GitGraph 只读快照契约（CLAUDE.md §5）。前端图视图与终端视图共用此模型渲染。
 * <p>本对象由 GraphMapper 从沙盒仓库实时读出，是派生快照——后端不持久化它（database.md §1 存储边界）。
 * 修改此结构必须前后端同步并升级 {@code version}。
 */
public record GitGraph(
        int version,
        List<CommitNode> commits,
        List<BranchRef> branches,
        List<TagRef> tags,
        HeadRef head,
        List<RemoteRef> remotes,
        WorkingDir workingDir
) {

    /** 当前契约版本；破坏性变更必须 +1（§5）。 */
    public static final int CONTRACT_VERSION = 1;

    public record CommitNode(
            String id,
            List<String> parents,
            String message,
            String author,
            long timestamp,
            String seq
    ) {}

    public record BranchRef(
            String name,
            String target,
            @JsonProperty("isRemote") boolean isRemote
    ) {}

    public record TagRef(String name, String target) {}

    public record HeadRef(String type, String ref) {}

    public record RemoteRef(String name, List<RemoteBranch> branches) {}

    public record RemoteBranch(String name, String target) {}

    public record WorkingDir(
            List<String> staged,
            List<String> modified,
            List<String> untracked
    ) {}
}
