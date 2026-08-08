package org.xiaoyu.gitarena.domain.collab;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;

/**
 * 协作房间（M3 阶段B，运行时句柄）。一个房间 = 一份共享的裸 origin 仓库 + 若干成员，
 * 每个成员各持一份从 origin 克隆出的本地沙盒；成员通过 push/pull 相互可见（§1 协作差异化）。
 *
 * <p><b>落库后职责</b>：本对象只承载<b>运行时句柄</b>（共享裸 origin 的目录路径、房间元数据），
 * 供 JGit 操作与广播使用；成员身份、PR 业务流一律以数据库为真相（database.md §4.1–§4.3），
 * 不在此缓存成员/PR 集合——避免内存与库不一致。
 */
@Getter
public class Room {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ARCHIVED = "archived";

    private final String roomId;
    private final String joinCode;
    private final String name;
    /** 共享裸 origin 仓库目录（成员克隆的来源）。 */
    private final Path originPath;
    /** 若房间基于某个 collab 关卡开出，记录其 slug（供 PR 场景与校验）。 */
    private final String scenarioLevelSlug;
    private final long createdAt;

    @Setter
    private String status = STATUS_OPEN;

    public Room(String roomId, String joinCode, String name, Path originPath, String scenarioLevelSlug, long createdAt) {
        this.roomId = roomId;
        this.joinCode = joinCode;
        this.name = name;
        this.originPath = originPath;
        this.scenarioLevelSlug = scenarioLevelSlug;
        this.createdAt = createdAt;
    }
}