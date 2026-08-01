package org.xiaoyu.gitarena.service;

import java.util.Optional;

/**
 * 读取 HEAD 树中某路径文件内容的回调（供 GoalMatcher 求值文件类断言，同时便于单测注入桩）。
 * 生产实现委托 git/ 的 RepoInspector。
 */
@FunctionalInterface
public interface FileAtHead {

    Optional<String> read(String path);
}
