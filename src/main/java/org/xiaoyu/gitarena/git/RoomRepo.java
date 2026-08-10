package org.xiaoyu.gitarena.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.dto.PrDiff;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 房间共享仓库的 JGit 操作（M3 阶段B，git/ 包唯一碰 JGit 处，§7）：
 * 建裸 origin、为成员克隆本地沙盒、以及在裸仓库上做 PR 的<b>无工作区（in-core）合并</b>。
 */
@Component
public class RoomRepo {

    private static final String BOT = "arena";
    private static final long BASE_EPOCH = 1_700_000_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** {@code @@ -a,b +c,d @@}——只取两侧起始行号，长度由逐行推进得出。 */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /** PR 合并结果。 */
    public enum MergeOutcome { MERGED, ALREADY_UP_TO_DATE, CONFLICT }

    /** 建立房间的裸 origin，并预置 main 上一个 base 提交（成员克隆后即可分支/提交）。 */
    public void createRoomOrigin(Path bareDir) {
        createRoomOrigin(bareDir, true);
    }

    /**
     * 建立房间的裸 origin。{@code seedBase=false} 时只建空裸仓库不写 base 提交——
     * 供场景关卡房间使用：初始图由 {@code LevelBuilder.buildBare} 按关卡 spec 物化。
     */
    public void createRoomOrigin(Path bareDir, boolean seedBase) {
        try (Git ignored = Git.init().setBare(true).setDirectory(bareDir.toFile())
                .setInitialBranch("main").call()) {
            // 建仓即可，下面用 in-core 写入 base 提交
        } catch (GitAPIException e) {
            throw new CommandException("创建房间仓库失败：" + e.getMessage());
        }
        if (!seedBase) {
            return;
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

    // ---- PR 评审：差异与文件读取（database.md §4.5） ----

    /** 分支当前 HEAD 的完整 sha。写行级评论时定格为 {@code anchor_commit_sha}（不可变历史标记，§1 例外）。 */
    public String resolveBranchSha(Path bareOrigin, String branch) {
        try (Repository repo = openBare(bareOrigin)) {
            ObjectId id = repo.resolve(Constants.R_HEADS + branch);
            return id == null ? null : id.name();
        } catch (IOException e) {
            throw new CommandException("读取分支失败：" + e.getMessage());
        }
    }

    /**
     * PR 三点差异：{@code merge-base(target, source)} → source HEAD。
     * 用 merge-base 而非 target HEAD 作基线，才不会把目标分支上别人的新提交混进本 PR 的改动。
     */
    public PrDiff diff(Path bareOrigin, String sourceBranch, String targetBranch) {
        try (Repository repo = openBare(bareOrigin)) {
            ObjectId srcId = repo.resolve(Constants.R_HEADS + sourceBranch);
            ObjectId tgtId = repo.resolve(Constants.R_HEADS + targetBranch);
            if (srcId == null) {
                throw new CommandException("源分支不存在：" + sourceBranch);
            }
            if (tgtId == null) {
                throw new CommandException("目标分支不存在：" + targetBranch);
            }
            RevCommit base;
            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(srcId));
                walk.markStart(walk.parseCommit(tgtId));
                base = walk.next();
            }
            List<PrDiff.FileDiff> files = new ArrayList<>();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                formatter.setDetectRenames(true);
                List<DiffEntry> entries = formatter.scan(
                        treeIterator(repo, base == null ? null : base.getId()), treeIterator(repo, srcId));
                for (DiffEntry entry : entries) {
                    out.reset();
                    formatter.format(entry);
                    files.add(parseFileDiff(entry, out.toString(StandardCharsets.UTF_8)));
                }
            }
            return new PrDiff(base == null ? null : base.getId().name(), srcId.name(), files);
        } catch (IOException e) {
            throw new CommandException("计算 PR 差异失败：" + e.getMessage());
        }
    }

    /** 读某个提交下某文件的全部行；文件不存在（被删/改名）返回 null，供锚点重算判定"已过时"。 */
    public List<String> readFileLines(Path bareOrigin, String sha, String path) {
        if (sha == null || path == null) {
            return null;
        }
        try (Repository repo = openBare(bareOrigin); RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
                if (tw == null) {
                    return null;
                }
                byte[] bytes = repo.open(tw.getObjectId(0)).getBytes();
                if (RawText.isBinary(bytes)) {
                    return null;
                }
                return List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
            }
        } catch (IOException | IllegalArgumentException e) {
            // sha 已被 gc/改写，或对象读不出：当作无法定位，交由调用方标记 outdated
            return null;
        }
    }

    private AbstractTreeIterator treeIterator(Repository repo, ObjectId commitId) throws IOException {
        if (commitId == null) {
            return new EmptyTreeIterator(); // 无共同祖先（孤儿分支）：整棵新树都算新增
        }
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, walk.parseCommit(commitId).getTree());
            return parser;
        }
    }

    /**
     * 把 JGit 输出的 unified diff 文本解析成带双侧行号的结构。
     * 行号由 {@code @@} 头的起点推进——这正是行级评论锚点所依赖的坐标。
     */
    private static PrDiff.FileDiff parseFileDiff(DiffEntry entry, String unified) {
        List<PrDiff.DiffLine> lines = new ArrayList<>();
        boolean binary = false;
        boolean inHunk = false;
        int oldLine = 0;
        int newLine = 0;
        for (String raw : unified.split("\n", -1)) {
            if (raw.startsWith("@@")) {
                Matcher matcher = HUNK_HEADER.matcher(raw);
                if (matcher.find()) {
                    oldLine = Integer.parseInt(matcher.group(1));
                    newLine = Integer.parseInt(matcher.group(2));
                    inHunk = true;
                    lines.add(new PrDiff.DiffLine(PrDiff.DiffLine.KIND_HUNK, null, null, raw));
                }
                continue;
            }
            if (!inHunk) {
                if (raw.startsWith("Binary files") || raw.startsWith("GIT binary patch")) {
                    binary = true;
                }
                continue; // diff --git / index / --- / +++ 头部不进结构
            }
            if (raw.isEmpty()) {
                continue;
            }
            String content = raw.substring(1);
            switch (raw.charAt(0)) {
                case '+' -> lines.add(new PrDiff.DiffLine(PrDiff.DiffLine.KIND_ADD, null, newLine++, content));
                case '-' -> lines.add(new PrDiff.DiffLine(PrDiff.DiffLine.KIND_DEL, oldLine++, null, content));
                case ' ' -> lines.add(new PrDiff.DiffLine(
                        PrDiff.DiffLine.KIND_CONTEXT, oldLine++, newLine++, content));
                default -> { } // "\ No newline at end of file" 等元信息行
            }
        }
        String oldPath = DiffEntry.DEV_NULL.equals(entry.getOldPath()) ? null : entry.getOldPath();
        String path = DiffEntry.DEV_NULL.equals(entry.getNewPath()) ? entry.getOldPath() : entry.getNewPath();
        return new PrDiff.FileDiff(path, oldPath, entry.getChangeType().name(), binary, lines);
    }
}
