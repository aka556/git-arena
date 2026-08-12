package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Spec → GitGraph 转换（仅用于前端展示）。把关卡的 initial / goal 图转成 GitGraph 形状，
 * 以 seq 作 id、合成时间戳，让前端用同一 GitGraphView 渲染"初始预览"与"目标图"（§6.3 对照展示）。
 *
 * <p>这不是仓库真相——真实"当前图"由 GraphMapper 从沙盒读出（短 hash）；本转换只服务目标/预览渲染。
 */
@Component
public class SpecGraphConverter {

    private static final long BASE_EPOCH = 1_700_000_000L;

    public GitGraph fromInitial(LevelFile.InitialSpec initial) {
        List<GitGraph.CommitNode> commits = commits(initial.commits());
        GitGraph.WorkingDir wd = initialWorkingDir(initial.workingDir());
        return new GitGraph(
                GitGraph.CONTRACT_VERSION,
                commits,
                branches(initial.branches()),
                tags(initial.tags()),
                head(initial.head()),
                remotes(initial.remotes()),
                wd
        );
    }

    public GitGraph fromGoal(LevelFile.GoalGraph goal) {
        return new GitGraph(
                GitGraph.CONTRACT_VERSION,
                commits(goal.commits()),
                branches(goal.branches()),
                tags(goal.tags()),
                head(goal.head()),
                remotes(goal.remotes()),
                goalWorkingDir(goal.workingDir())
        );
    }

    private List<GitGraph.CommitNode> commits(List<LevelFile.Commit> specCommits) {
        List<GitGraph.CommitNode> result = new ArrayList<>();
        if (specCommits == null) {
            return result;
        }
        int index = 0;
        for (LevelFile.Commit c : specCommits) {
            result.add(new GitGraph.CommitNode(
                    c.seq(),
                    c.parents() == null ? List.of() : c.parents(),
                    c.message() != null ? c.message() : "commit " + c.seq(),
                    c.author() != null ? c.author() : "arena",
                    BASE_EPOCH + 60L * index,
                    c.seq(),
                    false // spec 图是声明出来的目标/初始形状，不存在不可达提交
            ));
            index++;
        }
        // 与快照一致：最新在前
        List<GitGraph.CommitNode> reversed = new ArrayList<>(result);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private List<GitGraph.BranchRef> branches(List<LevelFile.Ref> refs) {
        List<GitGraph.BranchRef> result = new ArrayList<>();
        if (refs != null) {
            for (LevelFile.Ref r : refs) {
                result.add(new GitGraph.BranchRef(r.name(), r.target(), false));
            }
        }
        return result;
    }

    private List<GitGraph.TagRef> tags(List<LevelFile.Ref> refs) {
        List<GitGraph.TagRef> result = new ArrayList<>();
        if (refs != null) {
            for (LevelFile.Ref r : refs) {
                result.add(new GitGraph.TagRef(r.name(), r.target()));
            }
        }
        return result;
    }

    private GitGraph.HeadRef head(LevelFile.Head head) {
        if (head == null) {
            return new GitGraph.HeadRef("branch", "main");
        }
        return new GitGraph.HeadRef(head.type(), head.ref());
    }

    private List<GitGraph.RemoteRef> remotes(List<LevelFile.Remote> remotes) {
        List<GitGraph.RemoteRef> result = new ArrayList<>();
        if (remotes != null) {
            for (LevelFile.Remote r : remotes) {
                List<GitGraph.RemoteBranch> rb = new ArrayList<>();
                for (LevelFile.RemoteBranch b : r.branches()) {
                    rb.add(new GitGraph.RemoteBranch(b.name(), b.target()));
                }
                result.add(new GitGraph.RemoteRef(r.name(), rb));
            }
        }
        return result;
    }

    /** 初始工作区预览：staged 取声明子集，其余 files 键视为 untracked（无 HEAD 时贴近真实）。 */
    private GitGraph.WorkingDir initialWorkingDir(LevelFile.InitialWorkingDir wd) {
        if (wd == null) {
            return new GitGraph.WorkingDir(List.of(), List.of(), List.of());
        }
        List<String> staged = wd.staged() == null ? List.of() : wd.staged();
        List<String> untracked = new ArrayList<>();
        if (wd.files() != null) {
            for (var e : wd.files().entrySet()) {
                if (e.getValue() != null && !staged.contains(e.getKey())) {
                    untracked.add(e.getKey());
                }
            }
        }
        return new GitGraph.WorkingDir(new ArrayList<>(staged), List.of(), untracked);
    }

    private GitGraph.WorkingDir goalWorkingDir(LevelFile.StatusWorkingDir wd) {
        if (wd == null) {
            return new GitGraph.WorkingDir(List.of(), List.of(), List.of());
        }
        return new GitGraph.WorkingDir(
                wd.staged() == null ? List.of() : wd.staged(),
                wd.modified() == null ? List.of() : wd.modified(),
                wd.untracked() == null ? List.of() : wd.untracked()
        );
    }
}
