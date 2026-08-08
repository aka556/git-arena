package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 沙盒仓库台账（database.md §3.2，落库到 sandbox_repos 表）。<b>只是指针</b>：仓库真相在文件系统，
 * 此处存「相对 key」而非绝对路径（§7 越权防护），由 SandboxManager 以「沙盒根 + key」定位。
 *
 * <p>命名带 {@code Entity} 后缀以区别于 {@code domain/collab} 下的运行时对象（Room / RoomMember /
 * PullRequest），后者持有活的路径与句柄，前者只是库里的一行。
 *
 * <p><b>当前只为房间相关沙盒写行</b>（{@code room_origin} / {@code room_clone}）——rooms 与
 * room_members 的外键需要它们。personal / level_attempt 的台账随沙盒 GC 与配额一起落地（§7.5）。
 */
@Data
@TableName("sandbox_repos")
public class SandboxRepoEntity {

    public static final String KIND_ROOM_ORIGIN = "room_origin";
    public static final String KIND_ROOM_CLONE = "room_clone";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLEANED = "cleaned";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 相对沙盒根的 key（唯一）。会话沙盒用 sessionId；房间 origin 用 {@code rooms/<publicId>.git}。 */
    private String sandboxKey;
    private Long ownerUserId;
    /** personal / level_attempt / room_origin / room_clone */
    private String repoKind;
    private Long levelId;
    private Long roomId;
    /** active / idle / cleaning / cleaned */
    private String status;
    private Long sizeBytes;
    private Integer commitCount;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime，pgjdbc 拒绝把 timestamptz 读进 LocalDateTime。
    private OffsetDateTime lastActiveAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime cleanedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
