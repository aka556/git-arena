package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.dto.PrDiff;
import org.xiaoyu.gitarena.domain.dto.PrReviewDtos;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CurrentUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR 评审闭环（database.md §4.4/§4.5，CLAUDE.md §4 P1「PR 流程：发起、评审、合并」）。
 *
 * <p>不加 {@code @Transactional}：协作链路要真实提交（沙盒目录、裸 origin 都是文件系统副作用），
 * 放进测试事务既测不到真实语义，也会让后续 push 看不到前面的数据。改为手工登记用户、事后 CASCADE 清理。
 *
 * <p>鉴权走 {@link CurrentUser} ThreadLocal（平时由 AuthInterceptor 设置），测试里显式切换身份，
 * 正好也把"以登录用户为准、memberId 不是凭证"这条约束一并覆盖。
 */
@SpringBootTest
class PrReviewIntegrationTest {

    @Autowired
    private CollabService collabService;
    @Autowired
    private PrReviewService prReviewService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        CurrentUser.clear();
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        createdUserIds.clear();
    }

    @Test
    void inline_comment_anchors_to_a_real_diff_line_and_survives_unrelated_push() {
        Fixture f = openPrWithFeatureBranch();

        as(f.reviewerId);
        PrDiff diff = prReviewService.diff(f.roomId, 1);
        assertThat(diff.files()).extracting(PrDiff.FileDiff::path).contains("feature.txt");
        assertThat(diff.headSha()).isNotBlank();

        PrReviewDtos.Thread thread = prReviewService.addComment(f.roomId, 1,
                new PrReviewDtos.AddComment("这一行要加注释", "feature.txt", "new", 1));
        PrReviewDtos.CommentView comment = onlyInline(thread);
        assertThat(comment.currentLine()).isEqualTo(1);
        assertThat(comment.outdated()).isFalse();
        assertThat(comment.anchorSha()).isNotBlank(); // 锚点定格了"针对哪个版本"

        // 作者在锚点行「上方」插入新内容并推送：行号该跟着下移，而不是死守 1
        as(f.authorId);
        exec(f, "git checkout feature");
        exec(f, "echo header > feature.txt");
        exec(f, "echo first-line >> feature.txt");
        exec(f, "echo second-line >> feature.txt");
        exec(f, "git add -A");
        exec(f, "git commit -m \"prepend header\"");
        exec(f, "git push origin feature");

        PrReviewDtos.CommentView after = onlyInline(prReviewService.thread(f.roomId, 1));
        assertThat(after.originalLine()).isEqualTo(1); // 不可变事实不被改写
        assertThat(after.outdated()).isFalse();
        assertThat(after.currentLine()).isEqualTo(2);  // 派生值被重算
    }

    @Test
    void comment_is_marked_outdated_when_its_anchor_line_is_rewritten() {
        Fixture f = openPrWithFeatureBranch();

        as(f.reviewerId);
        prReviewService.addComment(f.roomId, 1,
                new PrReviewDtos.AddComment("这行有问题", "feature.txt", "new", 1));

        as(f.authorId);
        exec(f, "git checkout feature");
        exec(f, "echo totally-different > feature.txt");
        exec(f, "git add -A");
        exec(f, "git commit -m \"rewrite\"");
        exec(f, "git push origin feature");

        PrReviewDtos.CommentView after = onlyInline(prReviewService.thread(f.roomId, 1));
        // 锚点行没了：如实标"已过时"，而不是猜一个行号把评论挂到无关代码上
        assertThat(after.outdated()).isTrue();
        assertThat(after.currentLine()).isNull();
        assertThat(after.originalLine()).isEqualTo(1);
    }

    @Test
    void changes_requested_blocks_merge_until_the_same_reviewer_approves() {
        Fixture f = openPrWithFeatureBranch();

        as(f.reviewerId);
        PrReviewDtos.Thread requested = prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("changes_requested", "先改改", null));
        assertThat(requested.blocked()).isTrue();
        assertThat(requested.blockingReviewers()).isNotEmpty();

        as(f.ownerId);
        assertThatThrownBy(() -> collabService.mergePullRequest(f.roomId, 1, f.ownerMemberId))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("请求修改");

        // 同一评审者改判 approve：最新一次评审取代先前的，闸门解除
        as(f.reviewerId);
        PrReviewDtos.Thread approved = prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("approved", "可以了", null));
        assertThat(approved.blocked()).isFalse();
        assertThat(approved.approvals()).isEqualTo(1);
        assertThat(approved.reviews()).filteredOn(PrReviewDtos.ReviewView::superseded).hasSize(1);

        as(f.ownerId);
        RoomView merged = collabService.mergePullRequest(f.roomId, 1, f.ownerMemberId);
        assertThat(merged.pullRequests()).singleElement()
                .satisfies(pr -> assertThat(pr.status()).isEqualTo("merged"));
    }

    @Test
    void author_cannot_approve_own_pull_request_but_may_comment() {
        Fixture f = openPrWithFeatureBranch();

        as(f.authorId);
        assertThatThrownBy(() -> prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("approved", "自己批准", null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不能评审自己");

        PrReviewDtos.Thread thread = prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("commented", "补充说明", null));
        assertThat(thread.blocked()).isFalse();
        assertThat(thread.approvals()).isZero();
    }

    @Test
    void non_member_cannot_read_or_review_a_pull_request() {
        Fixture f = openPrWithFeatureBranch();
        Long outsiderId = guest();

        as(outsiderId);
        assertThatThrownBy(() -> prReviewService.diff(f.roomId, 1))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不是该房间成员");
        assertThatThrownBy(() -> prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("approved", "路过", null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不是该房间成员");
    }

    @Test
    void inline_comment_must_anchor_to_a_line_present_in_the_diff() {
        Fixture f = openPrWithFeatureBranch();

        as(f.reviewerId);
        // 第 999 行不在 diff 里：拒绝写入，而不是存一个从一开始就无法定位的锚点
        assertThatThrownBy(() -> prReviewService.addComment(f.roomId, 1,
                new PrReviewDtos.AddComment("越界", "feature.txt", "new", 999)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("无法作为评论锚点");
        assertThatThrownBy(() -> prReviewService.addComment(f.roomId, 1,
                new PrReviewDtos.AddComment("查无此文件", "nope.txt", "new", 1)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不在本 PR 的差异里");
    }

    @Test
    void room_snapshot_carries_review_badges_for_the_pr_list() {
        Fixture f = openPrWithFeatureBranch();

        as(f.reviewerId);
        prReviewService.submitReview(f.roomId, 1,
                new PrReviewDtos.SubmitReview("changes_requested", "等一下", null));
        prReviewService.addComment(f.roomId, 1, new PrReviewDtos.AddComment("整体意见", null, null, null));

        as(f.ownerId);
        RoomView.PullRequestView view = collabService.roomView(f.roomId).pullRequests().get(0);
        assertThat(view.changesRequested()).isTrue();
        assertThat(view.commentCount()).isEqualTo(1);
        assertThat(view.approvals()).isZero();
    }

    // ---- 夹具 ----

    /** 房主=评审者，另一位成员=PR 作者，feature 分支已推送并开好 PR #1。 */
    private Fixture openPrWithFeatureBranch() {
        Long ownerId = guest();
        Long authorId = guest();

        as(ownerId);
        RoomJoinResponse owner = collabService.createRoom(
                new RoomRequests.CreateRoom("review-room-" + UUID.randomUUID().toString().substring(0, 6),
                        "房主", null));
        String roomId = owner.room().roomId();

        as(authorId);
        RoomJoinResponse author = collabService.joinRoom(
                new RoomRequests.JoinRoom(owner.room().joinCode(), "作者"));

        Fixture f = new Fixture(roomId, ownerId, authorId, ownerId,
                owner.memberId(), author.memberId());

        as(authorId);
        exec(f, "git checkout -b feature");
        exec(f, "echo first-line > feature.txt");
        exec(f, "echo second-line >> feature.txt");
        exec(f, "git add feature.txt");
        exec(f, "git commit -m \"add feature\"");
        exec(f, "git push origin feature");
        collabService.openPullRequest(roomId,
                new RoomRequests.OpenPr(author.memberId(), "加个功能", "描述", "feature", "main"));
        return f;
    }

    /** 以 PR 作者身份在其克隆沙盒里执行命令。 */
    private void exec(Fixture f, String command) {
        collabService.memberExec(f.roomId, f.authorMemberId, command);
    }

    private PrReviewDtos.CommentView onlyInline(PrReviewDtos.Thread thread) {
        List<PrReviewDtos.CommentView> inline = thread.comments().stream()
                .filter(c -> "inline".equals(c.commentKind()))
                .toList();
        assertThat(inline).hasSize(1);
        return inline.get(0);
    }

    private void as(Long userId) {
        CurrentUser.set(userId);
    }

    private Long guest() {
        AuthDtos.AuthResponse response = authService.guest();
        createdUserIds.add(response.user().id());
        return response.user().id();
    }

    private record Fixture(String roomId, Long ownerId, Long authorId, Long reviewerId,
                           String ownerMemberId, String authorMemberId) {
    }
}
