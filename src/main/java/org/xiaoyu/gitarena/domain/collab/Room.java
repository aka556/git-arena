package org.xiaoyu.gitarena.domain.collab;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 协作房间（M3 阶段B，内存对象）。一个房间 = 一份共享的裸 origin 仓库 + 若干成员，
 * 每个成员各持一份从 origin 克隆出的本地沙盒；成员通过 push/pull 相互可见（§1 协作差异化）。
 *
 * <p><b>M3 决策</b>：与全项目一致，房间/成员/PR 仅存内存（并发安全集合），不写库——持久化（rooms /
 * room_members / pull_requests 表）依赖 P1 用户体系，届时再落库（database.md §4）。
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

    private final ConcurrentHashMap<String, RoomMember> members = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PullRequest> pullRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger prSequence = new AtomicInteger(0);

    @Setter
    private String status = STATUS_OPEN;
    @Setter
    private String ownerMemberId;

    public Room(String roomId, String joinCode, String name, Path originPath, String scenarioLevelSlug, long createdAt) {
        this.roomId = roomId;
        this.joinCode = joinCode;
        this.name = name;
        this.originPath = originPath;
        this.scenarioLevelSlug = scenarioLevelSlug;
        this.createdAt = createdAt;
    }

    public void addMember(RoomMember member) {
        members.put(member.getMemberId(), member);
        if (ownerMemberId == null) {
            ownerMemberId = member.getMemberId();
        }
    }

    public RoomMember member(String memberId) {
        return members.get(memberId);
    }

    public Collection<RoomMember> memberList() {
        return members.values();
    }

    public int nextPrNumber() {
        return prSequence.incrementAndGet();
    }

    public void addPullRequest(PullRequest pr) {
        pullRequests.add(pr);
    }

    public List<PullRequest> pullRequestList() {
        return List.copyOf(pullRequests);
    }

    public PullRequest pullRequest(int number) {
        return pullRequests.stream().filter(p -> p.getNumber() == number).findFirst().orElse(null);
    }
}
