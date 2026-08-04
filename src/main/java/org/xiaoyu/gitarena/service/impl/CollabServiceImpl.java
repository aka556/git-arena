package org.xiaoyu.gitarena.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.domain.collab.PullRequest;
import org.xiaoyu.gitarena.domain.collab.Room;
import org.xiaoyu.gitarena.domain.collab.RoomMember;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.RoomRepo;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.CollabService;
import org.xiaoyu.gitarena.service.CommandService;
import org.xiaoyu.gitarena.service.GraphService;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协作房间实现（内存登记，不写库——与全项目一致，持久化随 P1 落库，database.md §4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabServiceImpl implements CollabService {

    /** 成员化身配色（§6.3 化身层）：按加入序轮转，尽量同房不撞色。 */
    private static final String[] AVATAR_PALETTE = {
            "#2f80ed", "#eb5757", "#27ae60", "#f2994a", "#9b51e0", "#00b8d9", "#e91e63", "#795548"
    };

    private final SandboxManager sandboxManager;
    private final RoomRepo roomRepo;
    private final GraphService graphService;
    private final CommandService commandService;
    private final SimpMessagingTemplate messaging;

    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();
    private final Map<String, String> roomIdByJoinCode = new ConcurrentHashMap<>();
    private Path roomsBaseDir;

    private synchronized Path roomsBaseDir() {
        if (roomsBaseDir == null) {
            try {
                roomsBaseDir = Files.createDirectories(
                        Path.of(System.getProperty("java.io.tmpdir"), "git-arena-rooms"));
            } catch (IOException e) {
                throw new IllegalStateException("无法创建房间根目录", e);
            }
        }
        return roomsBaseDir;
    }

    @Override
    public RoomJoinResponse createRoom(RoomRequests.CreateRoom request) {
        String roomId = UUID.randomUUID().toString();
        String joinCode = roomId.substring(0, 6);
        Path origin = roomsBaseDir().resolve(roomId + ".git");
        roomRepo.createRoomOrigin(origin);

        Room room = new Room(roomId, joinCode, request.name(), origin, request.scenarioLevelSlug(),
                System.currentTimeMillis());
        roomsById.put(roomId, room);
        roomIdByJoinCode.put(joinCode, roomId);

        return addMemberAndClone(room, displayNameOrDefault(request.displayName(), "房主"));
    }

    @Override
    public RoomJoinResponse joinRoom(RoomRequests.JoinRoom request) {
        String roomId = roomIdByJoinCode.get(request.joinCode());
        Room room = roomId == null ? null : roomsById.get(roomId);
        if (room == null) {
            throw new CommandException("房间不存在或邀请码无效：" + request.joinCode());
        }
        RoomJoinResponse response = addMemberAndClone(room, displayNameOrDefault(request.displayName(), "玩家"));
        broadcast(room);
        return response;
    }

    /** 建成员克隆沙盒并入册。 */
    private RoomJoinResponse addMemberAndClone(Room room, String displayName) {
        SandboxRepo sandbox = sandboxManager.create();
        roomRepo.cloneMember(room.getOriginPath(), sandbox.root());

        String memberId = UUID.randomUUID().toString();
        boolean first = room.memberList().isEmpty();
        String color = AVATAR_PALETTE[room.memberList().size() % AVATAR_PALETTE.length];
        RoomMember member = new RoomMember(memberId, displayName, color,
                first ? RoomMember.ROLE_OWNER : RoomMember.ROLE_CONTRIBUTOR, sandbox.sessionId());
        room.addMember(member);

        return new RoomJoinResponse(toView(room), memberId, sandbox.sessionId(), graphService.readGraph(sandbox));
    }

    @Override
    public RoomView roomView(String roomId) {
        return toView(requireRoom(roomId));
    }

    @Override
    public GitGraph originGraph(String roomId) {
        return graphService.readOriginGraph(requireRoom(roomId).getOriginPath());
    }

    @Override
    public CommandResponse memberExec(String roomId, String memberId, String command) {
        Room room = requireRoom(roomId);
        RoomMember member = requireMember(room, memberId);
        CommandResponse response = commandService.execute(member.getSessionId(), command);
        // push 改变共享 origin → 广播让其他成员刷新共享图
        if (response.ok() && command.strip().startsWith("git push")) {
            broadcast(room);
        }
        return response;
    }

    @Override
    public RoomView openPullRequest(String roomId, RoomRequests.OpenPr request) {
        Room room = requireRoom(roomId);
        requireMember(room, request.memberId());
        if (request.sourceBranch().equals(request.targetBranch())) {
            throw new CommandException("源分支与目标分支不能相同");
        }
        RoomRepo.MergeOutcome probe = roomRepo.probeMergeable(
                room.getOriginPath(), request.sourceBranch(), request.targetBranch());
        String mergeable = switch (probe) {
            case CONFLICT -> PullRequest.MERGEABLE_CONFLICT;
            default -> PullRequest.MERGEABLE_CLEAN;
        };
        PullRequest pr = new PullRequest(room.nextPrNumber(), request.title(), request.description(),
                request.sourceBranch(), request.targetBranch(), request.memberId(), System.currentTimeMillis());
        pr.setMergeable(mergeable);
        room.addPullRequest(pr);
        broadcast(room);
        return toView(room);
    }

    @Override
    public RoomView mergePullRequest(String roomId, int number, String memberId) {
        Room room = requireRoom(roomId);
        RoomMember member = requireMember(room, memberId);
        if (!RoomMember.ROLE_OWNER.equals(member.getRole())) {
            throw new CommandException("只有房主可以合并 PR");
        }
        PullRequest pr = room.pullRequest(number);
        if (pr == null) {
            throw new CommandException("PR 不存在：#" + number);
        }
        if (!PullRequest.STATUS_OPEN.equals(pr.getStatus())) {
            throw new CommandException("PR #" + number + " 已经是 " + pr.getStatus() + " 状态");
        }
        RoomRepo.MergeOutcome outcome = roomRepo.mergePullRequest(room.getOriginPath(),
                pr.getSourceBranch(), pr.getTargetBranch(),
                "Merge PR #" + number + ": " + pr.getTitle());
        if (outcome == RoomRepo.MergeOutcome.CONFLICT) {
            pr.setMergeable(PullRequest.MERGEABLE_CONFLICT);
            broadcast(room);
            throw new CommandException("PR #" + number + " 存在冲突，无法自动合并（需先在本地解决并推送）");
        }
        pr.setStatus(PullRequest.STATUS_MERGED);
        pr.setMergedByMemberId(memberId);
        pr.setMergedAt(System.currentTimeMillis());
        broadcast(room);
        return toView(room);
    }

    // ---- 内部 ----

    private Room requireRoom(String roomId) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            throw new CommandException("房间不存在：" + roomId);
        }
        return room;
    }

    private RoomMember requireMember(Room room, String memberId) {
        RoomMember member = room.member(memberId);
        if (member == null) {
            throw new CommandException("你不是该房间成员");
        }
        return member;
    }

    private void broadcast(Room room) {
        messaging.convertAndSend("/topic/rooms/" + room.getRoomId(), toView(room));
    }

    private RoomView toView(Room room) {
        List<RoomView.MemberView> members = new ArrayList<>();
        for (RoomMember m : room.memberList()) {
            members.add(new RoomView.MemberView(m.getMemberId(), m.getDisplayName(), m.getAvatarColor(), m.getRole()));
        }
        List<RoomView.PullRequestView> prs = new ArrayList<>();
        for (PullRequest p : room.pullRequestList()) {
            prs.add(new RoomView.PullRequestView(p.getNumber(), p.getTitle(), p.getDescription(),
                    p.getSourceBranch(), p.getTargetBranch(), p.getAuthorMemberId(),
                    p.getStatus(), p.getMergeable(), p.getMergedByMemberId(), p.getMergedAt()));
        }
        return new RoomView(room.getRoomId(), room.getJoinCode(), room.getName(), room.getScenarioLevelSlug(),
                room.getOwnerMemberId(), members, prs);
    }

    private String displayNameOrDefault(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    @PreDestroy
    void cleanup() {
        // 成员沙盒由 SandboxManager 统一清理；这里清房间裸仓库目录
        roomsById.values().forEach(r -> {
            try {
                org.eclipse.jgit.util.FileUtils.delete(r.getOriginPath().toFile(),
                        org.eclipse.jgit.util.FileUtils.RECURSIVE | org.eclipse.jgit.util.FileUtils.IGNORE_ERRORS);
            } catch (IOException ignore) {
                // best-effort
            }
        });
    }
}
