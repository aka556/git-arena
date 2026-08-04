package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协作回路集成测（CLAUDE.md §9：为多人推拉、PR 流程写集成测试）。不启 Spring、不连 DB，
 * 直接 new git/ 组件，在临时目录上跑真实 JGit：
 * <ol>
 *   <li>建裸 origin，两个成员各克隆一份；</li>
 *   <li>成员 A 建 feature 分支提交并 push → origin 出现 feature；</li>
 *   <li>成员 B fetch 能看到 A 的 feature（多人推拉互相可见——§1 协作差异化）；</li>
 *   <li>PR 把 feature 合进 main（裸仓 in-core 合并），main 前进；</li>
 *   <li>成员 B pull 后本地 main 追上（拿到合并结果）。</li>
 * </ol>
 */
class CollabFlowIntegrationTest {

    @TempDir
    Path tmp;

    private RoomRepo roomRepo;
    private GitExecutor executor;
    private GraphMapper mapper;
    private CommandParser parser;
    private Path origin;

    @BeforeEach
    void setUp() {
        PathGuard guard = new PathGuard();
        roomRepo = new RoomRepo();
        executor = new GitExecutor(guard);
        mapper = new GraphMapper();
        parser = new CommandParser();
        origin = tmp.resolve("room.git");
        roomRepo.createRoomOrigin(origin);
    }

    @Test
    void twoMembersPushPullAndSeeEachOther() {
        SandboxRepo alice = cloneMember("alice");
        SandboxRepo bob = cloneMember("bob");

        // origin 初始只有 main（base 提交）
        assertThat(mapper.mapBareOrigin(origin).branches())
                .extracting(GitGraph.BranchRef::name).containsExactly("main");

        // Alice：建 feature、提交、推送
        run(alice, "git checkout -b feature");
        run(alice, "echo hello > a.txt");
        run(alice, "git add a.txt");
        run(alice, "git commit -m \"alice work\"");
        ExecOutput push = run(alice, "git push origin feature");
        assertThat(push.ok()).isTrue();

        // origin 现在有 feature 分支
        assertThat(mapper.mapBareOrigin(origin).branches())
                .extracting(GitGraph.BranchRef::name).contains("main", "feature");

        // Bob fetch 后，其克隆能看到 origin/feature（多人推拉互相可见）
        run(bob, "git fetch origin");
        GitGraph bobGraph = mapper.map(bob);
        assertThat(bobGraph.remotes()).flatExtracting(GitGraph.RemoteRef::branches)
                .extracting(GitGraph.RemoteBranch::name).contains("feature");
    }

    @Test
    void pullRequestMergeAdvancesOriginAndMembersCanPull() {
        SandboxRepo alice = cloneMember("alice");
        SandboxRepo bob = cloneMember("bob");

        run(alice, "git checkout -b feature");
        run(alice, "echo hello > feature.txt");
        run(alice, "git add feature.txt");
        run(alice, "git commit -m \"feature work\"");
        run(alice, "git push origin feature");

        String originMainBefore = originMainTip();

        // PR：feature -> main，在裸 origin 上 in-core 合并
        RoomRepo.MergeOutcome outcome = roomRepo.mergePullRequest(origin, "feature", "main", "Merge PR #1");
        assertThat(outcome).isEqualTo(RoomRepo.MergeOutcome.MERGED);
        assertThat(originMainTip()).isNotEqualTo(originMainBefore); // main 前进了

        // Bob pull 后本地 main 拿到合并结果（feature.txt 进入 main 历史）
        run(bob, "git pull origin main");
        // bob 在 main 上，feature.txt 应已存在（合并带过来）
        assertThat(java.nio.file.Files.exists(bob.root().resolve("feature.txt"))).isTrue();
    }

    @Test
    void conflictingBranchesReportConflictNotMerge() {
        SandboxRepo alice = cloneMember("alice");
        SandboxRepo bob = cloneMember("bob");

        // 两人都改 README.md 同一处，各推到不同分支
        run(alice, "git checkout -b a-branch");
        run(alice, "echo alice-change > README.md");
        run(alice, "git add README.md");
        run(alice, "git commit -m \"alice\"");
        run(alice, "git push origin a-branch");

        run(bob, "git checkout -b b-branch");
        run(bob, "echo bob-change > README.md");
        run(bob, "git add README.md");
        run(bob, "git commit -m \"bob\"");
        run(bob, "git push origin b-branch");

        // 先把 a-branch 合进 main
        assertThat(roomRepo.mergePullRequest(origin, "a-branch", "main", "PR1"))
                .isEqualTo(RoomRepo.MergeOutcome.MERGED);
        // 再合 b-branch 就冲突了（同一行）
        assertThat(roomRepo.probeMergeable(origin, "b-branch", "main"))
                .isEqualTo(RoomRepo.MergeOutcome.CONFLICT);
    }

    // ---- helpers ----

    private SandboxRepo cloneMember(String name) {
        Path dir = tmp.resolve("member-" + name);
        roomRepo.cloneMember(origin, dir);
        return new SandboxRepo(name, dir);
    }

    private ExecOutput run(SandboxRepo sandbox, String command) {
        ParsedCommand cmd = parser.parse(command);
        return executor.execute(sandbox, cmd);
    }

    private String originMainTip() {
        GitGraph g = mapper.mapBareOrigin(origin);
        return g.branches().stream().filter(b -> b.name().equals("main")).findFirst().orElseThrow().target();
    }
}
