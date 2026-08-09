package org.xiaoyu.gitarena.git;

/**
 * 提交身份（author/committer）。登录用户以真实用户名入历史，git log 才能像真实 git
 * 一样回答「谁在什么时候提交了什么」——这也是协作房间里分辨队友提交的依据（CLAUDE.md §1）。
 */
public record CommitIdentity(String name, String email) {

    /** 匿名沙盒的缺省身份（保持既往行为与关卡自证闭环的确定性）。 */
    public static final CommitIdentity PLAYER = new CommitIdentity("player", "player@git-arena.local");

    /**
     * 由登录用户构造身份。邮箱可缺省（游客/未填写）：此时仿真实 git 的 user@hostname
     * 处置，用 {@code <用户名>@git-arena.local} 占位，保证 author 行始终完整。
     */
    public static CommitIdentity of(String username, String email) {
        String name = username == null || username.isBlank() ? PLAYER.name() : username.strip();
        if (email != null && !email.isBlank()) {
            return new CommitIdentity(name, email.strip());
        }
        String local = name.replaceAll("[^A-Za-z0-9._-]", "");
        return new CommitIdentity(name, (local.isEmpty() ? "player" : local) + "@git-arena.local");
    }
}
