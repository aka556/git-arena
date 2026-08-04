package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.CommandResponse;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomView;
import org.xiaoyu.gitarena.domain.graph.GitGraph;

/**
 * 协作房间服务（§3 CollabService，M3 阶段B）：建房/加入、共享 origin 图、成员命令、PR 开/列/合并。
 * 状态变更后向 {@code /topic/rooms/{roomId}} 广播最新房间快照（WebSocketConfig）。
 */
public interface CollabService {

    RoomJoinResponse createRoom(RoomRequests.CreateRoom request);

    RoomJoinResponse joinRoom(RoomRequests.JoinRoom request);

    RoomView roomView(String roomId);

    /** 房间共享 origin 的提交图（协作者共同看的那张远程图）。 */
    GitGraph originGraph(String roomId);

    /** 成员在自己的克隆沙盒上执行命令（走统一命令链路 §3）；push 成功后广播，让他人可见。 */
    CommandResponse memberExec(String roomId, String memberId, String command);

    RoomView openPullRequest(String roomId, RoomRequests.OpenPr request);

    RoomView mergePullRequest(String roomId, int number, String memberId);
}
