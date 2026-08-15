package org.xiaoyu.gitarena.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.security.CommandException;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 邮箱验证码（P1 注册的邮箱验证路径）。验证码存 Redis 带 5 分钟过期，经已配 SMTP 发信。
 *
 * <p><b>发送是异步的</b>：SMTP 握手可达数秒，同步发会拖住接口、诱发用户连点重复发送。
 * 接口侧先落码并占用 60s 重发冷却（Redis setIfAbsent，防绕过前端的连点/脚本刷信），随即返回；
 * 发信失败时清掉冷却，让用户可立即重试。未配 SMTP 凭据直接报错——不再有"验证码打日志"的开发兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final String PREFIX = "auth:code:";
    private static final String COOLDOWN_PREFIX = "auth:code-cooldown:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final MailDispatcher mailDispatcher;
    private final org.springframework.core.env.Environment env;

    public void sendCode(String email) {
        String from = env.getProperty("spring.mail.username", "");
        if (from.isBlank()) {
            throw new CommandException("邮件服务未配置，请联系管理员");
        }
        String cooldownKey = COOLDOWN_PREFIX + email;
        Boolean acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", RESEND_COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new CommandException("发送太频繁，请 1 分钟后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(PREFIX + email, code, TTL);
        mailDispatcher.sendCode(from, email, code, cooldownKey);
    }

    /** 校验并消费验证码（一次性）。 */
    public void verify(String email, String code) {
        String key = PREFIX + email;
        String expected = redis.opsForValue().get(key);
        if (expected == null) {
            throw new CommandException("验证码不存在或已过期，请重新获取");
        }
        if (!expected.equals(code)) {
            throw new CommandException("验证码错误");
        }
        redis.delete(key);
    }
}
