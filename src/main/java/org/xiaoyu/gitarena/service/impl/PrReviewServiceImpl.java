package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.PrDiff;
import org.xiaoyu.gitarena.domain.dto.PrReviewDtos;
import org.xiaoyu.gitarena.domain.entity.PrCommentEntity;
import org.xiaoyu.gitarena.domain.entity.PrReviewEntity;
import org.xiaoyu.gitarena.domain.entity.PullRequestEntity;
import org.xiaoyu.gitarena.domain.entity.RoomEntity;
import org.xiaoyu.gitarena.domain.entity.RoomMemberEntity;
import org.xiaoyu.gitarena.git.RoomRepo;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.mapper.PrCommentMapper;
import org.xiaoyu.gitarena.mapper.PrReviewMapper;
import org.xiaoyu.gitarena.mapper.PullRequestMapper;
import org.xiaoyu.gitarena.mapper.RoomMapper;
import org.xiaoyu.gitarena.mapper.RoomMemberMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.DiffAnchorRelocator;
import org.xiaoyu.gitarena.service.PrReviewService;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PR 评审实现（database.md §4.4/§4.5）。
 *
 * <p>锚点三件套（{@code anchor_commit_sha} / {@code original_line} / {@code diff_hunk}）
 * <b>一律由后端在写入时从真实 diff 取</b>，不接受客户端自报——否则锚点这个"不可变事实"就不可信了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrReviewServiceImpl implements PrReviewService {

    private final RoomMapper roomMapper;
    private final RoomMemberMapper roomMemberMapper;
    private final PullRequestMapper pullRequestMapper;
    private final PrReviewMapper prReviewMapper;
    private final PrCommentMapper prCommentMapper;
    private final RoomRepo roomRepo;
    private final SandboxManager sandboxManager;
    private final DiffAnchorRelocator relocator;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;

    /** 评审活动事件：由 {@code CollabServiceImpl} 监听并广播房间快照，避免两个 service 互相依赖成环。 */
    public record PrActivityEvent(String roomId) {
    }

    @Override
    public PrDiff diff(String roomId, int number) {
        RoomEntity roomRow = requireRoom(roomId);
        requireMember(roomRow.getId(), CurrentUser.require());
        PullRequestEntity pr = requirePr(roomRow.getId(), number);
        return roomRepo.diff(originPath(roomId), pr.getSourceBranch(), pr.getTargetBranch());
    }

    @Override
    @Transactional
    public PrReviewDtos.Thread submitReview(String roomId, int number, PrReviewDtos.SubmitReview request) {
        RoomEntity roomRow = requireRoom(roomId);
        RoomMemberEntity reviewer = requireMember(roomRow.getId(), CurrentUser.require());
        PullRequestEntity pr = requirePr(roomRow.getId(), number);
        if (!"open".equals(pr.getStatus())) {
            throw new CommandException("PR #" + number + " 已关闭，不能再评审");
        }
        // 自审自批会让评审沦为形式：可以留言（commented），但不能给自己 approve / 请求修改
        if (reviewer.getId().equals(pr.getAuthorMemberId())
                && !PrReviewEntity.STATE_COMMENTED.equals(request.state())) {
            throw new CommandException("不能评审自己发起的 PR（可以留言，但不能自己批准或请求修改）");
        }

        PrReviewEntity review = new PrReviewEntity();
        review.setPullRequestId(pr.getId());
        review.setReviewerMemberId(reviewer.getId());
        review.setState(request.state());
        review.setBody(request.body());
        review.setSubmittedAt(OffsetDateTime.now());
        prReviewMapper.insert(review);

        if (request.comments() != null && !request.comments().isEmpty()) {
            PrDiff diff = roomRepo.diff(originPath(roomId), pr.getSourceBranch(), pr.getTargetBranch());
            for (PrReviewDtos.InlineComment inline : request.comments()) {
                insertInline(pr, reviewer, review.getId(), diff,
                        inline.filePath(), inline.diffSide(), inline.line(), inline.body());
            }
        }
        events.publishEvent(new PrActivityEvent(roomId));
        return thread(roomId, number);
    }

    @Override
    @Transactional
    public PrReviewDtos.Thread addComment(String roomId, int number, PrReviewDtos.AddComment request) {
        RoomEntity roomRow = requireRoom(roomId);
        RoomMemberEntity author = requireMember(roomRow.getId(), CurrentUser.require());
        PullRequestEntity pr = requirePr(roomRow.getId(), number);

        boolean inline = request.filePath() != null && request.diffSide() != null && request.line() != null;
        if (inline) {
            PrDiff diff = roomRepo.diff(originPath(roomId), pr.getSourceBranch(), pr.getTargetBranch());
            insertInline(pr, author, null, diff,
                    request.filePath(), request.diffSide(), request.line(), request.body());
        } else {
            PrCommentEntity row = new PrCommentEntity();
            row.setPullRequestId(pr.getId());
            row.setAuthorMemberId(author.getId());
            row.setBody(request.body());
            row.setCommentKind(PrCommentEntity.KIND_GENERAL);
            row.setIsOutdated(false);
            prCommentMapper.insert(row);
        }
        events.publishEvent(new PrActivityEvent(roomId));
        return thread(roomId, number);
    }

    @Override
    public PrReviewDtos.Thread thread(String roomId, int number) {
        RoomEntity roomRow = requireRoom(roomId);
        PullRequestEntity pr = requirePr(roomRow.getId(), number);
        return buildThread(roomRow, pr);
    }

    @Override
    @Transactional
    public int relocateAnchors(String roomId) {
        RoomEntity roomRow = roomMapper.selectOne(new LambdaQueryWrapper<RoomEntity>()
                .eq(RoomEntity::getPublicId, roomId));
        if (roomRow == null) {
            return 0;
        }
        List<PullRequestEntity> openPrs = pullRequestMapper.selectList(new LambdaQueryWrapper<PullRequestEntity>()
                .eq(PullRequestEntity::getRoomId, roomRow.getId())
                .eq(PullRequestEntity::getStatus, "open"));
        int updated = 0;
        for (PullRequestEntity pr : openPrs) {
            updated += relocateForPr(roomId, pr);
        }
        return updated;
    }

    @Override
    public boolean isBlockedByChangesRequested(Long pullRequestId) {
        return !blockingReviewerIds(pullRequestId).isEmpty();
    }

    @Override
    public Map<Long, Stats> statsOf(Collection<Long> pullRequestIds) {
        if (pullRequestIds == null || pullRequestIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = pullRequestIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = pullRequestIds.toArray();

        Map<Long, Integer> approvals = new HashMap<>();
        Map<Long, Boolean> blocked = new HashMap<>();
        // 每位评审者只算最新一次（同 blockingReviewerIds 的口径），故先按 (pr, reviewer) 取 rn=1
        jdbc.query("""
                SELECT pull_request_id, state FROM (
                    SELECT pull_request_id, reviewer_member_id, state,
                           ROW_NUMBER() OVER (PARTITION BY pull_request_id, reviewer_member_id
                                              ORDER BY submitted_at DESC, id DESC) AS rn
                    FROM pr_reviews
                    WHERE pull_request_id IN (%s) AND reviewer_member_id IS NOT NULL
                ) latest WHERE rn = 1
                """.formatted(placeholders), args, (RowCallbackHandler) rs -> {
            long prId = rs.getLong("pull_request_id");
            String state = rs.getString("state");
            if (PrReviewEntity.STATE_APPROVED.equals(state)) {
                approvals.merge(prId, 1, Integer::sum);
            } else if (PrReviewEntity.STATE_CHANGES_REQUESTED.equals(state)) {
                blocked.put(prId, true);
            }
        });

        Map<Long, Integer> comments = new HashMap<>();
        jdbc.query("SELECT pull_request_id, count(*) AS c FROM pr_comments WHERE pull_request_id IN (%s) "
                        .formatted(placeholders) + "GROUP BY pull_request_id",
                args, (RowCallbackHandler) rs -> comments.put(rs.getLong("pull_request_id"), rs.getInt("c")));

        Map<Long, Stats> result = new HashMap<>();
        for (Object id : args) {
            long prId = ((Number) id).longValue();
            result.put(prId, new Stats(
                    approvals.getOrDefault(prId, 0),
                    blocked.getOrDefault(prId, false),
                    comments.getOrDefault(prId, 0)));
        }
        return result;
    }

    // ---- 内部 ----

    /**
     * 写入一条行级评论，锚点事实取自<b>当前真实 diff</b>：
     * sha 取源分支此刻的 HEAD，hunk 取该行所属的那一段——这两样定格后永不改写（§4.5）。
     */
    private void insertInline(PullRequestEntity pr, RoomMemberEntity author, Long reviewId, PrDiff diff,
                              String filePath, String side, Integer line, String body) {
        PrDiff.FileDiff file = diff.files().stream()
                .filter(f -> f.path().equals(filePath))
                .findFirst()
                .orElseThrow(() -> new CommandException("该文件不在本 PR 的差异里：" + filePath));
        String hunk = hunkAround(file, side, line);
        if (hunk == null) {
            throw new CommandException("该行不在本 PR 的差异里，无法作为评论锚点：" + filePath + ":" + line);
        }
        PrCommentEntity row = new PrCommentEntity();
        row.setPullRequestId(pr.getId());
        row.setReviewId(reviewId);
        row.setAuthorMemberId(author.getId());
        row.setBody(body);
        row.setCommentKind(PrCommentEntity.KIND_INLINE);
        row.setAnchorCommitSha(diff.headSha());
        row.setFilePath(filePath);
        row.setDiffSide(side);
        row.setOriginalLine(line);
        row.setDiffHunk(hunk);
        row.setCurrentLine(line); // 刚写下时锚点即当前位置
        row.setIsOutdated(false);
        prCommentMapper.insert(row);
    }

    /** 抽出目标行所在的那一段 hunk 文本（含 {@code @@} 头），即 §4.5 要求存下的"再定位依据"。 */
    private String hunkAround(PrDiff.FileDiff file, String side, int line) {
        boolean wantNew = !PrCommentEntity.SIDE_OLD.equals(side);
        List<List<PrDiff.DiffLine>> hunks = new ArrayList<>();
        List<PrDiff.DiffLine> current = null;
        for (PrDiff.DiffLine diffLine : file.lines()) {
            if (PrDiff.DiffLine.KIND_HUNK.equals(diffLine.kind())) {
                current = new ArrayList<>();
                current.add(diffLine);
                hunks.add(current);
            } else if (current != null) {
                current.add(diffLine);
            }
        }
        for (List<PrDiff.DiffLine> hunk : hunks) {
            boolean hit = hunk.stream().anyMatch(l -> {
                Integer at = wantNew ? l.newLine() : l.oldLine();
                return at != null && at == line;
            });
            if (hit) {
                return renderHunk(hunk);
            }
        }
        return null;
    }

    /** 还原成 unified diff 文本，与 {@link DiffAnchorRelocator} 的解析格式对齐。 */
    private String renderHunk(List<PrDiff.DiffLine> hunk) {
        StringBuilder sb = new StringBuilder();
        for (PrDiff.DiffLine line : hunk) {
            String prefix = switch (line.kind()) {
                case PrDiff.DiffLine.KIND_HUNK -> "";
                case PrDiff.DiffLine.KIND_ADD -> "+";
                case PrDiff.DiffLine.KIND_DEL -> "-";
                default -> " ";
            };
            sb.append(prefix).append(line.content()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 重算单个 PR 的锚点。新侧锚点比到源分支当前 HEAD，旧侧锚点比到当前 merge-base——
     * 两侧的"当前版本"不是同一个提交，混用会得出荒唐的行号。
     */
    private int relocateForPr(String roomId, PullRequestEntity pr) {
        List<PrCommentEntity> inlineComments = prCommentMapper.selectList(
                new LambdaQueryWrapper<PrCommentEntity>()
                        .eq(PrCommentEntity::getPullRequestId, pr.getId())
                        .eq(PrCommentEntity::getCommentKind, PrCommentEntity.KIND_INLINE));
        if (inlineComments.isEmpty()) {
            return 0;
        }
        Path origin = originPath(roomId);
        PrDiff diff;
        try {
            diff = roomRepo.diff(origin, pr.getSourceBranch(), pr.getTargetBranch());
        } catch (CommandException e) {
            // 分支被删等：无从重算，保持既有值不动，等下次分支恢复
            log.debug("PR #{} 锚点重算跳过：{}", pr.getNumber(), e.getMessage());
            return 0;
        }

        Map<String, List<String>> fileCache = new HashMap<>();
        int updated = 0;
        for (PrCommentEntity comment : inlineComments) {
            boolean oldSide = PrCommentEntity.SIDE_OLD.equals(comment.getDiffSide());
            String sha = oldSide ? diff.baseSha() : diff.headSha();
            if (sha == null) {
                continue;
            }
            String cacheKey = sha + ":" + comment.getFilePath();
            List<String> lines = fileCache.computeIfAbsent(cacheKey,
                    key -> roomRepo.readFileLines(origin, sha, comment.getFilePath()));

            OptionalInt located = relocator.relocate(
                    comment.getDiffHunk(), comment.getDiffSide(), comment.getOriginalLine(), lines);
            Integer newLine = located.isPresent() ? located.getAsInt() : null;
            boolean outdated = located.isEmpty();
            boolean changed = !java.util.Objects.equals(newLine, comment.getCurrentLine())
                    || !Boolean.valueOf(outdated).equals(comment.getIsOutdated());
            if (changed) {
                // 显式 SQL 而非 updateById：MyBatis-Plus 默认跳过 null 字段，
                // 会把"无法定位"写成 is_outdated=true 却留着上一次的 current_line——
                // 那是个自相矛盾的行号，UI 一旦漏判 outdated 就会把评论挂到无关代码上。
                jdbc.update("UPDATE pr_comments SET current_line = ?, is_outdated = ? WHERE id = ?",
                        newLine, outdated, comment.getId());
                updated++;
            }
        }
        return updated;
    }

    /**
     * 挡住合并的评审者：每位评审者只看其<b>最新</b>一次评审，状态为 changes_requested 才算数。
     * 这样"请求修改 → 作者改完 → 评审者再 approve"能正常解锁，不会永久卡死。
     */
    private Set<Long> blockingReviewerIds(Long pullRequestId) {
        Map<Long, PrReviewEntity> latestByReviewer = latestReviewByReviewer(pullRequestId);
        Set<Long> blocking = new HashSet<>();
        latestByReviewer.forEach((reviewerId, review) -> {
            if (PrReviewEntity.STATE_CHANGES_REQUESTED.equals(review.getState())) {
                blocking.add(reviewerId);
            }
        });
        return blocking;
    }

    private Map<Long, PrReviewEntity> latestReviewByReviewer(Long pullRequestId) {
        List<PrReviewEntity> reviews = prReviewMapper.selectList(new LambdaQueryWrapper<PrReviewEntity>()
                .eq(PrReviewEntity::getPullRequestId, pullRequestId)
                .orderByAsc(PrReviewEntity::getSubmittedAt)
                .orderByAsc(PrReviewEntity::getId));
        Map<Long, PrReviewEntity> latest = new LinkedHashMap<>();
        for (PrReviewEntity review : reviews) {
            if (review.getReviewerMemberId() != null) {
                latest.put(review.getReviewerMemberId(), review); // 后来的覆盖先前的
            }
        }
        return latest;
    }

    private PrReviewDtos.Thread buildThread(RoomEntity roomRow, PullRequestEntity pr) {
        Map<Long, RoomMemberEntity> members = new HashMap<>();
        for (RoomMemberEntity member : roomMemberMapper.selectList(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRow.getId()))) {
            members.put(member.getId(), member);
        }
        Map<Long, PrReviewEntity> latest = latestReviewByReviewer(pr.getId());
        Set<Long> latestIds = new HashSet<>();
        latest.values().forEach(review -> latestIds.add(review.getId()));

        List<PrReviewEntity> allReviews = prReviewMapper.selectList(new LambdaQueryWrapper<PrReviewEntity>()
                .eq(PrReviewEntity::getPullRequestId, pr.getId())
                .orderByAsc(PrReviewEntity::getId));
        List<PrReviewDtos.ReviewView> reviewViews = new ArrayList<>(allReviews.size());
        int approvals = 0;
        List<String> blockingNames = new ArrayList<>();
        for (PrReviewEntity review : allReviews) {
            boolean superseded = !latestIds.contains(review.getId());
            RoomMemberEntity member = review.getReviewerMemberId() == null
                    ? null : members.get(review.getReviewerMemberId());
            String name = member == null ? "（已离开）" : member.getAvatarLabel();
            if (!superseded && PrReviewEntity.STATE_APPROVED.equals(review.getState())) {
                approvals++;
            }
            if (!superseded && PrReviewEntity.STATE_CHANGES_REQUESTED.equals(review.getState())) {
                blockingNames.add(name);
            }
            reviewViews.add(new PrReviewDtos.ReviewView(
                    review.getId(),
                    review.getReviewerMemberId() == null ? null : String.valueOf(review.getReviewerMemberId()),
                    name,
                    review.getState(),
                    review.getBody(),
                    review.getSubmittedAt() == null ? null : review.getSubmittedAt().toEpochSecond() * 1000L,
                    superseded));
        }

        List<PrCommentEntity> comments = prCommentMapper.selectList(new LambdaQueryWrapper<PrCommentEntity>()
                .eq(PrCommentEntity::getPullRequestId, pr.getId())
                .orderByAsc(PrCommentEntity::getId));
        List<PrReviewDtos.CommentView> commentViews = new ArrayList<>(comments.size());
        for (PrCommentEntity comment : comments) {
            RoomMemberEntity member = comment.getAuthorMemberId() == null
                    ? null : members.get(comment.getAuthorMemberId());
            commentViews.add(new PrReviewDtos.CommentView(
                    comment.getId(),
                    comment.getReviewId(),
                    comment.getAuthorMemberId() == null ? null : String.valueOf(comment.getAuthorMemberId()),
                    member == null ? "（已离开）" : member.getAvatarLabel(),
                    comment.getBody(),
                    comment.getCommentKind(),
                    comment.getFilePath(),
                    comment.getDiffSide(),
                    comment.getOriginalLine(),
                    comment.getCurrentLine(),
                    Boolean.TRUE.equals(comment.getIsOutdated()),
                    shortSha(comment.getAnchorCommitSha()),
                    comment.getCreatedAt() == null ? null : comment.getCreatedAt().toEpochSecond() * 1000L));
        }

        reviewViews.sort(Comparator.comparing(PrReviewDtos.ReviewView::id));
        return new PrReviewDtos.Thread(
                pr.getNumber(), pr.getStatus(), pr.getMergeable(),
                !blockingNames.isEmpty(), blockingNames, approvals, reviewViews, commentViews);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? sha : sha.substring(0, 7);
    }

    /** 房间裸 origin 的路径：沿用 {@link SandboxManager} 的确定性布局，不依赖 CollabService 的内存句柄。 */
    private Path originPath(String roomId) {
        return sandboxManager.roomsBaseDir().resolve(roomId + ".git");
    }

    private RoomEntity requireRoom(String roomId) {
        RoomEntity row = roomMapper.selectOne(new LambdaQueryWrapper<RoomEntity>()
                .eq(RoomEntity::getPublicId, roomId));
        if (row == null) {
            throw new CommandException("房间不存在：" + roomId);
        }
        return row;
    }

    /** 越权防护：评审动作一律要求当前登录用户确实是该房间成员（§7 同 CollabService）。 */
    private RoomMemberEntity requireMember(Long roomRowId, Long userId) {
        RoomMemberEntity row = roomMemberMapper.selectOne(new LambdaQueryWrapper<RoomMemberEntity>()
                .eq(RoomMemberEntity::getRoomId, roomRowId)
                .eq(RoomMemberEntity::getUserId, userId));
        if (row == null) {
            throw new CommandException("你不是该房间成员");
        }
        return row;
    }

    private PullRequestEntity requirePr(Long roomRowId, int number) {
        PullRequestEntity row = pullRequestMapper.selectOne(new LambdaQueryWrapper<PullRequestEntity>()
                .eq(PullRequestEntity::getRoomId, roomRowId)
                .eq(PullRequestEntity::getNumber, number));
        if (row == null) {
            throw new CommandException("PR 不存在：#" + number);
        }
        return row;
    }
}
