package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** PR 评审与评论的请求/响应 DTO（database.md §4.4/§4.5）。 */
public final class PrReviewDtos {

    private PrReviewDtos() {
    }

    /**
     * 提交一次评审。可只写总评，也可带一批行级评论一并提交（GitHub 的 review 语义）。
     *
     * @param state approved / changes_requested / commented
     */
    public record SubmitReview(
            @NotBlank @Pattern(regexp = "approved|changes_requested|commented",
                    message = "评审结论只能是 approved / changes_requested / commented")
            String state,
            @Size(max = 5000) String body,
            List<InlineComment> comments
    ) {}

    /**
     * 一条行级评论的定位信息。锚点事实由后端在写入时定格——
     * 客户端只说"评在哪一侧的哪一行"，{@code anchor_commit_sha} 与 {@code diff_hunk}
     * 一律由后端按当时的真实 diff 填充，不接受客户端自报（否则锚点就不可信了）。
     */
    public record InlineComment(
            @NotBlank @Size(max = 1024) String filePath,
            @NotBlank @Pattern(regexp = "old|new", message = "diffSide 只能是 old 或 new") String diffSide,
            @NotNull Integer line,
            @NotBlank @Size(max = 5000) String body
    ) {}

    /** 追加一条评论：不带定位信息即为整体评论（general）。 */
    public record AddComment(
            @NotBlank @Size(max = 5000) String body,
            @Size(max = 1024) String filePath,
            String diffSide,
            Integer line
    ) {}

    /** PR 的评审全貌：评审列表 + 评论列表 + 合并闸门结论。 */
    public record Thread(
            int number,
            String status,
            String mergeable,
            /** 是否被"请求修改"挡住合并（见 §4.4 落地约定）。 */
            boolean blocked,
            /** 挡住合并的评审者展示名，供 UI 说明原因。 */
            List<String> blockingReviewers,
            int approvals,
            List<ReviewView> reviews,
            List<CommentView> comments
    ) {}

    public record ReviewView(
            Long id,
            String reviewerMemberId,
            String reviewerName,
            String state,
            String body,
            Long submittedAt,
            /** 该评审是否已被同一评审者的更晚一次评审取代（取代后不再参与闸门判定）。 */
            boolean superseded
    ) {}

    public record CommentView(
            Long id,
            Long reviewId,
            String authorMemberId,
            String authorName,
            String body,
            String commentKind,
            String filePath,
            String diffSide,
            Integer originalLine,
            /** 后端针对当前 HEAD 重算出的行号；null 表示已无法定位。 */
            Integer currentLine,
            boolean outdated,
            /** 写评论时源分支 HEAD 的短 sha，仅作"针对哪个版本"的展示。 */
            String anchorSha,
            Long createdAt
    ) {}
}
