package org.xiaoyu.gitarena.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 仓库 → GitGraph 只读快照（CLAUDE.md §5）。这是"仓库→图模型"的唯一实现，JGit 只在此包内使用（§7）。
 *
 * <p>快照实时从文件系统的沙盒仓库读出，不缓存、不持久化（database.md §1）。commit 短 hash 对外展示，
 * 同时附稳定序号 {@code C1..Cn}（最老为 C1）便于教学对照——布局层以 commit id 为 key（§6.3）。
 */
@Slf4j
@Component
public class GraphMapper {

    public GitGraph map(SandboxRepo sandbox) {
        if (!sandbox.isInitialized()) {
            return emptyGraph();
        }
        try (Git git = Git.open(sandbox.root().toFile())) {
            Repository repo = git.getRepository();

            List<Ref> heads = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
            List<Ref> tags = repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS);

            List<GitGraph.CommitNode> commits = readCommits(repo, heads, tags);
            List<GitGraph.BranchRef> branches = readBranches(heads);
            List<GitGraph.TagRef> tagRefs = readTags(tags);
            GitGraph.HeadRef head = readHead(repo);
            GitGraph.WorkingDir workingDir = readWorkingDir(git);

            return new GitGraph(
                    GitGraph.CONTRACT_VERSION,
                    commits,
                    branches,
                    tagRefs,
                    head,
                    List.of(), // M1 无远程
                    workingDir
            );
        } catch (IOException e) {
            log.warn("failed to read repo {}: {}", sandbox.sessionId(), e.getMessage());
            throw new CommandException("读取仓库状态失败");
        } catch (GitAPIException e) {
            throw new CommandException("读取工作区状态失败：" + e.getMessage());
        }
    }

    private List<GitGraph.CommitNode> readCommits(Repository repo, List<Ref> heads, List<Ref> tags) throws IOException {
        List<RevCommit> ordered = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            for (Ref ref : concat(heads, tags)) {
                ObjectId id = ref.getObjectId();
                if (id != null) {
                    try {
                        walk.markStart(walk.parseCommit(id));
                    } catch (IOException ignore) {
                        // 非 commit 对象（如指向 tree 的怪异 ref），跳过
                    }
                }
            }
            for (RevCommit c : walk) {
                ordered.add(c);
            }
        }

        // seq：最老为 C1（ordered 为最新在前，故反向编号）
        Map<String, String> seqById = new HashMap<>();
        int seq = 1;
        for (int i = ordered.size() - 1; i >= 0; i--, seq++) {
            seqById.put(ordered.get(i).getName(), "C" + seq);
        }

        List<GitGraph.CommitNode> nodes = new ArrayList<>(ordered.size());
        for (RevCommit c : ordered) {
            List<String> parents = new ArrayList<>(c.getParentCount());
            for (RevCommit p : c.getParents()) {
                parents.add(shortId(p.getName()));
            }
            nodes.add(new GitGraph.CommitNode(
                    shortId(c.getName()),
                    parents,
                    c.getShortMessage(),
                    c.getAuthorIdent().getName(),
                    c.getCommitTime(),
                    seqById.get(c.getName())
            ));
        }
        return nodes;
    }

    private List<GitGraph.BranchRef> readBranches(List<Ref> heads) {
        List<GitGraph.BranchRef> branches = new ArrayList<>(heads.size());
        for (Ref ref : heads) {
            ObjectId id = ref.getObjectId();
            branches.add(new GitGraph.BranchRef(
                    Repository.shortenRefName(ref.getName()),
                    id == null ? null : shortId(id.getName()),
                    false
            ));
        }
        return branches;
    }

    private List<GitGraph.TagRef> readTags(List<Ref> tags) {
        List<GitGraph.TagRef> result = new ArrayList<>(tags.size());
        for (Ref ref : tags) {
            ObjectId id = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
            result.add(new GitGraph.TagRef(
                    Repository.shortenRefName(ref.getName()),
                    id == null ? null : shortId(id.getName())
            ));
        }
        return result;
    }

    private GitGraph.HeadRef readHead(Repository repo) throws IOException {
        Ref head = repo.exactRef(Constants.HEAD);
        if (head == null) {
            return new GitGraph.HeadRef("branch", "main");
        }
        if (head.isSymbolic()) {
            // 已出生或未出生的分支，都以分支名呈现
            return new GitGraph.HeadRef("branch", Repository.shortenRefName(head.getTarget().getName()));
        }
        ObjectId id = head.getObjectId();
        if (id == null) {
            return new GitGraph.HeadRef("branch", "main");
        }
        return new GitGraph.HeadRef("detached", shortId(id.getName()));
    }

    private GitGraph.WorkingDir readWorkingDir(Git git) throws GitAPIException {
        Status s = git.status().call();
        List<String> staged = new ArrayList<>();
        staged.addAll(s.getAdded());
        staged.addAll(s.getChanged());
        staged.addAll(s.getRemoved());
        List<String> modified = new ArrayList<>();
        modified.addAll(s.getModified());
        modified.addAll(s.getMissing());
        List<String> untracked = new ArrayList<>(s.getUntracked());
        return new GitGraph.WorkingDir(sorted(staged), sorted(modified), sorted(untracked));
    }

    private GitGraph emptyGraph() {
        return new GitGraph(
                GitGraph.CONTRACT_VERSION,
                List.of(),
                List.of(),
                List.of(),
                new GitGraph.HeadRef("branch", "main"),
                List.of(),
                new GitGraph.WorkingDir(List.of(), List.of(), List.of())
        );
    }

    private List<Ref> concat(List<Ref> a, List<Ref> b) {
        List<Ref> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    private List<String> sorted(List<String> in) {
        return new ArrayList<>(new TreeSet<>(in));
    }

    private String shortId(String fullSha) {
        return fullSha.length() <= 7 ? fullSha : fullSha.substring(0, 7);
    }
}
