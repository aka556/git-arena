package org.xiaoyu.gitarena.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 命令执行引擎（CLAUDE.md §7）。git 操作一律走 JGit 编程式 API，辅助命令 touch/echo 走 Java Files API；
 * <b>全程不经 shell、不用 ProcessBuilder、不拼接用户字符串</b>。所有文件路径经 {@link PathGuard} 限定在沙盒内。
 *
 * <p>M1 覆盖 {@code init/add/commit/log/status} + {@code touch/echo}；
 * M2 增开 {@code branch/checkout/switch/merge}。
 */
@Slf4j
@Component
public class GitExecutor {

    private static final String AUTHOR_NAME = "player";
    private static final String AUTHOR_EMAIL = "player@git-arena.local";

    private final PathGuard pathGuard;

    public GitExecutor(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public ExecOutput execute(SandboxRepo sandbox, ParsedCommand cmd) {
        return switch (cmd.program()) {
            case "git" -> runGit(sandbox, cmd);
            case "touch" -> runTouch(sandbox, cmd.args());
            case "echo" -> runEcho(sandbox, cmd.args());
            default -> throw new CommandException("不允许的命令：" + cmd.program());
        };
    }

    // ---- git ---------------------------------------------------------------

    private ExecOutput runGit(SandboxRepo sandbox, ParsedCommand cmd) {
        String sub = cmd.subcommand();
        if ("init".equals(sub)) {
            return gitInit(sandbox);
        }
        if (!sandbox.isInitialized()) {
            throw new CommandException("当前目录不是 git 仓库，请先执行：git init");
        }
        try (Git git = Git.open(sandbox.root().toFile())) {
            return switch (sub) {
                case "add" -> gitAdd(sandbox, git, cmd.args());
                case "commit" -> gitCommit(git, cmd.args());
                case "log" -> gitLog(git);
                case "status" -> gitStatus(git);
                case "branch" -> gitBranch(git, cmd.args());
                case "checkout" -> gitCheckout(git, cmd.args());
                case "switch" -> gitSwitch(git, cmd.args());
                case "merge" -> gitMerge(git, cmd.args());
                case "tag" -> gitTag(git, cmd.args());
                case "rebase" -> gitRebase(git, cmd.args());
                default -> throw new CommandException("暂不支持的 git 子命令：" + sub);
            };
        } catch (IOException e) {
            log.warn("git open failed for {}: {}", sandbox.sessionId(), e.getMessage());
            throw new CommandException("无法打开仓库");
        } catch (GitAPIException e) {
            throw new CommandException("git " + sub + " 执行失败：" + e.getMessage());
        }
    }

    private ExecOutput gitInit(SandboxRepo sandbox) {
        boolean existed = sandbox.isInitialized();
        try (Git git = Git.init()
                .setDirectory(sandbox.root().toFile())
                .setInitialBranch("main")
                .call()) {
            // 写入固定身份：merge 等自动提交走仓库 UserConfig，没有它 JGit 会退回系统用户名（不确定）
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", AUTHOR_NAME);
            config.setString("user", null, "email", AUTHOR_EMAIL);
            config.save();
            return ExecOutput.ok(existed
                    ? "Reinitialized existing Git repository in .git/"
                    : "Initialized empty Git repository in .git/ (branch main)");
        } catch (GitAPIException | IOException e) {
            throw new CommandException("git init 失败：" + e.getMessage());
        }
    }

    private ExecOutput gitAdd(SandboxRepo sandbox, Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty()) {
            throw new CommandException("Nothing specified. 用法：git add <文件> 或 git add .");
        }
        var add = git.add();
        boolean any = false;
        for (String a : args) {
            if (".".equals(a) || "-A".equals(a) || "--all".equals(a)) {
                add.addFilepattern(".");
            } else {
                // 校验路径合法且在沙盒内（防越权）；filepattern 用规范化后的相对形式
                Path resolved = pathGuard.resolveInside(sandbox.root(), a);
                String rel = sandbox.root().toAbsolutePath().normalize()
                        .relativize(resolved).toString().replace('\\', '/');
                add.addFilepattern(rel);
            }
            any = true;
        }
        if (any) {
            add.call();
        }
        return ExecOutput.ok(""); // git add 成功时无输出
    }

    private ExecOutput gitCommit(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        boolean amend = args.contains("--amend");
        String message = extractMessage(args);

        if (amend) {
            if (repo.resolve(Constants.HEAD) == null) {
                throw new CommandException("尚无提交，无法 --amend");
            }
            // --amend 修改上一次提交：可只改信息（无暂存改动亦可）；message 缺省沿用原信息
            var commitCmd = git.commit().setAmend(true)
                    .setAuthor(AUTHOR_NAME, AUTHOR_EMAIL)
                    .setCommitter(AUTHOR_NAME, AUTHOR_EMAIL);
            if (message != null) {
                commitCmd.setMessage(message);
            }
            RevCommit commit = commitCmd.call();
            return ExecOutput.ok("[" + repo.getBranch() + " " + shortId(commit.getName()) + "] "
                    + firstLine(commit.getShortMessage()) + " (amended)");
        }

        if (message == null) {
            throw new CommandException("请提供提交信息：git commit -m \"信息\"");
        }
        Status status = git.status().call();
        boolean staged = !status.getAdded().isEmpty()
                || !status.getChanged().isEmpty()
                || !status.getRemoved().isEmpty();
        if (!staged) {
            throw new CommandException("nothing to commit（没有已暂存的改动，请先 git add）");
        }
        try {
            RevCommit commit = git.commit()
                    .setMessage(message)
                    .setAuthor(AUTHOR_NAME, AUTHOR_EMAIL)
                    .setCommitter(AUTHOR_NAME, AUTHOR_EMAIL)
                    .setAllowEmpty(false)
                    .call();
            String branch = repo.getBranch();
            return ExecOutput.ok("[" + branch + " " + shortId(commit.getName()) + "] " + firstLine(message));
        } catch (EmptyCommitException e) {
            throw new CommandException("nothing to commit（没有已暂存的改动，请先 git add）");
        }
    }

    private ExecOutput gitLog(Git git) throws GitAPIException {
        try {
            Iterable<RevCommit> commits = git.log().call();
            StringBuilder sb = new StringBuilder();
            for (RevCommit c : commits) {
                sb.append(shortId(c.getName())).append(' ').append(firstLine(c.getFullMessage())).append('\n');
            }
            if (sb.length() == 0) {
                return ExecOutput.ok("(暂无提交)");
            }
            return ExecOutput.ok(sb.toString().stripTrailing());
        } catch (NoHeadException e) {
            throw new CommandException("当前分支还没有任何提交");
        }
    }

    private ExecOutput gitStatus(Git git) throws GitAPIException {
        Status s = git.status().call();
        String branch;
        try {
            branch = git.getRepository().getBranch();
        } catch (Exception e) {
            branch = "main";
        }

        StringBuilder sb = new StringBuilder("On branch ").append(branch).append('\n');
        boolean clean = true;

        if (!s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty()) {
            clean = false;
            sb.append("Changes to be committed:\n");
            appendEach(sb, "  new file:   ", s.getAdded());
            appendEach(sb, "  modified:   ", s.getChanged());
            appendEach(sb, "  deleted:    ", s.getRemoved());
        }
        if (!s.getModified().isEmpty() || !s.getMissing().isEmpty()) {
            clean = false;
            sb.append("Changes not staged for commit:\n");
            appendEach(sb, "  modified:   ", s.getModified());
            appendEach(sb, "  deleted:    ", s.getMissing());
        }
        if (!s.getUntracked().isEmpty()) {
            clean = false;
            sb.append("Untracked files:\n");
            appendEach(sb, "  ", s.getUntracked());
        }
        if (clean) {
            sb.append("nothing to commit, working tree clean");
        }
        return ExecOutput.ok(sb.toString().stripTrailing());
    }

    // ---- branch / checkout / switch / merge (M2) --------------------------

    private ExecOutput gitBranch(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        if (args.isEmpty()) {
            return branchList(git, repo);
        }
        String first = args.get(0);
        if ("-d".equals(first) || "-D".equals(first)) {
            if (args.size() < 2) {
                throw new CommandException("用法：git branch -d <分支名>");
            }
            String name = args.get(1);
            boolean force = "-D".equals(first);
            try {
                git.branchDelete().setBranchNames(name).setForce(force).call();
                return ExecOutput.ok("Deleted branch " + name);
            } catch (CannotDeleteCurrentBranchException e) {
                throw new CommandException("无法删除当前所在分支：" + name);
            } catch (NotMergedException e) {
                throw new CommandException("分支 " + name + " 尚未完全合并；确认要删除请用 git branch -D " + name);
            } catch (RefNotFoundException e) {
                throw new CommandException("分支不存在：" + name);
            }
        }
        // git branch <name>：在 HEAD 处创建分支（不切换）
        String name = first;
        if (repo.resolve(Constants.HEAD) == null) {
            throw new CommandException("尚无提交，无法创建分支（请先 git commit）");
        }
        try {
            git.branchCreate().setName(name).call();
            return ExecOutput.ok(""); // 与 git 一致：创建成功无输出
        } catch (RefAlreadyExistsException e) {
            throw new CommandException("分支已存在：" + name);
        } catch (InvalidRefNameException e) {
            throw new CommandException("非法分支名：" + name);
        }
    }

    private ExecOutput branchList(Git git, Repository repo) throws GitAPIException, IOException {
        String fullBranch = repo.getFullBranch();
        boolean detached = fullBranch == null || !fullBranch.startsWith("refs/heads/");
        String current = detached ? null : Repository.shortenRefName(fullBranch);

        StringBuilder sb = new StringBuilder();
        if (detached) {
            ObjectId head = repo.resolve(Constants.HEAD);
            sb.append("* (HEAD detached at ")
                    .append(head == null ? "?" : shortId(head.getName()))
                    .append(")\n");
        }
        for (Ref b : git.branchList().call()) {
            String name = Repository.shortenRefName(b.getName());
            sb.append(name.equals(current) ? "* " : "  ").append(name).append('\n');
        }
        if (sb.length() == 0) {
            return ExecOutput.ok("(无分支)");
        }
        return ExecOutput.ok(sb.toString().stripTrailing());
    }

    private ExecOutput gitCheckout(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        if (args.isEmpty()) {
            throw new CommandException("用法：git checkout <分支|提交> 或 git checkout -b <新分支>");
        }
        if ("-b".equals(args.get(0))) {
            return createAndSwitch(git, repo, args.size() < 2 ? null : args.get(1));
        }
        String target = args.get(0);
        boolean isBranch = repo.findRef(Constants.R_HEADS + target) != null;
        try {
            if (isBranch) {
                git.checkout().setName(target).call();
                return ExecOutput.ok("Switched to branch '" + target + "'");
            }
            // 非分支：尝试按提交切换 → detached HEAD
            ObjectId oid = repo.resolve(target);
            if (oid == null) {
                throw new CommandException("未找到分支或提交：" + target);
            }
            git.checkout().setName(oid.getName()).call();
            return ExecOutput.ok("Note: switching to '" + target + "'.\n"
                    + "HEAD is now at " + shortId(oid.getName()) + " (detached HEAD)");
        } catch (CheckoutConflictException e) {
            throw new CommandException("有未提交改动会被覆盖，请先提交或撤销：" + e.getConflictingPaths());
        } catch (RefNotFoundException e) {
            throw new CommandException("未找到分支或提交：" + target);
        }
    }

    private ExecOutput gitSwitch(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        if (args.isEmpty()) {
            throw new CommandException("用法：git switch <分支> 或 git switch -c <新分支>");
        }
        if ("-c".equals(args.get(0))) {
            return createAndSwitch(git, repo, args.size() < 2 ? null : args.get(1));
        }
        String name = args.get(0);
        if (repo.findRef(Constants.R_HEADS + name) == null) {
            throw new CommandException("没有名为 '" + name + "' 的分支（switch 只能切到已存在分支，新建用 -c）");
        }
        try {
            git.checkout().setName(name).call();
            return ExecOutput.ok("Switched to branch '" + name + "'");
        } catch (CheckoutConflictException e) {
            throw new CommandException("有未提交改动会被覆盖，请先提交或撤销：" + e.getConflictingPaths());
        }
    }

    private ExecOutput createAndSwitch(Git git, Repository repo, String name) throws GitAPIException, IOException {
        if (name == null) {
            throw new CommandException("请提供新分支名");
        }
        if (repo.resolve(Constants.HEAD) == null) {
            throw new CommandException("尚无提交，无法创建分支（请先 git commit）");
        }
        try {
            git.checkout().setCreateBranch(true).setName(name).call();
            return ExecOutput.ok("Switched to a new branch '" + name + "'");
        } catch (RefAlreadyExistsException e) {
            throw new CommandException("分支已存在：" + name);
        } catch (InvalidRefNameException e) {
            throw new CommandException("非法分支名：" + name);
        }
    }

    private ExecOutput gitMerge(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        boolean squash = args.contains("--squash");
        String name = firstNonFlag(args);
        if (name == null) {
            throw new CommandException("用法：git merge <分支>");
        }
        if (repo.resolve(Constants.HEAD) == null) {
            throw new CommandException("尚无提交，无法合并");
        }
        ObjectId other = repo.resolve(name);
        if (other == null) {
            throw new CommandException("未找到要合并的分支或提交：" + name);
        }
        try {
            MergeResult result = git.merge()
                    .include(other)
                    .setSquash(squash)
                    .setMessage("Merge branch '" + name + "'")
                    .call();
            return switch (result.getMergeStatus()) {
                case ALREADY_UP_TO_DATE -> ExecOutput.ok("Already up to date.");
                case FAST_FORWARD -> ExecOutput.ok("Updating, Fast-forward");
                case MERGED -> ExecOutput.ok("Merge made by the 'recursive' strategy.");
                case MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED, FAST_FORWARD_SQUASHED ->
                        ExecOutput.ok("Squash commit -- 改动已暂存但未提交，请用 git commit 完成");
                case CONFLICTING -> ExecOutput.error(
                        "CONFLICT: 自动合并失败于 " + result.getConflicts().keySet()
                                + "\n请编辑冲突文件后 git add，再 git commit 完成合并");
                default -> ExecOutput.error("合并未完成：" + result.getMergeStatus());
            };
        } catch (CheckoutConflictException e) {
            throw new CommandException("本地有未提交改动会被合并覆盖，请先提交或撤销");
        }
    }

    private ExecOutput gitTag(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        if (args.isEmpty()) {
            // 列出标签
            StringBuilder sb = new StringBuilder();
            for (Ref t : git.tagList().call()) {
                sb.append(Repository.shortenRefName(t.getName())).append('\n');
            }
            return ExecOutput.ok(sb.toString().stripTrailing());
        }
        if ("-d".equals(args.get(0))) {
            if (args.size() < 2) {
                throw new CommandException("用法：git tag -d <标签名>");
            }
            git.tagDelete().setTags(args.get(1)).call();
            return ExecOutput.ok("Deleted tag " + args.get(1));
        }

        boolean annotated = args.contains("-a");
        String message = extractMessage(args);
        List<String> positional = positionalArgs(args);
        if (positional.isEmpty()) {
            throw new CommandException("请提供标签名，例如：git tag v1.0");
        }
        String name = positional.get(0);
        String targetRef = positional.size() > 1 ? positional.get(1) : Constants.HEAD;
        ObjectId oid = repo.resolve(targetRef);
        if (oid == null) {
            throw new CommandException("未找到要打标签的提交：" + targetRef);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevObject target = walk.parseAny(oid);
            var tagCmd = git.tag().setName(name).setObjectId(target).setAnnotated(annotated);
            if (annotated) {
                tagCmd.setMessage(message != null ? message : name)
                        .setTagger(new PersonIdent(AUTHOR_NAME, AUTHOR_EMAIL));
            }
            tagCmd.call();
            return ExecOutput.ok("");
        } catch (RefAlreadyExistsException e) {
            throw new CommandException("标签已存在：" + name);
        } catch (InvalidRefNameException e) {
            throw new CommandException("非法标签名：" + name);
        }
    }

    private ExecOutput gitRebase(Git git, List<String> args) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        if (args.contains("--abort")) {
            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
            return ExecOutput.ok("rebase 已中止，回到原分支");
        }
        if (args.contains("--continue")) {
            return rebaseOutcome(git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call());
        }
        if (args.contains("--skip")) {
            return rebaseOutcome(git.rebase().setOperation(RebaseCommand.Operation.SKIP).call());
        }
        String upstream = firstNonFlag(args);
        if (upstream == null) {
            throw new CommandException("用法：git rebase <上游分支>");
        }
        if (repo.resolve(Constants.HEAD) == null) {
            throw new CommandException("尚无提交，无法 rebase");
        }
        if (repo.resolve(upstream) == null) {
            throw new CommandException("未找到上游分支或提交：" + upstream);
        }
        return rebaseOutcome(git.rebase().setUpstream(upstream).call());
    }

    private ExecOutput rebaseOutcome(RebaseResult result) {
        return switch (result.getStatus()) {
            case OK, FAST_FORWARD, UP_TO_DATE -> ExecOutput.ok("Successfully rebased.");
            case ABORTED -> ExecOutput.ok("rebase 已中止");
            case NOTHING_TO_COMMIT -> ExecOutput.error(
                    "当前改动为空，git rebase --skip 跳过，或 git rebase --abort 放弃");
            case STOPPED, CONFLICTS, STASH_APPLY_CONFLICTS -> ExecOutput.error(
                    "CONFLICT: rebase 停在冲突处" + conflictHint(result)
                            + "\n解决后 git add，再 git rebase --continue（或 git rebase --abort 放弃）");
            default -> ExecOutput.error("rebase 未完成：" + result.getStatus());
        };
    }

    private String conflictHint(RebaseResult result) {
        List<String> conflicts = result.getConflicts();
        return conflicts == null || conflicts.isEmpty() ? "" : "，涉及 " + conflicts;
    }

    // ---- helpers (touch / echo) -------------------------------------------

    private ExecOutput runTouch(SandboxRepo sandbox, List<String> args) {
        if (args.isEmpty()) {
            throw new CommandException("touch 需要文件名，例如：touch a.txt");
        }
        for (String a : args) {
            Path p = pathGuard.resolveInside(sandbox.root(), a);
            try {
                Files.createDirectories(p.getParent());
                if (!Files.exists(p)) {
                    Files.createFile(p);
                }
            } catch (IOException e) {
                throw new CommandException("无法创建文件：" + a);
            }
        }
        return ExecOutput.ok("");
    }

    private ExecOutput runEcho(SandboxRepo sandbox, List<String> args) {
        int redirect = indexOfRedirect(args);
        if (redirect < 0) {
            return ExecOutput.ok(String.join(" ", args));
        }
        boolean append = ">>".equals(args.get(redirect));
        String text = String.join(" ", args.subList(0, redirect));
        if (redirect + 1 >= args.size()) {
            throw new CommandException("echo 重定向缺少目标文件");
        }
        if (redirect + 2 < args.size()) {
            throw new CommandException("echo 重定向后只能跟一个文件名");
        }
        String target = args.get(redirect + 1);
        Path p = pathGuard.resolveInside(sandbox.root(), target);
        String content = text + System.lineSeparator();
        try {
            Files.createDirectories(p.getParent());
            if (append) {
                Files.writeString(p, content, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(p, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new CommandException("无法写入文件：" + target);
        }
        return ExecOutput.ok("");
    }

    // ---- utils -------------------------------------------------------------

    /** 第一个非选项参数（不以 - 开头），用于取分支/上游名。 */
    private String firstNonFlag(List<String> args) {
        for (String a : args) {
            if (!a.startsWith("-")) {
                return a;
            }
        }
        return null;
    }

    /** 位置参数：剔除选项与 -m 的取值（用于 tag 的 名字/目标）。 */
    private List<String> positionalArgs(List<String> args) {
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("-m".equals(a) || "--message".equals(a)) {
                i++; // 跳过其取值
            } else if (!a.startsWith("-")) {
                positional.add(a);
            }
        }
        return positional;
    }

    private int indexOfRedirect(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (">".equals(a) || ">>".equals(a)) {
                return i;
            }
        }
        return -1;
    }

    private String extractMessage(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("-m".equals(a) || "--message".equals(a)) {
                if (i + 1 >= args.size()) {
                    throw new CommandException("-m 后缺少提交信息");
                }
                return args.get(i + 1);
            }
        }
        return null;
    }

    private void appendEach(StringBuilder sb, String prefix, Set<String> files) {
        for (String f : new TreeSet<>(files)) {
            sb.append(prefix).append(f).append('\n');
        }
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private String shortId(String fullSha) {
        return fullSha.length() <= 7 ? fullSha : fullSha.substring(0, 7);
    }
}
