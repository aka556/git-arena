package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.config.MaintenanceProperties;
import org.xiaoyu.gitarena.domain.collab.PullRequest;
import org.xiaoyu.gitarena.domain.collab.Room;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.domain.entity.PullRequestEntity;
import org.xiaoyu.gitarena.domain.entity.RoomEntity;
import org.xiaoyu.gitarena.domain.entity.RoomMemberEntity;
import org.xiaoyu.gitarena.domain.entity.SandboxRepoEntity;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.RoomRepo;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.mapper.PullRequestMapper;
import org.xiaoyu.gitarena.mapper.RoomMapper;
import org.xiaoyu.gitarena.mapper.RoomMemberMapper;
import org.xiaoyu.gitarena.mapper.SandboxRepoMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.AchievementService;
import org.xiaoyu.gitarena.service.CollabService;
import org.xiaoyu.gitarena.service.CommandService;
import org.xiaoyu.gitarena.service.GraphService;
import org.xiaoyu.gitarena.service.LevelRegistry;
import org.xiaoyu.gitarena.service.PrReviewService;
import org.xiaoyu.gitarena.service.ScoreService;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协作房间实现（M3 阶段B，落库版）。房间/成员/PR 以数据库为真相（database.md §4.1–§4.3），
 * 内存只保留<b>运行时句柄</b>（共享裸 origin 路径、房间元数据）供 JGit 与广播使用。
 *
 * <p><b>越权防护</b>：memberId 只是展示/引用标识，<b>不是凭证</b>。所有房间操作一律以
 * {@link CurrentUser}（登录用户）鉴权——命令执行校验「当前用户 == 该成员行的 user_id」，
 * 合并 PR 校验「当前用户 == 房主」。匿名用户不能参与协作房间（需先登录）。
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
    private final RoomMapper roomMapper;
    private final RoomMemberMapper roomMemberMapper;
    private final PullRequestMapper pullRequestMapper;
    private final SandboxRepoMapper sandboxRepoMapper;
    private final LevelRegistry levelRegistry;
    private final ScoreService scoreService;
    private final AchievementService achievementService;
    private final MaintenanceProperties maintenanceProperties;
    private final PrReviewService prReviewService;
    private final JdbcTemplate jdbc;

    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();
    private final Map<String, String> roomIdByJoinCode = new ConcurrentHashMap<>();

    /**
     * 新建一行沙盒台账（database.md §3.2）。带上 expires_at 是回收作业的前提——
     * 台账扫描按 (status, expires_at) 走，expires_at 为空的行永远扫不到，会成为磁盘泄漏源。
     *
     * <p>插入后立刻向 {@link SandboxManager} 登记豁免：该沙盒此后由台账 TTL 治理，
     * 内存侧的固定空闲阈值不得再插手，否则会抢先删掉目录连带用户未 push 的提交。
     */
    private SandboxRepoEntity insertSandboxRow(String sandboxKey, Long ownerUserId, String kind, Long roomId) {
        OffsetDateTime now = OffsetDateTime.now();
        SandboxRepoEntity row = new SandboxRepoEntity();
        row.setSandboxKey(sandboxKey);
        row.setOwnerUserId(ownerUserId);
        row.setRepoKind(kind);
        row.setRoomId(roomId);
        row.setStatus(SandboxRepoEntity.STATUS_ACTIVE);
        row.setLastActiveAt(now);
        row.setExpiresAt(now.plus(maintenanceProperties.sandboxTtlOrDefault()));
        sandboxRepoMapper.insert(row);
        sandboxManager.markLedgerManaged(sandboxKey);
        return row;
    }

    /** 成员活动即为整个房间续期：成员克隆与共享 origin 一起滑动，避免房间还热着就被回收。 */
    private void touchSandboxes(Long roomRowId, Long sandboxId) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(maintenanceProperties.sandboxTtlOrDefault());
        try {
            jdbc.update("""
                    UPDATE sandbox_repos SET last_active_at = now(), expires_at = ?
                    WHERE (id = ? OR room_id = ?) AND status IN ('active', 'idle')
                    """, expiresAt, sandboxId, roomRowId);
        } catch (RuntimeException e) {
            // 续期失败不该挡住用户的命令：最坏结果是该沙盒提前一轮进回收，用户重连即重建。
            log.warn("沙盒续期失败 room={} sandbox={}: {}", roomRowId, sandboxId, e.getMessage());
        }
    }

    @Override
    public RoomJoinResponse createRoom(RoomRequests.CreateRoom request) {
        Long userId = CurrentUser.require();
        String roomId = UUID.randomUUID().toString();
        String joinCode = roomId.substring(0, 6);
        Path origin = sandboxManager.roomsBaseDir().resolve(roomId + ".git");
        roomRepo.createRoomOrigin(origin);

        // 落库：先写 room_origin 沙盒台账，再写 rooms（origin_sandbox_id 外键）
        SandboxRepoEntity originRow = insertSandboxRow(
                "rooms/" + roomId + ".git", userId, SandboxRepoEntity.KIND_ROOM_ORIGIN, null);

        RoomEntity roomRow = new RoomEntity();
        roomRow.setPublicId(roomId);
        roomRow.setJoinCode(joinCode);
        roomRow.setName(request.name());
        roomRow.setOwnerUserId(userId);
        roomRow.setOriginSandboxId(originRow.getId());
        roomRow.setScenarioLevelId(levelIdOf(request.scenarioLevelSlug()));
        roomRow.setStatus(RoomEntity.STATUS_OPEN);
        roomMapper.insert(roomRow);

        Room room = new Room(roomId, joinCode, request.name(), origin, request.scenarioLevelSlug(),
                System.currentTimeMillis());
        roomsById.put(roomId, room);
        roomIdByJoinCode.put(joinCode, roomId);

        return addMemberAndClone(room, roomRow.getId(), userId, RoomMemberEntity.ROLE_OWNER,
                displayNameOrDefault(request.displayName(), "房主"));
    }

    @Override
    public RoomJoinResponse joinRoom(RoomRequests.JoinRoom request) {
        Long userId = CurrentUser.require();
        String roomId = roomIdByJoinCode.get(request.joinCode());
        Room room = roomId == null ? null : roomsById.get(roomId);
        if (room == null) {
            throw new CommandException("房间不存在或邀请码无效：" + request.joinCode());
        }
        RoomEntity roomRow = requireRoomRow(roomId);
        // 一人一房一行（唯一约束 room_id+user_id）：已加入则视为重连，复用其本地沙盒（若仍存活）
        RoomMemberEntity existing = roomMemberMapper.selectOne(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRow.getId())
                .eq(RoomMemberEntity::getUserId, userId));
        if (existing != null) {
            return reconnectMember(room, roomRow.getId(), existing);
        }
        RoomJoinResponse response = addMemberAndClone(room, roomRow.getId(), userId,
                RoomMemberEntity.ROLE_CONTRIBUTOR, displayNameOrDefault(request.displayName(), "玩家"));
        broadcast(room);
        return response;
    }

    /** 建成员克隆沙盒、写 room_clone 台账与 room_members 行，并入册。 */
    private RoomJoinResponse addMemberAndClone(Room room, Long roomRowId, Long userId, String role, String displayName) {
        SandboxRepo sandbox = sandboxManager.create();
        roomRepo.cloneMember(room.getOriginPath(), sandbox.root());

        SandboxRepoEntity cloneRow = insertSandboxRow(
                sandbox.sessionId(), userId, SandboxRepoEntity.KIND_ROOM_CLONE, roomRowId);

        String color = AVATAR_PALETTE[memberCount(roomRowId) % AVATAR_PALETTE.length];
        RoomMemberEntity memberRow = new RoomMemberEntity();
        memberRow.setRoomId(roomRowId);
        memberRow.setUserId(userId);
        memberRow.setRole(role);
        memberRow.setLocalSandboxId(cloneRow.getId());
        memberRow.setAvatarColor(color);
        memberRow.setAvatarLabel(displayName);
        roomMemberMapper.insert(memberRow);

        String memberId = String.valueOf(memberRow.getId());
        return new RoomJoinResponse(toView(room), memberId, sandbox.sessionId(), graphService.readGraph(sandbox));
    }

    /** 已加入成员重连：复用其本地沙盒（若仍存活）；沙盒已失效（如重启）则重建克隆并更新台账。 */
    private RoomJoinResponse reconnectMember(Room room, Long roomRowId, RoomMemberEntity memberRow) {
        String sessionId = sessionIdOf(memberRow);
        SandboxRepo sandbox = sandboxManager.find(sessionId);
        if (sandbox == null) {
            // 沙盒已随进程重启丢失：重建克隆，更新 room_clone 台账与成员行的 local_sandbox_id
            sandbox = sandboxManager.create();
            roomRepo.cloneMember(room.getOriginPath(), sandbox.root());
            SandboxRepoEntity cloneRow = insertSandboxRow(
                    sandbox.sessionId(), memberRow.getUserId(), SandboxRepoEntity.KIND_ROOM_CLONE, roomRowId);
            memberRow.setLocalSandboxId(cloneRow.getId());
            roomMemberMapper.updateById(memberRow);
        }
        String memberId = String.valueOf(memberRow.getId());
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
        RoomMemberEntity memberRow = requireMemberRow(roomId, memberId);
        // 越权防护：当前登录用户必须是该成员行的 user_id（memberId 只是标识，不是凭证）
        Long userId = CurrentUser.require();
        if (!userId.equals(memberRow.getUserId())) {
            throw new CommandException("无权以该成员身份执行命令");
        }
        String sessionId = sessionIdOf(memberRow);
        CommandResponse response = commandService.execute(sessionId, command);
        touchSandboxes(memberRow.getRoomId(), memberRow.getLocalSandboxId());
        // push 改变共享 origin → 行级评论的行号要跟着重算（§4.5），再广播让其他成员刷新共享图
        if (response.ok() && command.strip().startsWith("git push")) {
            relocateAnchorsQuietly(roomId);
            broadcast(room);
        }
        return response;
    }

    @Override
    public RoomView openPullRequest(String roomId, RoomRequests.OpenPr request) {
        Room room = requireRoom(roomId);
        Long userId = CurrentUser.require();
        RoomEntity roomRow = requireRoomRow(roomId);
        // 越权防护：当前用户必须是该房间成员（memberId 只是标识，不据此鉴权）
        RoomMemberEntity authorRow = requireMemberByUser(roomRow.getId(), userId);
        if (request.sourceBranch().equals(request.targetBranch())) {
            throw new CommandException("源分支与目标分支不能相同");
        }
        RoomRepo.MergeOutcome probe = roomRepo.probeMergeable(
                room.getOriginPath(), request.sourceBranch(), request.targetBranch());
        String mergeable = switch (probe) {
            case CONFLICT -> PullRequest.MERGEABLE_CONFLICT;
            default -> PullRequest.MERGEABLE_CLEAN;
        };
        PullRequestEntity prRow = new PullRequestEntity();
        prRow.setRoomId(roomRow.getId());
        prRow.setNumber(nextPrNumber(roomRow.getId()));
        prRow.setTitle(request.title());
        prRow.setDescription(request.description());
        prRow.setAuthorMemberId(authorRow.getId());
        prRow.setSourceBranch(request.sourceBranch());
        prRow.setTargetBranch(request.targetBranch());
        prRow.setStatus(PullRequest.STATUS_OPEN);
        prRow.setMergeable(mergeable);
        pullRequestMapper.insert(prRow);
        broadcast(room);
        return toView(room);
    }

    @Override
    public RoomView mergePullRequest(String roomId, int number, String memberId) {
        Room room = requireRoom(roomId);
        Long userId = CurrentUser.require();
        RoomEntity roomRow = requireRoomRow(roomId);
        // 越权防护：只有房主（rooms.owner_user_id == 当前用户）可以合并 PR
        if (!userId.equals(roomRow.getOwnerUserId())) {
            throw new CommandException("只有房主可以合并 PR");
        }
        PullRequestEntity prRow = pullRequestMapper.selectOne(new LambdaQueryWrapper<PullRequestEntity>()
                .eq(PullRequestEntity::getRoomId, roomRow.getId())
                .eq(PullRequestEntity::getNumber, number));
        if (prRow == null) {
            throw new CommandException("PR 不存在：#" + number);
        }
        if (!PullRequest.STATUS_OPEN.equals(prRow.getStatus())) {
            throw new CommandException("PR #" + number + " 已经是 " + prRow.getStatus() + " 状态");
        }
        // 评审闸门（database.md §4.4）：有未被更晚评审取代的 changes_requested 就挡住合并。
        // 评审若不影响合并，"评审"只是摆设，教不会协作里的把关环节。
        if (prReviewService.isBlockedByChangesRequested(prRow.getId())) {
            throw new CommandException("PR #" + number + " 有评审者请求修改，需其重新评审通过后才能合并");
        }
        RoomRepo.MergeOutcome outcome = roomRepo.mergePullRequest(room.getOriginPath(),
                prRow.getSourceBranch(), prRow.getTargetBranch(),
                "Merge PR #" + number + ": " + prRow.getTitle());
        if (outcome == RoomRepo.MergeOutcome.CONFLICT) {
            prRow.setMergeable(PullRequest.MERGEABLE_CONFLICT);
            pullRequestMapper.updateById(prRow);
            broadcast(room);
            throw new CommandException("PR #" + number + " 存在冲突，无法自动合并（需先在本地解决并推送）");
        }
        prRow.setStatus(PullRequest.STATUS_MERGED);
        prRow.setMergedByMemberId(requireMemberByUser(roomRow.getId(), userId).getId());
        prRow.setMergedAt(OffsetDateTime.now());
        pullRequestMapper.updateById(prRow);
        awardPrAuthor(roomId, prRow);
        // 合并推进了目标分支 → merge-base 变了，旧侧锚点须重算（§4.5）
        relocateAnchorsQuietly(roomId);
        broadcast(room);
        return toView(room);
    }

    /** 评审活动（提交评审/评论）后把房间快照广播出去，让其他成员的 PR 面板即时更新。 */
    @EventListener
    public void onPrActivity(PrReviewServiceImpl.PrActivityEvent event) {
        Room room = roomsById.get(event.roomId());
        if (room != null) {
            broadcast(room);
        }
    }

    /** 锚点重算是评论展示的辅助，失败不该影响 push/merge 本身的成败。 */
    private void relocateAnchorsQuietly(String roomId) {
        try {
            prReviewService.relocateAnchors(roomId);
        } catch (RuntimeException e) {
            log.warn("PR 行级评论锚点重算失败 room={}: {}", roomId, e.getMessage());
        }
    }

    // ---- 内部 ----

    private Room requireRoom(String roomId) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            throw new CommandException("房间不存在：" + roomId);
        }
        return room;
    }

    private RoomEntity requireRoomRow(String roomId) {
        RoomEntity row = roomMapper.selectOne(new LambdaQueryWrapper<RoomEntity>()
                .eq(RoomEntity::getPublicId, roomId));
        if (row == null) {
            throw new CommandException("房间不存在：" + roomId);
        }
        return row;
    }

    private RoomMemberEntity requireMemberRow(String roomId, String memberId) {
        Long memberIdLong;
        try {
            memberIdLong = Long.parseLong(memberId);
        } catch (NumberFormatException e) {
            throw new CommandException("非法的成员标识");
        }
        RoomEntity roomRow = requireRoomRow(roomId);
        RoomMemberEntity row = roomMemberMapper.selectOne(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getId, memberIdLong)
                .eq(RoomMemberEntity::getRoomId, roomRow.getId()));
        if (row == null) {
            throw new CommandException("你不是该房间成员");
        }
        return row;
    }

    private RoomMemberEntity requireMemberByUser(Long roomRowId, Long userId) {
        RoomMemberEntity row = roomMemberMapper.selectOne(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRowId)
                .eq(RoomMemberEntity::getUserId, userId));
        if (row == null) {
            throw new CommandException("你不是该房间成员");
        }
        return row;
    }

    /** 由 room_clone 台账行反查该成员本地沙盒的 sessionId（sandbox_key 即 sessionId）。 */
    private String sessionIdOf(RoomMemberEntity memberRow) {
        if (memberRow.getLocalSandboxId() == null) {
            throw new CommandException("该成员没有本地沙盒");
        }
        SandboxRepoEntity cloneRow = sandboxRepoMapper.selectById(memberRow.getLocalSandboxId());
        if (cloneRow == null) {
            throw new CommandException("该成员的沙盒已失效");
        }
        return cloneRow.getSandboxKey();
    }

    private int memberCount(Long roomRowId) {
        return Math.toIntExact(roomMemberMapper.selectCount(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRowId)));
    }

    /** 房内 PR 编号自增：MAX(number)+1（唯一约束 (room_id, number) 兜底并发）。 */
    private int nextPrNumber(Long roomRowId) {
        Integer max = pullRequestMapper.selectObjs(new LambdaQueryWrapper<PullRequestEntity>()
                .select(PullRequestEntity::getNumber)
                .eq(PullRequestEntity::getRoomId, roomRowId)
                .orderByDesc(PullRequestEntity::getNumber)
                .last("LIMIT 1")).stream()
                .map(o -> (Integer) o)
                .findFirst().orElse(0);
        return max + 1;
    }

    private Long levelIdOf(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return levelRegistry.idOf(slug);
    }

    private void awardPrAuthor(String roomId, PullRequestEntity prRow) {
        if (prRow.getAuthorMemberId() == null) {
            return;
        }
        RoomMemberEntity author = roomMemberMapper.selectById(prRow.getAuthorMemberId());
        if (author == null || author.getUserId() == null) {
            return;
        }
        String sourceRef = roomId + "#" + prRow.getNumber();
        try {
            scoreService.award(author.getUserId(), "pr_merged", sourceRef, 15);
            achievementService.onPullRequestMerged(author.getUserId(), sourceRef);
        } catch (RuntimeException e) {
            log.warn("PR 合并成功但奖励发放失败 room={} pr=#{}: {}", roomId, prRow.getNumber(), e.getMessage());
        }
    }

    private void broadcast(Room room) {
        messaging.convertAndSend("/topic/rooms/" + room.getRoomId(), toView(room));
    }

    private RoomView toView(Room room) {
        RoomEntity roomRow = requireRoomRow(room.getRoomId());
        List<RoomView.MemberView> members = new ArrayList<>();
        for (RoomMemberEntity m : roomMemberMapper.selectList(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRow.getId()))) {
            members.add(new RoomView.MemberView(String.valueOf(m.getId()), m.getAvatarLabel(),
                    m.getAvatarColor(), m.getRole()));
        }
        List<RoomView.PullRequestView> prs = new ArrayList<>();
        List<PullRequestEntity> prRows = pullRequestMapper.selectList(new LambdaQueryWrapper<PullRequestEntity>()
                .eq(PullRequestEntity::getRoomId, roomRow.getId())
                .orderByAsc(PullRequestEntity::getNumber));
        // 批量取评审统计：广播频繁，逐条查会随 PR 数量 N+1 放大
        Map<Long, PrReviewService.Stats> stats = prReviewService.statsOf(
                prRows.stream().map(PullRequestEntity::getId).toList());
        for (PullRequestEntity p : prRows) {
            PrReviewService.Stats stat = stats.getOrDefault(p.getId(), PrReviewService.Stats.EMPTY);
            prs.add(new RoomView.PullRequestView(p.getNumber(), p.getTitle(), p.getDescription(),
                    p.getSourceBranch(), p.getTargetBranch(),
                    p.getAuthorMemberId() == null ? null : String.valueOf(p.getAuthorMemberId()),
                    p.getStatus(), p.getMergeable(),
                    p.getMergedByMemberId() == null ? null : String.valueOf(p.getMergedByMemberId()),
                    p.getMergedAt() == null ? null : p.getMergedAt().toEpochSecond() * 1000L,
                    stat.approvals(), stat.changesRequested(), stat.commentCount()));
        }
        return new RoomView(room.getRoomId(), room.getJoinCode(), room.getName(), room.getScenarioLevelSlug(),
                roomRow.getOwnerUserId() == null ? null : ownerMemberId(roomRow.getId()), members, prs);
    }

    /** 房主在 room_members 里的 memberId（对外展示用）。 */
    private String ownerMemberId(Long roomRowId) {
        RoomEntity roomRow = roomMapper.selectById(roomRowId);
        if (roomRow == null || roomRow.getOwnerUserId() == null) {
            return null;
        }
        RoomMemberEntity owner = roomMemberMapper.selectOne(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRowId)
                .eq(RoomMemberEntity::getUserId, roomRow.getOwnerUserId()));
        return owner == null ? null : String.valueOf(owner.getId());
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
