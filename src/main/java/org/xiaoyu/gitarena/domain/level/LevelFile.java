package org.xiaoyu.gitarena.domain.level;

import java.util.List;
import java.util.Map;

/**
 * 关卡文件契约（docs/level-spec.md，specVersion=1）——LevelFile 及其全部子结构。
 *
 * <p>以嵌套 record 承载 §2–§6 的形状；Jackson 直接绑定。语义校验（seq 唯一、拓扑序、goal 可达、
 * 断言可用等 §4.4/§5.1）由 LevelValidator 在加载时执行，形状偏差由 ObjectMapper 的
 * FAIL_ON_UNKNOWN_PROPERTIES 拦截。
 *
 * <p>初始与目标共用 {@link Commit}/{@link Ref}/{@link Head}/{@link Remote}；差异（goal 不许 files/author、
 * workingDir 形状不同）由类型区分（{@link InitialWorkingDir} vs {@link StatusWorkingDir}）与校验器保证。
 */
public record LevelFile(
        int specVersion,
        Meta meta,
        InitialSpec initial,
        GoalSpec goal,
        SolutionSpec solution,
        List<Hint> hints
) {

    public record Meta(
            String slug,
            String title,
            String description,
            String category,
            Integer difficulty,
            String mode,
            Integer orderIndex,
            String visibility
    ) {}

    /** 初始仓库构建蓝图（§3–§4）。 */
    public record InitialSpec(
            List<Commit> commits,
            List<Ref> branches,
            List<Ref> tags,
            Head head,
            List<Remote> remotes,
            InitialWorkingDir workingDir
    ) {}

    /** 目标校验规则（§5）。 */
    public record GoalSpec(
            GoalGraph graph,
            MatchPolicy match,
            List<Assertion> assertions
    ) {}

    /** 目标图（§5.1 变体：commit 无 files/author，workingDir 为状态断言形状）。 */
    public record GoalGraph(
            List<Commit> commits,
            List<Ref> branches,
            List<Ref> tags,
            Head head,
            List<Remote> remotes,
            StatusWorkingDir workingDir
    ) {}

    /** 提交节点。files/author 仅 initial 有效；goal 侧禁止（校验器保证）。 */
    public record Commit(
            String seq,
            List<String> parents,
            String message,
            String author,
            Map<String, String> files
    ) {}

    public record Ref(String name, String target) {}

    public record Head(String type, String ref) {}

    public record Remote(String name, List<RemoteBranch> branches) {}

    public record RemoteBranch(String name, String target, String tracked) {}

    /** 初始工作区：checkout HEAD 后对工作区的覆盖写 + 其中被 git add 的子集（§4.2）。 */
    public record InitialWorkingDir(Map<String, String> files, List<String> staged) {}

    /** 目标工作区断言：三个路径集合（§5.1）。 */
    public record StatusWorkingDir(List<String> staged, List<String> modified, List<String> untracked) {}

    /** 匹配策略；字段可空，缺省值见 {@link #withDefaults()}（§5.2）。 */
    public record MatchPolicy(
            Boolean allowExtraCommits,
            Boolean allowExtraBranches,
            Boolean allowExtraTags,
            Boolean ignoreMessages,
            Boolean compareHead,
            Boolean compareWorkingDir
    ) {
        public static MatchPolicy defaults() {
            return new MatchPolicy(false, false, false, true, true, false);
        }

        /** 用契约默认值填充缺省字段。 */
        public MatchPolicy withDefaults() {
            return new MatchPolicy(
                    allowExtraCommits != null ? allowExtraCommits : false,
                    allowExtraBranches != null ? allowExtraBranches : false,
                    allowExtraTags != null ? allowExtraTags : false,
                    ignoreMessages != null ? ignoreMessages : true,
                    compareHead != null ? compareHead : true,
                    compareWorkingDir != null ? compareWorkingDir : false
            );
        }
    }

    /** 断言（§5.4）：扁平承载全部类型的参数，匹配器按 type 分派，校验器按 type 查必填。 */
    public record Assertion(
            String type,
            String name,
            String path,
            String pattern,
            String remote,
            Integer number
    ) {}

    public record SolutionSpec(List<SolutionStep> steps, String notes) {}

    /** 参考解步骤：run（git 命令，走白名单链路）或 writeFile（模拟编辑文件）二选一（§6）。 */
    public record SolutionStep(String run, WriteFile writeFile) {}

    public record WriteFile(String path, String content) {}

    public record Hint(Integer tier, String body, Integer costPoints) {}
}
