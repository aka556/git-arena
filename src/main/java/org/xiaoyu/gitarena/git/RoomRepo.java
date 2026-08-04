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
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 房间共享仓库的 JGit 操作（M3 阶段B，git/ 包唯一碰 JGit 处，§7）：
 * 建裸 origin、为成员克隆本地沙盒、以及在裸仓库上做 PR 的<b>无工作区（in-core）合并</b>。
 */
@Component
public class RoomRepo {

    private static final String BOT = "arena";
    private static final long BASE_EPOCH = 1_700_000_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** PR 合并结果。 */
    public enum MergeOutcome { MERGED, ALREADY_UP_TO_DATE, CONFLICT }

    /** 建立房间的裸 origin，并预置 main 上一个 base 提交（成员克隆后即可分支/提交）。 */
    public void createRoomOrigin(Path bareDir) {
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile())
                .setInitialBranch("main").call()) {
            // 建仓即可，下面用 in-core 写入 base 提交
        } catch (GitAPIException e) {
            throw new CommandException("创建房间仓库失败：" + e.getMessage());
        }
        try (Repository repo = openBare(bareDir); ObjectInserter inserter = repo.newObjectInserter()) {
            DirCache dc = DirCache.newInCore();
            DirCacheBuilder builder = dc.builder();
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "# shared repo\n".getBytes(StandardCharsets.UTF_8));
            DirCacheEntry entry = new DirCacheEntry("README.md");
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(blob);
            builder.add(entry);
            builder.finish();
            ObjectId tree = dc.writeTree(inserter);

            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(tree);
            PersonIdent ident = new PersonIdent(BOT, BOT + "@git-arena.local", new Date(BASE_EPOCH * 1000L), UTC);
            cb.setAuthor(ident);
            cb.setCommitter(ident);
            cb.setMessage("base");
            ObjectId base = inserter.insert(cb);
            inserter.flush();

            RefUpdate ru = repo.updateRef(Constants.R_HEADS + "main");
            ru.setNewObjectId(base);
            ru.forceUpdate();
        } catch (IOException e) {
            throw new CommandException("初始化房间仓库失败：" + e.getMessage());
        }
    }

    /** 为成员克隆一份本地工作沙盒（origin 指向房间裸仓库），并写入固定身份配置。 */
    public void cloneMember(Path bareOrigin, Path memberDir) {
        String uri = bareOrigin.toAbsolutePath().toString().replace('\\', '/');
        try (Git git = Git.cloneRepository()
                .setURI(uri)
                .setDirectory(memberDir.toFile())
                .setCloneAllBranches(true)
                .call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "player");
            config.setString("user", null, "email", "player@git-arena.local");
            config.save();
        } catch (GitAPIException | IOException e) {
            throw new CommandException("加入房间失败（克隆仓库出错）：" + e.getMessage());
        }
    }

    /** 试合并（不写入），返回是否可干净合并——供 PR 的 mergeable 探测。 */
    public MergeOutcome probeMergeable(Path bareOrigin, String sourceBranch, String targetBranch) {
        try (Repository repo = openBare(bareOrigin)) {
            return computeMerge(repo, sourceBranch, targetBranch, false, null);
        } catch (IOException e) {
            throw new CommandException("探测 PR 可合并性失败：" + e.getMessage());
        }
    }

    /** 在裸 origin 上把 sourceBranch 合并进 targetBranch（in-core，无工作区），成功则推进目标分支引用。 */
    public MergeOutcome mergePullRequest(Path bareOrigin, String sourceBranch, String targetBranch, String message) {
        try (Repository repo = openBare(bareOrigin)) {
            return computeMerge(repo, sourceBranch, targetBranch, true, message);
        } catch (IOException e) {
            throw new CommandException("合并 PR 失败：" + e.getMessage());
        }
    }

    private MergeOutcome computeMerge(Repository repo, String sourceBranch, String targetBranch,
                                      boolean commit, String message) throws IOException {
        ObjectId srcId = repo.resolve(Constants.R_HEADS + sourceBranch);
        ObjectId tgtId = repo.resolve(Constants.R_HEADS + targetBranch);
        if (srcId == null) {
            throw new CommandException("源分支不存在：" + sourceBranch);
        }
        if (tgtId == null) {
            throw new CommandException("目标分支不存在：" + targetBranch);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit src = walk.parseCommit(srcId);
            RevCommit tgt = walk.parseCommit(tgtId);
            if (walk.isMergedInto(src, tgt)) {
                return MergeOutcome.ALREADY_UP_TO_DATE;
            }

            Merger merger = MergeStrategy.RECURSIVE.newMerger(repo, true); // inCore
            boolean clean = merger.merge(tgt, src);
            if (!clean) {
                return MergeOutcome.CONFLICT;
            }
            if (!commit) {
                return MergeOutcome.MERGED;
            }

            ObjectId mergedTree = merger.getResultTreeId();
            try (ObjectInserter inserter = repo.newObjectInserter()) {
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(mergedTree);
                cb.setParentIds(tgt.getId(), src.getId());
                PersonIdent ident = new PersonIdent(BOT, BOT + "@git-arena.local");
                cb.setAuthor(ident);
                cb.setCommitter(ident);
                cb.setMessage(message != null ? message : "Merge branch '" + sourceBranch + "' into " + targetBranch);
                ObjectId mergeCommit = inserter.insert(cb);
                inserter.flush();

                RefUpdate ru = repo.updateRef(Constants.R_HEADS + targetBranch);
                ru.setExpectedOldObjectId(tgtId);
                ru.setNewObjectId(mergeCommit);
                RefUpdate.Result result = ru.update();
                if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.FORCED
                        && result != RefUpdate.Result.NEW && result != RefUpdate.Result.NO_CHANGE) {
                    throw new CommandException("目标分支已被他人更新，请刷新后重试（" + result + "）");
                }
            }
            return MergeOutcome.MERGED;
        }
    }

    private Repository openBare(Path bareDir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(bareDir.toFile()).setBare().build();
    }
}
