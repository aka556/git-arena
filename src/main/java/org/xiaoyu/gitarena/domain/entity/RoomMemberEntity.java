package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 房间成员（database.md §4.2，落库到 room_members 表）。承载 §6.3 化身层在协作房间的呈现数据
 * （avatarColor / avatarLabel）；化身的<b>实时位置</b>不入库，由该成员沙盒的实时 GitGraph 派生。
 *
 * <p>唯一约束 {@code (room_id, user_id)}——一人一房一行，所以「同一用户再次加入」是重连而非新成员。
 * 本行的 {@code id} 即对外 memberId：它只是展示/引用标识，<b>不是凭证</b>，鉴权一律看登录用户。
 */
@Data
@TableName("room_members")
public class RoomMemberEntity {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_CONTRIBUTOR = "contributor";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomId;
    private Long userId;
    /** owner / maintainer / contributor / observer */
    private String role;
    /** 该成员本地克隆的沙盒台账 id（repo_kind='room_clone'）。 */
    private Long localSandboxId;
    private String avatarColor;
    private String avatarLabel;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime。
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime joinedAt;
}
