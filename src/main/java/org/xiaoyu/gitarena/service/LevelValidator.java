package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 关卡语义校验（docs/level-spec.md §4.4 / §5.1，fail-closed）。在加载时对已由 Jackson 绑定成形的
 * LevelFile 做结构性检查：seq 唯一与拓扑序、引用完整、head 合法、goal 无 files/author 且全可达、
 * 断言类型在本里程碑可用。发现问题即抛 {@link LevelException}（汇总所有问题）。
 *
 * <p>M2 已实现断言：{@code branchExists / fileAtHeadContains / fileAtHeadNotContains}；
 * {@code branchPushed / prMerged} 属 M3，出现即拒绝（§5.4）。
 */
@Component
public class LevelValidator {

    private static final Pattern SEQ = Pattern.compile("^C[1-9][0-9]{0,3}$");
    private static final Pattern REF_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*$");
    private static final Set<String> SUPPORTED_ASSERTIONS =
            Set.of("branchExists", "fileAtHeadContains", "fileAtHeadNotContains");
    private static final Set<String> KNOWN_FUTURE_ASSERTIONS = Set.of("branchPushed", "prMerged");

    public void validate(LevelFile level) {
        List<String> problems = new ArrayList<>();
        String slug = level.meta() != null ? level.meta().slug() : "?";

        if (level.specVersion() != 1) {
            problems.add("specVersion 必须为 1，实际 " + level.specVersion());
        }
        validateMeta(level.meta(), problems);
        if (level.initial() != null) {
            validateInitial(level.initial(), problems);
        } else {
            problems.add("缺少 initial");
        }
        if (level.goal() != null) {
            validateGoal(level.goal(), level.meta(), problems);
        } else {
            problems.add("缺少 goal");
        }

        if (!problems.isEmpty()) {
            throw new LevelException(slug, problems);
        }
    }

    private void validateMeta(LevelFile.Meta meta, List<String> problems) {
        if (meta == null) {
            problems.add("缺少 meta");
            return;
        }
        if (isBlank(meta.slug())) problems.add("meta.slug 不能为空");
        if (isBlank(meta.title())) problems.add("meta.title 不能为空");
        if (isBlank(meta.category())) problems.add("meta.category 不能为空");
        if (meta.difficulty() == null || meta.difficulty() < 1 || meta.difficulty() > 5) {
            problems.add("meta.difficulty 须为 1–5");
        }
        if (!"solo".equals(meta.mode()) && !"collab".equals(meta.mode())) {
            problems.add("meta.mode 须为 solo 或 collab");
        }
    }

    private void validateInitial(LevelFile.InitialSpec initial, List<String> problems) {
        Set<String> seqs = validateCommits(initial.commits(), false, "initial", problems);
        validateRefs(initial.branches(), seqs, "initial.branches", problems);
        validateRefs(initial.tags(), seqs, "initial.tags", problems);
        validateHead(initial.head(), seqs, isEmpty(initial.commits()), "initial", problems);
        validateWorkingDir(initial.workingDir(), problems);
    }

    private void validateGoal(LevelFile.GoalSpec goal, LevelFile.Meta meta, List<String> problems) {
        LevelFile.GoalGraph gg = goal.graph();
        if (gg == null) {
            problems.add("缺少 goal.graph");
            return;
        }
        Set<String> seqs = validateCommits(gg.commits(), true, "goal", problems);
        validateRefs(gg.branches(), seqs, "goal.branches", problems);
        validateRefs(gg.tags(), seqs, "goal.tags", problems);
        validateHead(gg.head(), seqs, isEmpty(gg.commits()), "goal", problems);
        validateGoalReachability(gg, seqs, problems);
        validateAssertions(goal.assertions(), meta, problems);
    }

    /** 校验 commits：seq 唯一/格式、拓扑序、parents 合法；goal 侧禁止 files/author。返回全部 seq。 */
    private Set<String> validateCommits(List<LevelFile.Commit> commits, boolean isGoal, String ctx, List<String> problems) {
        Set<String> seen = new HashSet<>();
        if (commits == null) {
            return seen;
        }
        for (LevelFile.Commit c : commits) {
            if (c.seq() == null || !SEQ.matcher(c.seq()).matches()) {
                problems.add(ctx + " 提交 seq 非法：" + c.seq());
                continue;
            }
            if (!seen.add(c.seq())) {
                problems.add(ctx + " 提交 seq 重复：" + c.seq());
            }
            Set<String> parentSet = new HashSet<>();
            for (String p : nz(c.parents())) {
                if (!seen.contains(p)) {
                    problems.add(ctx + " 提交 " + c.seq() + " 的父 " + p + " 未在其之前出现（拓扑序/无环要求）");
                }
                if (!parentSet.add(p)) {
                    problems.add(ctx + " 提交 " + c.seq() + " 的父提交重复：" + p);
                }
            }
            if (isGoal) {
                if (c.files() != null) problems.add("goal 提交 " + c.seq() + " 不允许 files（内容目标用 assertions）");
                if (c.author() != null) problems.add("goal 提交 " + c.seq() + " 不允许 author");
            } else if (c.files() != null) {
                c.files().keySet().forEach(path -> validatePath(path, ctx + " files", problems));
            }
        }
        return seen;
    }

    private void validateRefs(List<LevelFile.Ref> refs, Set<String> seqs, String ctx, List<String> problems) {
        if (refs == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (LevelFile.Ref r : refs) {
            if (r.name() == null || !REF_NAME.matcher(r.name()).matches() || r.name().contains("..")) {
                problems.add(ctx + " 引用名非法：" + r.name());
            } else if (!names.add(r.name())) {
                problems.add(ctx + " 引用名重复：" + r.name());
            }
            if (!seqs.contains(r.target())) {
                problems.add(ctx + " 引用 " + r.name() + " 指向不存在的提交：" + r.target());
            }
        }
    }

    private void validateHead(LevelFile.Head head, Set<String> seqs, boolean commitsEmpty, String ctx, List<String> problems) {
        if (head == null) {
            problems.add(ctx + " 缺少 head");
            return;
        }
        if ("branch".equals(head.type())) {
            if (head.ref() == null || !REF_NAME.matcher(head.ref()).matches()) {
                problems.add(ctx + " head 分支名非法：" + head.ref());
            }
            // 分支型 head 允许指向未出生分支——仅当无提交（§4.3 空仓库）
        } else if ("detached".equals(head.type())) {
            if (commitsEmpty || !seqs.contains(head.ref())) {
                problems.add(ctx + " 游离 head 指向不存在的提交：" + head.ref());
            }
        } else {
            problems.add(ctx + " head.type 须为 branch 或 detached，实际 " + head.type());
        }
    }

    private void validateWorkingDir(LevelFile.InitialWorkingDir wd, List<String> problems) {
        if (wd == null) {
            return;
        }
        Set<String> fileKeys = wd.files() == null ? Set.of() : wd.files().keySet();
        if (wd.files() != null) {
            fileKeys.forEach(path -> validatePath(path, "initial.workingDir.files", problems));
        }
        for (String s : nz(wd.staged())) {
            if (!fileKeys.contains(s)) {
                problems.add("initial.workingDir.staged 含未在 files 中声明的路径：" + s);
            }
        }
    }

    /** goal 每个提交必须从某个 goal 引用（branch/tag/detached head）可达（§5.1 无游离节点）。 */
    private void validateGoalReachability(LevelFile.GoalGraph gg, Set<String> seqs, List<String> problems) {
        Map<String, List<String>> parents = new java.util.HashMap<>();
        for (LevelFile.Commit c : nz(gg.commits())) {
            parents.put(c.seq(), nz(c.parents()));
        }
        Set<String> reachable = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (LevelFile.Ref b : nz(gg.branches())) stack.push(b.target());
        for (LevelFile.Ref t : nz(gg.tags())) stack.push(t.target());
        if (gg.head() != null && "detached".equals(gg.head().type())) stack.push(gg.head().ref());
        while (!stack.isEmpty()) {
            String seq = stack.pop();
            if (seq == null || !reachable.add(seq)) continue;
            for (String p : parents.getOrDefault(seq, List.of())) stack.push(p);
        }
        for (String seq : seqs) {
            if (!reachable.contains(seq)) {
                problems.add("goal 提交 " + seq + " 从任何引用都不可达（游离节点，疑似笔误）");
            }
        }
    }

    private void validateAssertions(List<LevelFile.Assertion> assertions, LevelFile.Meta meta, List<String> problems) {
        for (LevelFile.Assertion a : nz(assertions)) {
            if (a.type() == null) {
                problems.add("断言缺少 type");
                continue;
            }
            if (KNOWN_FUTURE_ASSERTIONS.contains(a.type())) {
                problems.add("断言类型 " + a.type() + " 属 M3，尚未支持");
                continue;
            }
            if (!SUPPORTED_ASSERTIONS.contains(a.type())) {
                problems.add("未知断言类型：" + a.type());
                continue;
            }
            switch (a.type()) {
                case "branchExists" -> {
                    if (isBlank(a.name())) problems.add("branchExists 断言缺少 name");
                }
                case "fileAtHeadContains", "fileAtHeadNotContains" -> {
                    if (isBlank(a.path())) problems.add(a.type() + " 断言缺少 path");
                    else validatePath(a.path(), a.type(), problems);
                    if (isBlank(a.pattern())) problems.add(a.type() + " 断言缺少 pattern");
                }
                default -> { /* 已在上面拦截 */ }
            }
        }
    }

    private void validatePath(String path, String ctx, List<String> problems) {
        if (isBlank(path) || path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                || path.contains("..") || path.contains("\\")
                || path.startsWith(".git") || path.contains("/.git") || path.endsWith("/")) {
            problems.add(ctx + " 路径非法：" + path);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
