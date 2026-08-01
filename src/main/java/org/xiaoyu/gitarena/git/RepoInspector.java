package org.xiaoyu.gitarena.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 仓库只读检视（git/ 包，JGit 封装）。供关卡断言读取 HEAD 树中的文件内容
 * （fileAtHeadContains / fileAtHeadNotContains，docs/level-spec.md §5.4）。
 */
@Slf4j
@Component
public class RepoInspector {

    /**
     * 读取 HEAD 提交树中指定路径的文件内容。
     *
     * @return 文件内容；HEAD 未出生或路径不存在时为空
     */
    public Optional<String> fileAtHead(SandboxRepo sandbox, String path) {
        if (!sandbox.isInitialized()) {
            return Optional.empty();
        }
        try (Git git = Git.open(sandbox.root().toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(head);
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
                    if (treeWalk == null) {
                        return Optional.empty();
                    }
                    byte[] data = repo.open(treeWalk.getObjectId(0)).getBytes();
                    return Optional.of(new String(data, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.warn("fileAtHead failed for {} path {}: {}", sandbox.sessionId(), path, e.getMessage());
            return Optional.empty();
        }
    }
}
