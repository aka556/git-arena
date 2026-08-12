package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 目标匹配器（docs/level-spec.md §5.3 规范性算法）。输入实际仓库的 GitGraph 快照与关卡 GoalSpec，
 * 输出是否达成 + 结构化差异。
 *
 * <p>核心：以 goal 引用为锚点把 goal 的 seq 绑定到实际 commit id，沿<b>有序 parents</b>同步下钻
 * （首父顺序有意义，能校验 merge/rebase 方向）；再按 match 策略与 assertions 逐项检查。
 * 不可达提交不参与（GraphMapper 只收可达提交，§6.3 幽灵节点）。
 */
@Component
public class GoalMatcher {

    public MatchResult match(GitGraph snapshot, LevelFile.GoalSpec goal, FileAtHead fileReader) {
        return match(snapshot, goal, fileReader, PrMergedCheck.NONE);
    }

    public MatchResult match(GitGraph snapshot, LevelFile.GoalSpec goal, FileAtHead fileReader, PrMergedCheck prCheck) {
        List<String> reasons = new ArrayList<>();
        LevelFile.MatchPolicy policy = (goal.match() == null ? LevelFile.MatchPolicy.defaults() : goal.match()).withDefaults();
        LevelFile.GoalGraph gg = goal.graph();

        // ---- 索引实际快照 ----
        // 幽灵提交（reflog 里还留着但已无引用可达）只是呈现用，不参与校验：
        // 否则 reset/rebase/切离游离线之后，关卡会因为"多出几个提交"永远过不了（level-spec.md §5.3 规则 1）
        Map<String, GitGraph.CommitNode> actualById = new HashMap<>();
        int reachableCount = 0;
        for (GitGraph.CommitNode c : snapshot.commits()) {
            if (c.unreachable()) {
                continue;
            }
            actualById.put(c.id(), c);
            reachableCount++;
        }
        Map<String, String> actualBranch = new HashMap<>(); // name -> target id
        for (GitGraph.BranchRef b : snapshot.branches()) {
            actualBranch.put(b.name(), b.target());
        }
        Map<String, String> actualTag = new HashMap<>();
        for (GitGraph.TagRef t : snapshot.tags()) {
            actualTag.put(t.name(), t.target());
        }
        // remote/branch -> tracking 指向（快照即 tracking 视角，level-spec.md §5.3 规则 5）
        Map<String, String> actualRemote = new HashMap<>();
        for (GitGraph.RemoteRef r : snapshot.remotes()) {
            for (GitGraph.RemoteBranch rb : r.branches()) {
                actualRemote.put(r.name() + "/" + rb.name(), rb.target());
            }
        }

        // ---- 索引 goal ----
        Map<String, List<String>> goalParents = new HashMap<>();
        Map<String, LevelFile.Commit> goalBySeq = new HashMap<>();
        for (LevelFile.Commit c : nz(gg.commits())) {
            goalBySeq.put(c.seq(), c);
            goalParents.put(c.seq(), nz(c.parents()));
        }

        // ---- 绑定：seq <-> id ----
        Map<String, String> seqToId = new HashMap<>();
        Map<String, String> idToSeq = new HashMap<>();
        Deque<String[]> queue = new ArrayDeque<>();
        Binder binder = new Binder(seqToId, idToSeq, queue, reasons);

        // 锚定分支
        for (LevelFile.Ref b : nz(gg.branches())) {
            String actualTargetId = actualBranch.get(b.name());
            if (actualTargetId == null) {
                reasons.add("缺少分支：" + b.name());
            } else {
                binder.bind(b.target(), actualTargetId);
            }
        }
        // 锚定标签
        for (LevelFile.Ref t : nz(gg.tags())) {
            String actualId = actualTag.get(t.name());
            if (actualId == null) {
                reasons.add("缺少标签：" + t.name());
            } else {
                binder.bind(t.target(), actualId);
            }
        }
        // 锚定 detached HEAD
        if (gg.head() != null && "detached".equals(gg.head().type())) {
            if (!"detached".equals(snapshot.head().type())) {
                reasons.add("HEAD 应为游离（detached）状态");
            } else {
                binder.bind(gg.head().ref(), snapshot.head().ref());
            }
        }
        // 锚定远程跟踪分支（goal 给出 remotes 才比较）
        for (LevelFile.Remote r : nz(gg.remotes())) {
            for (LevelFile.RemoteBranch rb : nz(r.branches())) {
                String actualId = actualRemote.get(r.name() + "/" + rb.name());
                if (actualId == null) {
                    reasons.add("远程跟踪分支缺失：" + r.name() + "/" + rb.name() + "（是否还没 fetch/push？）");
                } else {
                    binder.bind(rb.target(), actualId);
                }
            }
        }

        // 下钻绑定（沿有序 parents）
        while (!queue.isEmpty()) {
            String[] pair = queue.poll();
            String seq = pair[0];
            String id = pair[1];
            List<String> gParents = goalParents.getOrDefault(seq, List.of());
            GitGraph.CommitNode actual = actualById.get(id);
            List<String> aParents = actual == null ? List.of() : actual.parents();
            if (gParents.size() != aParents.size()) {
                reasons.add("提交 " + seq + " 的父提交结构不符（期望 " + gParents.size() + " 个父，实际 " + aParents.size() + " 个）");
                continue;
            }
            for (int i = 0; i < gParents.size(); i++) {
                binder.bind(gParents.get(i), aParents.get(i));
            }
        }

        // ---- 每个 goal 提交都须被绑定（目标结构完整存在） ----
        for (String seq : goalBySeq.keySet()) {
            if (!seqToId.containsKey(seq)) {
                reasons.add("目标提交结构缺失：" + seq);
            }
        }

        // ---- allowExtra* ----
        if (!policy.allowExtraCommits()) {
            int goalCount = goalBySeq.size();
            if (reachableCount != goalCount || idToSeq.size() != reachableCount) {
                reasons.add("提交数不符：期望 " + goalCount + " 个，实际可达 " + reachableCount + " 个");
            }
        }
        if (!policy.allowExtraBranches()) {
            Set<String> extra = new HashSet<>(actualBranch.keySet());
            nz(gg.branches()).forEach(b -> extra.remove(b.name()));
            if (!extra.isEmpty()) {
                reasons.add("存在多余分支：" + extra);
            }
        }
        if (!policy.allowExtraTags()) {
            Set<String> extra = new HashSet<>(actualTag.keySet());
            nz(gg.tags()).forEach(t -> extra.remove(t.name()));
            if (!extra.isEmpty()) {
                reasons.add("存在多余标签：" + extra);
            }
        }

        // ---- compareHead ----
        if (policy.compareHead() && gg.head() != null) {
            checkHead(snapshot.head(), gg.head(), seqToId, reasons);
        }

        // ---- ignoreMessages=false ----
        if (!policy.ignoreMessages()) {
            for (Map.Entry<String, String> e : seqToId.entrySet()) {
                LevelFile.Commit goalCommit = goalBySeq.get(e.getKey());
                GitGraph.CommitNode actual = actualById.get(e.getValue());
                if (goalCommit != null && goalCommit.message() != null && actual != null
                        && !goalCommit.message().trim().equals(actual.message().trim())) {
                    reasons.add("提交 " + e.getKey() + " 的信息不符");
                }
            }
        }

        // ---- compareWorkingDir ----
        if (policy.compareWorkingDir()) {
            checkWorkingDir(snapshot.workingDir(), gg.workingDir(), reasons);
        }

        // ---- assertions ----
        for (LevelFile.Assertion a : nz(goal.assertions())) {
            evaluateAssertion(a, actualBranch, actualRemote, fileReader, prCheck, reasons);
        }

        return reasons.isEmpty() ? MatchResult.pass() : MatchResult.fail(reasons);
    }

    private void checkHead(GitGraph.HeadRef actual, LevelFile.Head goal, Map<String, String> seqToId, List<String> reasons) {
        if (!actual.type().equals(goal.type())) {
            reasons.add("HEAD 类型不符：期望 " + goal.type() + "，实际 " + actual.type());
            return;
        }
        if ("branch".equals(goal.type())) {
            if (!actual.ref().equals(goal.ref())) {
                reasons.add("HEAD 应在分支 " + goal.ref() + "，实际在 " + actual.ref());
            }
        } else {
            // detached：goal.ref 是 seq，实际 ref 是 id
            String boundId = seqToId.get(goal.ref());
            if (boundId == null || !boundId.equals(actual.ref())) {
                reasons.add("游离 HEAD 未指向目标提交 " + goal.ref());
            }
        }
    }

    private void checkWorkingDir(GitGraph.WorkingDir actual, LevelFile.StatusWorkingDir goal, List<String> reasons) {
        List<String> gStaged = goal == null || goal.staged() == null ? List.of() : goal.staged();
        List<String> gModified = goal == null || goal.modified() == null ? List.of() : goal.modified();
        List<String> gUntracked = goal == null || goal.untracked() == null ? List.of() : goal.untracked();
        if (!new HashSet<>(actual.staged()).equals(new HashSet<>(gStaged))) {
            reasons.add("暂存区（staged）不符");
        }
        if (!new HashSet<>(actual.modified()).equals(new HashSet<>(gModified))) {
            reasons.add("已修改（modified）文件集不符");
        }
        if (!new HashSet<>(actual.untracked()).equals(new HashSet<>(gUntracked))) {
            reasons.add("未跟踪（untracked）文件集不符");
        }
    }

    private void evaluateAssertion(LevelFile.Assertion a, Map<String, String> actualBranch,
                                   Map<String, String> actualRemote,
                                   FileAtHead fileReader, PrMergedCheck prCheck, List<String> reasons) {
        switch (a.type()) {
            case "branchExists" -> {
                if (!actualBranch.containsKey(a.name())) {
                    reasons.add("断言失败：应存在分支 " + a.name());
                }
            }
            case "branchPushed" -> {
                String remote = a.remote() == null ? "origin" : a.remote();
                String tracking = actualRemote.get(remote + "/" + a.name());
                String local = actualBranch.get(a.name());
                if (tracking == null) {
                    reasons.add("断言失败：" + remote + "/" + a.name() + " 不存在（还没 push？）");
                } else if (local == null || !local.equals(tracking)) {
                    reasons.add("断言失败：" + remote + "/" + a.name() + " 与本地 " + a.name() + " 指向不一致");
                }
            }
            case "prMerged" -> {
                if (!prCheck.isMerged(a.number())) {
                    reasons.add("断言失败：" + (a.number() == null ? "尚无 PR 被合并" : "PR #" + a.number() + " 未合并"));
                }
            }
            case "fileAtHeadContains" -> {
                Optional<String> content = fileReader.read(a.path());
                if (content.isEmpty() || !Pattern.compile(a.pattern()).matcher(content.get()).find()) {
                    reasons.add("断言失败：" + a.path() + " 应匹配 /" + a.pattern() + "/");
                }
            }
            case "fileAtHeadNotContains" -> {
                Optional<String> content = fileReader.read(a.path());
                if (content.isPresent() && Pattern.compile(a.pattern()).matcher(content.get()).find()) {
                    reasons.add("断言失败：" + a.path() + " 不应包含 /" + a.pattern() + "/");
                }
            }
            default -> reasons.add("断言类型暂不支持：" + a.type());
        }
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 双向绑定 seq<->id，冲突记入 reasons；新绑定入队待下钻其父。 */
    private static final class Binder {
        private final Map<String, String> seqToId;
        private final Map<String, String> idToSeq;
        private final Deque<String[]> queue;
        private final List<String> reasons;

        Binder(Map<String, String> seqToId, Map<String, String> idToSeq, Deque<String[]> queue, List<String> reasons) {
            this.seqToId = seqToId;
            this.idToSeq = idToSeq;
            this.queue = queue;
            this.reasons = reasons;
        }

        void bind(String seq, String id) {
            String existingId = seqToId.get(seq);
            if (existingId != null) {
                if (!existingId.equals(id)) {
                    reasons.add("提交 " + seq + " 绑定冲突（同一目标节点对应多个实际提交）");
                }
                return;
            }
            String existingSeq = idToSeq.get(id);
            if (existingSeq != null && !existingSeq.equals(seq)) {
                reasons.add("实际提交被目标 " + existingSeq + " 与 " + seq + " 同时占用");
                return;
            }
            seqToId.put(seq, id);
            idToSeq.put(id, seq);
            queue.add(new String[]{seq, id});
        }
    }
}
