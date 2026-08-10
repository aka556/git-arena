package org.xiaoyu.gitarena.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.dto.PrDiff;
import org.xiaoyu.gitarena.domain.dto.PrReviewDtos;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomScenarioView;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.service.CollabService;
import org.xiaoyu.gitarena.service.PrReviewService;

/**
 * 协作房间入口（薄 controller，§7）：建房/加入、共享图、成员命令、PR 开/合。
 * 实时同步经 WebSocket 主题 {@code /topic/rooms/{roomId}}，不在此暴露。
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final CollabService collabService;
    private final PrReviewService prReviewService;

    @PostMapping
    public Result<RoomJoinResponse> create(@Valid @RequestBody RoomRequests.CreateRoom request) {
        return Result.ok(collabService.createRoom(request));
    }

    @PostMapping("/join")
    public Result<RoomJoinResponse> join(@Valid @RequestBody RoomRequests.JoinRoom request) {
        return Result.ok(collabService.joinRoom(request));
    }

    @GetMapping("/{roomId}")
    public Result<RoomView> view(@PathVariable String roomId) {
        return Result.ok(collabService.roomView(roomId));
    }

    @GetMapping("/{roomId}/origin-graph")
    public Result<GitGraph> originGraph(@PathVariable String roomId) {
        return Result.ok(collabService.originGraph(roomId));
    }

    /** 房间场景关卡（目标说明 + 目标图）；无场景返回 data=null。 */
    @GetMapping("/{roomId}/scenario")
    public Result<RoomScenarioView> scenario(@PathVariable String roomId) {
        return Result.ok(collabService.scenario(roomId));
    }

    /** 成员对自己的克隆跑场景关卡校验（prMerged 查本房 PR）。 */
    @PostMapping("/{roomId}/members/{memberId}/validate")
    public Result<ValidateResponse> validateScenario(@PathVariable String roomId,
                                                     @PathVariable String memberId) {
        return Result.ok(collabService.validateScenario(roomId, memberId));
    }

    /** 成员命令（走统一命令链路 §3）。 */
    @PostMapping("/{roomId}/members/{memberId}/exec")
    public Result<CommandResponse> exec(@PathVariable String roomId,
                                        @PathVariable String memberId,
                                        @Valid @RequestBody RoomRequests.Exec request) {
        return Result.ok(collabService.memberExec(roomId, memberId, request.command()));
    }

    @PostMapping("/{roomId}/pulls")
    public Result<RoomView> openPr(@PathVariable String roomId,
                                   @Valid @RequestBody RoomRequests.OpenPr request) {
        return Result.ok(collabService.openPullRequest(roomId, request));
    }

    @PostMapping("/{roomId}/pulls/{number}/merge")
    public Result<RoomView> mergePr(@PathVariable String roomId,
                                    @PathVariable int number,
                                    @Valid @RequestBody RoomRequests.MergePr request) {
        return Result.ok(collabService.mergePullRequest(roomId, number, request.memberId()));
    }

    // ---- PR 评审（database.md §4.4/§4.5） ----

    /** PR 三点差异，供评审面板渲染与行级评论定位。 */
    @GetMapping("/{roomId}/pulls/{number}/diff")
    public Result<PrDiff> prDiff(@PathVariable String roomId, @PathVariable int number) {
        return Result.ok(prReviewService.diff(roomId, number));
    }

    /** PR 的评审全貌：评审列表 + 评论列表 + 合并闸门结论。 */
    @GetMapping("/{roomId}/pulls/{number}/reviews")
    public Result<PrReviewDtos.Thread> prThread(@PathVariable String roomId, @PathVariable int number) {
        return Result.ok(prReviewService.thread(roomId, number));
    }

    /** 提交一次评审（approve / request changes / comment），可附带一批行级评论。 */
    @PostMapping("/{roomId}/pulls/{number}/reviews")
    public Result<PrReviewDtos.Thread> submitReview(@PathVariable String roomId,
                                                    @PathVariable int number,
                                                    @Valid @RequestBody PrReviewDtos.SubmitReview request) {
        return Result.ok(prReviewService.submitReview(roomId, number, request));
    }

    /** 追加一条评论：带 filePath+diffSide+line 即行级，否则整体。 */
    @PostMapping("/{roomId}/pulls/{number}/comments")
    public Result<PrReviewDtos.Thread> addComment(@PathVariable String roomId,
                                                  @PathVariable int number,
                                                  @Valid @RequestBody PrReviewDtos.AddComment request) {
        return Result.ok(prReviewService.addComment(roomId, number, request));
    }
}
