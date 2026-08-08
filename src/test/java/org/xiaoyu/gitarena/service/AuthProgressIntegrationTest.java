package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.dto.ProgressView;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.TokenStore;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 用户体系落库集成测（AuthService + ProgressService 真连 DB/Redis）。
 * <p>{@code @Transactional} 使每个用例的库写入结束即回滚，不污染开发库（关卡 seed 在上下文启动时已提交，不受影响）；
 * Redis 里的 token 键带 TTL、无需清理。用例内用随机用户名规避与既有数据撞唯一约束。
 */
@SpringBootTest
@Transactional
class AuthProgressIntegrationTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private ProgressService progressService;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private LevelRegistry levelRegistry;

    private static String uniqueName() {
        return "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private AuthDtos.AuthResponse registerLocal(String username) {
        return authService.register(new AuthDtos.Register(username, "secret123", null, null));
    }

    @Test
    void register_issues_token_resolvable_to_new_user() {
        String username = uniqueName();
        AuthDtos.AuthResponse res = registerLocal(username);

        assertThat(res.token()).isNotBlank();
        assertThat(res.user().username()).isEqualTo(username);
        assertThat(res.user().guest()).isFalse();
        // token → userId 落在 Redis 会话里
        assertThat(tokenStore.resolve(res.token())).isEqualTo(res.user().id());
    }

    @Test
    void login_succeeds_with_correct_password_and_rejects_wrong() {
        String username = uniqueName();
        Long id = registerLocal(username).user().id();

        AuthDtos.AuthResponse login = authService.login(new AuthDtos.Login(username, "secret123"));
        assertThat(login.user().id()).isEqualTo(id);

        assertThatThrownBy(() -> authService.login(new AuthDtos.Login(username, "wrong-password")))
                .isInstanceOf(CommandException.class);
    }

    @Test
    void duplicate_username_is_rejected() {
        String username = uniqueName();
        registerLocal(username);
        assertThatThrownBy(() -> registerLocal(username)).isInstanceOf(CommandException.class);
    }

    @Test
    void guest_gets_temporary_account_with_resolvable_token() {
        AuthDtos.AuthResponse guest = authService.guest();
        assertThat(guest.user().guest()).isTrue();
        assertThat(guest.user().username()).startsWith("guest-");
        assertThat(tokenStore.resolve(guest.token())).isEqualTo(guest.user().id());
    }

    @Test
    void validating_a_level_persists_progress_for_logged_in_user() {
        Long userId = registerLocal(uniqueName()).user().id();
        String slug = "first-commit"; // 上下文启动时已 seed（LevelSeeder）
        assertThat(levelRegistry.idOf(slug)).as("关卡应已 seed 进库").isNotNull();

        progressService.record(userId, slug, true);

        List<ProgressView> mine = progressService.myProgress(userId);
        assertThat(mine).anySatisfy(p -> {
            assertThat(p.slug()).isEqualTo(slug);
            assertThat(p.status()).isEqualTo("completed");
            assertThat(p.attempts()).isEqualTo(1);
            assertThat(p.starRating()).isEqualTo(3);
        });
    }

    @Test
    void repeated_attempts_increment_and_first_pass_marks_completed() {
        Long userId = registerLocal(uniqueName()).user().id();
        String slug = "create-branch";

        progressService.record(userId, slug, false); // 第一次未过
        progressService.record(userId, slug, true);   // 第二次通过

        ProgressView view = progressService.myProgress(userId).stream()
                .filter(p -> p.slug().equals(slug)).findFirst().orElseThrow();
        assertThat(view.attempts()).isEqualTo(2);
        assertThat(view.status()).isEqualTo("completed");
        assertThat(view.firstCompletedAt()).isNotNull();
    }

    @Test
    void anonymous_progress_is_not_persisted() {
        progressService.record(null, "first-commit", true);
        assertThat(progressService.myProgress(null)).isEmpty();
    }
}
