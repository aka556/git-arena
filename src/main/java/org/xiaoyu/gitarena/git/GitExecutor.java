package org.xiaoyu.gitarena.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 命令执行引擎（CLAUDE.md §7）。git 操作一律走 JGit 编程式 API，辅助命令 touch/echo 走 Java Files API；
 * <b>全程不经 shell、不用 ProcessBuilder、不拼接用户字符串</b>。所有文件路径经 {@link PathGuard} 限定在沙盒内。
 *
 * <p>M1 覆盖 {@code init/add/commit/log/status} + {@code touch/echo}。
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
        try (Git ignored = Git.init()
                .setDirectory(sandbox.root().toFile())
                .setInitialBranch("main")
                .call()) {
            return ExecOutput.ok(existed
                    ? "Reinitialized existing Git repository in .git/"
                    : "Initialized empty Git repository in .git/ (branch main)");
        } catch (GitAPIException e) {
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
        String message = extractMessage(args);
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
            String branch = git.getRepository().getBranch();
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
