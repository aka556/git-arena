package org.xiaoyu.gitarena.domain.dto;

import java.util.List;

/**
 * 房间快照（REST 响应 + WebSocket 广播载荷）。广播时携带最新 roster/PR，让客户端无需二次请求即可刷新
 * 名册与 PR 列表；每个成员的 git 图仍由各自沙盒实时读出（§3 不引入第二份状态）。
 */
public record RoomView(
        String roomId,
        String joinCode,
        String name,
        String scenarioLevelSlug,
        String ownerMemberId,
        List<MemberView> members,
        List<PullRequestView> pullRequests
) {
    public record MemberView(String memberId, String displayName, String avatarColor, String role) {}

    public record PullRequestView(
            int number,
            String title,
            String description,
            String sourceBranch,
            String targetBranch,
            String authorMemberId,
            String status,
            String mergeable,
            String mergedByMemberId,
            Long mergedAt,
            /** 生效中的 approve 数（同一评审者只算其最新一次）。 */
            int approvals,
            /** 是否被"请求修改"挡住合并（database.md §4.4 闸门）。 */
            boolean changesRequested,
            int commentCount
    ) {}
}
