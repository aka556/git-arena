package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.PrDiff;
import org.xiaoyu.gitarena.domain.dto.PrReviewDtos;

/**
 * PR 评审服务（database.md §4.4/§4.5，CLAUDE.md §4 P1「PR 流程模拟：发起、评审、合并」的评审环节）。
 *
 * <p>鉴权同 {@link CollabService}：一律以登录用户为准，memberId 只是展示标识不是凭证。
 * 评审者必须是房间成员；PR 作者不能批准自己的 PR（自审自批会让评审流程失去教学意义）。
 *
 * <p>本服务<b>不依赖</b> {@link CollabService}，房间/PR 全部从库里查、origin 路径按沙盒布局推导，
 * 从而让 {@code CollabServiceImpl} 可以单向依赖它做合并闸门与锚点重算，不成环。
 */
public interface PrReviewService {

    /** PR 的三点差异（merge-base(target, source) → source HEAD），供行级评论定位与渲染。 */
    PrDiff diff(String roomId, int number);

    /** 提交一次评审（可附带一批行级评论）。 */
    PrReviewDtos.Thread submitReview(String roomId, int number, PrReviewDtos.SubmitReview request);

    /** 追加一条评论（带定位信息即行级，否则整体）。 */
    PrReviewDtos.Thread addComment(String roomId, int number, PrReviewDtos.AddComment request);

    /** 读取 PR 的评审全貌。 */
    PrReviewDtos.Thread thread(String roomId, int number);

    /**
     * 重算某房间下所有开放 PR 的行级评论锚点（§4.5：源分支一动，行号就得重新对齐）。
     * 由 {@code CollabServiceImpl} 在 push / merge 成功后调用。
     *
     * @return 实际更新的评论条数
     */
    int relocateAnchors(String roomId);

    /**
     * 合并闸门：是否存在<b>未被更晚评审取代</b>的 changes_requested。
     * 有则挡住合并——评审若不影响合并，"评审"就只是摆设，教不会协作里的把关环节。
     */
    boolean isBlockedByChangesRequested(Long pullRequestId);

    /**
     * 批量取评审统计，供房间快照的 PR 列表打徽标。
     * 批量而非逐条：{@code toView} 每次广播都会调用，逐条查会随 PR 数量 N+1 放大。
     */
    java.util.Map<Long, Stats> statsOf(java.util.Collection<Long> pullRequestIds);

    /** PR 的评审概览。{@code approvals} 只数每位评审者的最新一次评审。 */
    record Stats(int approvals, boolean changesRequested, int commentCount) {

        public static final Stats EMPTY = new Stats(0, false, 0);
    }
}
