package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 协作房间（database.md §4.1，落库到 rooms 表）。存的是<b>房间元数据</b>——共享 origin 仓库本身
 * 在文件系统，库里只有指向它的 {@code originSandboxId}（§1 存储边界）。
 *
 * <p>{@code publicId} 是对外房间号（uuid 列），依赖 JDBC url 的 {@code stringtype=unspecified}
 * 以 String 写入，与 LevelSeeder 写 jsonb 同一套路。
 */
@Data
@TableName("rooms")
public class RoomEntity {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ARCHIVED = "archived";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String publicId;
    private String joinCode;
    private String name;
    private Long ownerUserId;
    /** 共享裸 origin 的沙盒台账 id（repo_kind='room_origin'）。 */
    private Long originSandboxId;
    private Long scenarioLevelId;
    /** open / locked / archived */
    private String status;
    private Integer maxMembers;
    // 库列为 timestamptz（database.md §0）：须用 OffsetDateTime。
    private OffsetDateTime deletedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
