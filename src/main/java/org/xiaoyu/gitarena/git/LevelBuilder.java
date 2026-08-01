package org.xiaoyu.gitarena.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 关卡初始仓库构建器（docs/level-spec.md §4）。把 InitialSpec 确定性地建成一个真实沙盒仓库：
 * 直接用 {@link ObjectInserter} 造 blob/tree/commit（支持任意 DAG，含多父），因此不依赖工作区回放，
 * merge 结构也能构造。JGit 只在本包内使用（CLAUDE.md §7）。
 *
 * <p><b>确定性</b>：作者缺省 {@code arena}、committer 恒为 {@code arena}，时间戳 {@code 1700000000+60*下标}、
 * 时区 UTC —— 同一 spec 二次构建产出相同对象与相同 hash（§4.1）。hash 不落库（database.md §1）。
 */
@Component
public class LevelBuilder {

    private static final String DEFAULT_AUTHOR = "arena";
    private static final String COMMITTER = "arena";
    private static final long BASE_EPOCH = 1_700_000_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final PathGuard pathGuard;

    public LevelBuilder(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /**
     * 在（空的）沙盒目录上构建 InitialSpec 描述的仓库。
     *
     * @throws CommandException 构建失败（spec 已由 LevelValidator 校验，此处失败视为引擎/环境错误）
     */
    public void build(LevelFile.InitialSpec initial, SandboxRepo sandbox) {
        String initialBranch = initial.head() != null && "branch".equals(initial.head().type())
                ? initial.head().ref() : "main";
        try (Git git = Git.init()
                .setDirectory(sandbox.root().toFile())
                .setInitialBranch(initialBranch)
                .call()) {
            Repository repo = git.getRepository();
            writeIdentityConfig(repo);

            List<LevelFile.Commit> commits = nullToEmpty(initial.commits());
            if (!commits.isEmpty()) {
                Map<String, ObjectId> seqToOid = insertCommits(repo, commits);
                writeRefs(repo, initial, seqToOid);
                populateWorktree(git);
            }
            applyWorkingDir(git, sandbox, initial.workingDir());
        } catch (GitAPIException | IOException e) {
            throw new CommandException("关卡初始仓库构建失败：" + e.getMessage());
        }
    }

    /** 逐个（数组序=拓扑序）造提交，返回 seq → 提交 ObjectId。 */
    private Map<String, ObjectId> insertCommits(Repository repo, List<LevelFile.Commit> commits) throws IOException {
        Map<String, ObjectId> seqToOid = new LinkedHashMap<>();
        // seq → 该提交的累积文件快照（供子提交继承首父）
        Map<String, Map<String, String>> seqToFiles = new LinkedHashMap<>();

        try (ObjectInserter inserter = repo.newObjectInserter()) {
            int index = 0;
            for (LevelFile.Commit c : commits) {
                List<String> parents = nullToEmpty(c.parents());
                // 文件集：继承首父后应用本提交的 files（整文件快照，null=删除）
                Map<String, String> files = new LinkedHashMap<>();
                if (!parents.isEmpty()) {
                    files.putAll(seqToFiles.getOrDefault(parents.get(0), Map.of()));
                }
                if (c.files() != null) {
                    c.files().forEach((path, content) -> {
                        if (content == null) {
                            files.remove(path);
                        } else {
                            files.put(path, content);
                        }
                    });
                }

                ObjectId tree = buildTree(inserter, files);
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(tree);
                List<ObjectId> parentOids = new ArrayList<>();
                for (String p : parents) {
                    ObjectId oid = seqToOid.get(p);
                    if (oid == null) {
                        throw new IOException("父提交未先构建（拓扑序错误）：" + p);
                    }
                    parentOids.add(oid);
                }
                cb.setParentIds(parentOids);

                Date when = new Date((BASE_EPOCH + 60L * index) * 1000L);
                String authorName = c.author() != null ? c.author() : DEFAULT_AUTHOR;
                cb.setAuthor(new PersonIdent(authorName, authorName + "@git-arena.local", when, UTC));
                cb.setCommitter(new PersonIdent(COMMITTER, COMMITTER + "@git-arena.local", when, UTC));
                cb.setMessage(c.message() != null ? c.message() : "commit " + c.seq());

                ObjectId commitId = inserter.insert(cb);
                seqToOid.put(c.seq(), commitId);
                seqToFiles.put(c.seq(), files);
                index++;
            }
            inserter.flush();
        }
        return seqToOid;
    }

    /** 用 in-core DirCache 从 path→content 建树（自动处理嵌套目录）。 */
    private ObjectId buildTree(ObjectInserter inserter, Map<String, String> files) throws IOException {
        DirCache dirCache = DirCache.newInCore();
        DirCacheBuilder builder = dirCache.builder();
        for (Map.Entry<String, String> e : files.entrySet()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, e.getValue().getBytes(StandardCharsets.UTF_8));
            DirCacheEntry entry = new DirCacheEntry(e.getKey());
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(blob);
            builder.add(entry);
        }
        builder.finish();
        return dirCache.writeTree(inserter);
    }

    private void writeRefs(Repository repo, LevelFile.InitialSpec initial, Map<String, ObjectId> seqToOid) throws IOException {
        for (LevelFile.Ref b : nullToEmpty(initial.branches())) {
            updateRef(repo, Constants.R_HEADS + b.name(), seqToOid.get(b.target()));
        }
        for (LevelFile.Ref t : nullToEmpty(initial.tags())) {
            updateRef(repo, Constants.R_TAGS + t.name(), seqToOid.get(t.target()));
        }
        LevelFile.Head head = initial.head();
        if (head == null || "branch".equals(head.type())) {
            String branch = head == null ? "main" : head.ref();
            RefUpdate ru = repo.updateRef(Constants.HEAD);
            ru.link(Constants.R_HEADS + branch); // 符号引用
        } else {
            // detached：HEAD 直接指向提交
            ObjectId target = seqToOid.get(head.ref());
            RefUpdate ru = repo.updateRef(Constants.HEAD, true);
            ru.setNewObjectId(target);
            ru.forceUpdate();
        }
    }

    private void updateRef(Repository repo, String refName, ObjectId target) throws IOException {
        if (target == null) {
            throw new IOException("引用目标不存在：" + refName);
        }
        RefUpdate ru = repo.updateRef(refName);
        ru.setNewObjectId(target);
        ru.setForceUpdate(true);
        ru.update();
    }

    /** 把工作区与索引重置到 HEAD（ObjectInserter 造完对象后，工作区仍为空，需填充）。 */
    private void populateWorktree(Git git) throws GitAPIException {
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
    }

    /** 应用 workingDir 构建配方：覆盖写文件（null=删除），再 git add 指定子集（§4.2）。 */
    private void applyWorkingDir(Git git, SandboxRepo sandbox, LevelFile.InitialWorkingDir wd) throws GitAPIException, IOException {
        if (wd == null) {
            return;
        }
        if (wd.files() != null) {
            for (Map.Entry<String, String> e : wd.files().entrySet()) {
                Path p = pathGuard.resolveInside(sandbox.root(), e.getKey());
                if (e.getValue() == null) {
                    Files.deleteIfExists(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                }
            }
        }
        for (String staged : nullToEmpty(wd.staged())) {
            pathGuard.resolveInside(sandbox.root(), staged); // 越权校验
            git.add().addFilepattern(staged).call();
        }
    }

    private void writeIdentityConfig(Repository repo) throws IOException {
        StoredConfig config = repo.getConfig();
        config.setString("user", null, "name", COMMITTER);
        config.setString("user", null, "email", COMMITTER + "@git-arena.local");
        config.save();
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
