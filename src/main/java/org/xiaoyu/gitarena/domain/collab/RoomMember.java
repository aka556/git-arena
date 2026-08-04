package org.xiaoyu.gitarena.domain.collab;

import lombok.Getter;
import lombok.Setter;

/**
 * 房间成员（M3 阶段B，内存对象）。每个成员在房间里有独立的本地克隆沙盒（{@code sessionId} 指向
 * SandboxManager 里那份 clone），以及用于化身层（§6.3）的配色。
 */
@Getter
@Setter
public class RoomMember {

    /** owner=建房者；contributor=其他加入者。PR 合并权限据此。 */
    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_CONTRIBUTOR = "contributor";

    private final String memberId;
    private final String displayName;
    private final String avatarColor;
    private final String role;
    /** 该成员本地克隆的沙盒会话 id（其 origin 指向房间共享裸仓库）。 */
    private final String sessionId;
    private long lastSeenAt;

    public RoomMember(String memberId, String displayName, String avatarColor, String role, String sessionId) {
        this.memberId = memberId;
        this.displayName = displayName;
        this.avatarColor = avatarColor;
        this.role = role;
        this.sessionId = sessionId;
    }
}
