package org.xiaoyu.gitarena.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.xiaoyu.gitarena.security.CommandException;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 邮箱验证码（P1 注册的邮箱验证路径）。验证码存 Redis 带 5 分钟过期；通过已配 SMTP 发信。
 *
 * <p><b>Dev 兜底</b>：未配 SMTP 凭据（spring.mail.username 为空）或发信失败时，把验证码 WARN 到日志，
 * 保证无邮件服务的开发环境也能走通"两者都要"里的邮箱注册路径。生产配好凭据后即真实发信。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final String PREFIX = "auth:code:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final org.springframework.core.env.Environment env;

    public void sendCode(String email) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(PREFIX + email, code, TTL);

        String from = env.getProperty("spring.mail.username", "");
        if (from.isBlank()) {
            log.warn("[DEV] 未配 SMTP，验证码（仅开发）：{} -> {}", email, code);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(email);
            msg.setSubject("git-arena 注册验证码");
            msg.setText("你的验证码是 " + code + "，5 分钟内有效。");
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("[DEV] 邮件发送失败（{}），验证码仅记录日志：{} -> {}", e.getMessage(), email, code);
        }
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
