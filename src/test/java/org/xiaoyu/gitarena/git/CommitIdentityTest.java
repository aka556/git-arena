package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommitIdentity} 身份构造单测：登录用户名透传、邮箱缺省时的占位规则（「如果有的话」语义）。
 */
class CommitIdentityTest {

    @Test
    void keepsProvidedEmail() {
        CommitIdentity id = CommitIdentity.of("alice", "alice@example.com");

        assertThat(id.name()).isEqualTo("alice");
        assertThat(id.email()).isEqualTo("alice@example.com");
    }

    @Test
    void missingEmailFallsBackToPlaceholder() {
        assertThat(CommitIdentity.of("alice", null).email()).isEqualTo("alice@git-arena.local");
        assertThat(CommitIdentity.of("alice", "  ").email()).isEqualTo("alice@git-arena.local");
    }

    @Test
    void placeholderEmailStripsUnsafeCharacters() {
        CommitIdentity id = CommitIdentity.of("小明 dev", null);

        assertThat(id.name()).isEqualTo("小明 dev"); // author 名原样保留
        assertThat(id.email()).isEqualTo("dev@git-arena.local");
    }

    @Test
    void blankUsernameFallsBackToPlayer() {
        CommitIdentity id = CommitIdentity.of("  ", null);

        assertThat(id.name()).isEqualTo("player");
        assertThat(id.email()).isEqualTo("player@git-arena.local");
    }
}
