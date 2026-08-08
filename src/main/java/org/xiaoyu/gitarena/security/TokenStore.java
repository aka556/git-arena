package org.xiaoyu.gitarena.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录会话 token（Redis，§2 会话）。不透明 token → userId，带滑动过期。
 * 正式用户 7 天、游客 24h（与 users.expires_at 口径一致）。
 */
@Component
@RequiredArgsConstructor
public class TokenStore {

    private static final String PREFIX = "auth:token:";
    private static final Duration TTL_USER = Duration.ofDays(7);
    private static final Duration TTL_GUEST = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public String issue(long userId, boolean guest) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(PREFIX + token, String.valueOf(userId), guest ? TTL_GUEST : TTL_USER);
        return token;
    }

    /** 解析 token → userId，并滑动续期；无效返回 null。 */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = PREFIX + token;
        String userId = redis.opsForValue().get(key);
        if (userId == null) {
            return null;
        }
        redis.expire(key, TTL_USER); // 活跃即续期（游客到期由其账号 expires_at 兜底清理）
        return Long.valueOf(userId);
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(PREFIX + token);
        }
    }
}
