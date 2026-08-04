package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 协作房间相关请求体。 */
public final class RoomRequests {

    private RoomRequests() {
    }

    public record CreateRoom(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 64) String displayName,
            /** 可选：基于某个关卡开房（用其 initial 物化共享仓库）。 */
            String scenarioLevelSlug
    ) {}

    public record JoinRoom(
            @NotBlank String joinCode,
            @Size(max = 64) String displayName
    ) {}

    public record OpenPr(
            @NotBlank String memberId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotBlank String sourceBranch,
            @NotBlank String targetBranch
    ) {}

    public record MergePr(@NotBlank String memberId) {}

    public record Exec(@NotBlank @Size(max = 2000) String command) {}
}
