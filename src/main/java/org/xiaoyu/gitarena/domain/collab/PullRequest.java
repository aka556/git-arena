package org.xiaoyu.gitarena.domain.collab;

import lombok.Getter;
import lombok.Setter;

/**
 * Pull Request（M3 阶段B，内存对象）。PR 描述"把房间共享仓库上的 sourceBranch 合并进 targetBranch"，
 * 合并在房间的裸 origin 上以 JGit 内核合并完成（database.md §4.3：只存分支名不存 hash）。
 */
@Getter
@Setter
public class PullRequest {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_MERGED = "merged";
    public static final String STATUS_CLOSED = "closed";

    public static final String MERGEABLE_UNKNOWN = "unknown";
    public static final String MERGEABLE_CLEAN = "clean";
    public static final String MERGEABLE_CONFLICT = "conflict";

    private final int number;
    private final String title;
    private final String description;
    private final String sourceBranch;
    private final String targetBranch;
    private final String authorMemberId;
    private final long createdAt;

    private String status = STATUS_OPEN;
    private String mergeable = MERGEABLE_UNKNOWN;
    private String mergedByMemberId;
    private Long mergedAt;

    public PullRequest(int number, String title, String description,
                       String sourceBranch, String targetBranch, String authorMemberId, long createdAt) {
        this.number = number;
        this.title = title;
        this.description = description;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.authorMemberId = authorMemberId;
        this.createdAt = createdAt;
    }
}
