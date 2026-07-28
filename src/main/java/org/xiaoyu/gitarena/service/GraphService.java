package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.git.SandboxRepo;

/**
 * 图模型服务：把沙盒仓库读成 GitGraph 快照（§3 GraphService 角色）。
 * <p>service 层通过它间接使用 git/ 包的 JGit 能力（§7 分层规范）。
 */
public interface GraphService {

    GitGraph readGraph(SandboxRepo sandbox);
}
