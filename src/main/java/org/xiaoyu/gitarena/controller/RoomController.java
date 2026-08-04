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
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.service.CollabService;

/**
 * 协作房间入口（薄 controller，§7）：建房/加入、共享图、成员命令、PR 开/合。
 * 实时同步经 WebSocket 主题 {@code /topic/rooms/{roomId}}，不在此暴露。
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final CollabService collabService;

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
}
