package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.dto.RoomJoinResponse;
import org.xiaoyu.gitarena.domain.dto.RoomRequests;
import org.xiaoyu.gitarena.domain.dto.RoomScenarioView;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CurrentUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 房间场景关卡闭环（M4：第 5 章 collab 关卡的落地机制）。
 *
 * <p>collab 关卡的 initial 描述的是房间<b>共享裸 origin</b>：建房时按 spec 物化，成员克隆即拿到同一起点；
 * 校验跑在成员自己的克隆上，{@code prMerged} 断言查本房间的 PR。这条链路无法在单进程用 solution 重放
 * （需两人交互），故以本集成测覆盖——对应 LevelSelfCheckTest 里对 collab 关卡的跳过。
 *
 * <p>不加 {@code @Transactional}：沙盒目录与裸 origin 都是文件系统副作用，事务里既测不真也会让 push 失效。
 */
@SpringBootTest
class RoomScenarioIntegrationTest {

    private static final String SCENARIO = "pr-merge-conflict";

    @Autowired
    private CollabService collabService;
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
    void scenario_room_origin_is_materialized_from_level_spec() {
        Long ownerId = guest();
        as(ownerId);
        RoomJoinResponse owner = createScenarioRoom();

        // 共享 origin 直接就是关卡 initial 的图：main(C2) 与 bob-feature(C3) 从 C1 分叉
        GitGraph origin = collabService.originGraph(owner.room().roomId());
        assertThat(origin.commits()).hasSize(3);
        assertThat(origin.branches()).extracting(GitGraph.BranchRef::name)
                .containsExactlyInAnyOrder("main", "bob-feature");

        RoomScenarioView scenario = collabService.scenario(owner.room().roomId());
        assertThat(scenario).isNotNull();
        assertThat(scenario.slug()).isEqualTo(SCENARIO);
        assertThat(scenario.goalGraph().commits()).hasSize(4); // 目标图含合并提交 C4
    }

    @Test
    void free_room_has_no_scenario_and_validate_is_rejected() {
        Long ownerId = guest();
        as(ownerId);
        RoomJoinResponse owner = collabService.createRoom(
                new RoomRequests.CreateRoom("free-" + suffix(), "房主", null));

        assertThat(collabService.scenario(owner.room().roomId())).isNull();
        assertThatThrownBy(() -> collabService.validateScenario(owner.room().roomId(), owner.memberId()))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("没有场景关卡");
    }

    @Test
    void solo_level_cannot_be_used_as_room_scenario() {
        Long ownerId = guest();
        as(ownerId);
        // fail-closed：单人关卡的 initial 含 remotes/workingDir，拿来当共享 origin 会语义错乱
        assertThatThrownBy(() -> collabService.createRoom(
                new RoomRequests.CreateRoom("bad-" + suffix(), "房主", "first-commit")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不是协作关卡");
    }

    @Test
    void validate_fails_before_work_and_passes_after_conflict_resolved_and_pr_merged() {
        Long ownerId = guest();
        Long bobId = guest();

        as(ownerId);
        RoomJoinResponse owner = createScenarioRoom();
        String roomId = owner.room().roomId();

        as(bobId);
        RoomJoinResponse bob = collabService.joinRoom(
                new RoomRequests.JoinRoom(owner.room().joinCode(), "bob"));

        // 零步：既没解决冲突也没有 PR，必须不通过
        as(ownerId);
        ValidateResponse before = collabService.validateScenario(roomId, owner.memberId());
        assertThat(before.passed()).isFalse();
        assertThat(before.reasons()).isNotEmpty();

        // bob 开 PR（bob-feature → main），此时自动合并会冲突
        as(bobId);
        collabService.openPullRequest(roomId,
                new RoomRequests.OpenPr(bob.memberId(), "bob 的改动", "冲突 PR", "bob-feature", "main"));

        // 房主在自己的克隆里手工解决冲突：merge 触发冲突 → 改文件 → 提交 → 推送
        as(ownerId);
        String ownerMember = owner.memberId();
        collabService.memberExec(roomId, ownerMember, "git fetch origin");
        collabService.memberExec(roomId, ownerMember, "git merge origin/bob-feature");
        collabService.memberExec(roomId, ownerMember, "echo \"hello alice and bob\" > greeting.txt");
        collabService.memberExec(roomId, ownerMember, "git add greeting.txt");
        collabService.memberExec(roomId, ownerMember, "git commit -m \"resolve conflict\"");
        collabService.memberExec(roomId, ownerMember, "git push origin main");

        // 冲突已解决但 PR 尚未合并：prMerged 断言仍应挡住
        ValidateResponse beforeMerge = collabService.validateScenario(roomId, ownerMember);
        assertThat(beforeMerge.passed()).isFalse();
        assertThat(String.join(" ", beforeMerge.reasons())).contains("PR");

        // main 已包含 bob-feature，PR 此时可顺利合并
        collabService.mergePullRequest(roomId, 1, ownerMember);

        ValidateResponse after = collabService.validateScenario(roomId, ownerMember);
        assertThat(after.passed())
                .as("解决冲突并合并 PR 后应通关，差异：%s", after.reasons())
                .isTrue();
    }

    @Test
    void member_cannot_validate_as_someone_else() {
        Long ownerId = guest();
        Long bobId = guest();

        as(ownerId);
        RoomJoinResponse owner = createScenarioRoom();
        as(bobId);
        collabService.joinRoom(new RoomRequests.JoinRoom(owner.room().joinCode(), "bob"));

        // memberId 是标识不是凭证（§CLAUDE.md M3 落库约定）：不能拿房主的 memberId 校验
        assertThatThrownBy(() -> collabService.validateScenario(owner.room().roomId(), owner.memberId()))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("不能以他人身份");
    }

    private RoomJoinResponse createScenarioRoom() {
        return collabService.createRoom(
                new RoomRequests.CreateRoom("scenario-" + suffix(), "房主", SCENARIO));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private void as(Long userId) {
        CurrentUser.set(userId);
    }

    private Long guest() {
        AuthDtos.AuthResponse response = authService.guest();
        createdUserIds.add(response.user().id());
        return response.user().id();
    }
}
