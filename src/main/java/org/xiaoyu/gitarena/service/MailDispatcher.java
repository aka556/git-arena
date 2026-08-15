package org.xiaoyu.gitarena.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 验证码邮件的异步投递（与 CommandLogService 同一 @Async 模式）。
 * 单独成 bean 是因为 @Async 经代理生效，写在 VerificationCodeService 内部自调用会失效。
 *
 * <p>采用 HTML 卡片式模板，仿主流平台风格——明确展示发自 "git-arena"、验证码用途、有效期与安全提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatcher {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redis;

    @Async
    public void sendCode(String from, String to, String code, String cooldownKey) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("git-arena 注册验证码");

            String html = buildVerificationCard(code);

            helper.setText(html, true);
            mailSender.send(mime);
        } catch (Exception e) {
            // 失败释放冷却：用户等不到邮件时可立即重发，而不是被自己那次失败锁一分钟
            redis.delete(cooldownKey);
            log.error("验证码邮件发送失败 {}: {}", to, e.getMessage());
        }
    }

    // ---- HTML 卡片模板 ----

    private static String buildVerificationCard(String code) {
        // 将验证码拆成单个字符，用 flex 格子展示（仿主流平台的数码管 UI）
        String digits = String.join("",
                code.chars()
                        .mapToObj(c -> String.format(
                                "<span style=\"display:inline-block;width:40px;height:48px;line-height:48px;" +
                                        "text-align:center;font-size:28px;font-weight:700;letter-spacing:2px;" +
                                        "background:#f0f5ff;color:#2f80ed;border-radius:8px;margin:0 4px;\">%c</span>",
                                c))
                        .toList()
        );

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
                <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',system-ui,-apple-system,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f6f9;padding:40px 0;">
                    <tr><td align="center">
                      <table width="480" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;">
                        <!-- 头部品牌条 -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#2f80ed,#1a5fc7);padding:28px 36px 20px;text-align:center;">
                            <div style="color:#ffffff;font-size:22px;font-weight:700;letter-spacing:1px;">git-arena</div>
                            <div style="color:rgba(255,255,255,0.75);font-size:13px;margin-top:4px;">看懂 Git · 练会协作</div>
                          </td>
                        </tr>
                        <!-- 正文 -->
                        <tr>
                          <td style="padding:32px 36px 24px;">
                            <h2 style="margin:0 0 6px;font-size:18px;color:#1a202c;font-weight:600;">验证您的邮箱</h2>
                            <p style="margin:0 0 20px;font-size:14px;color:#64748b;line-height:1.6;">
                              您正在注册 <strong style="color:#2f80ed;">git-arena</strong> 账号，请使用以下验证码完成验证：
                            </p>
                            <!-- 验证码数码管区 -->
                            <div style="text-align:center;padding:16px 0 20px;">
                              """ + digits + """
                            </div>
                            <p style="margin:0 0 4px;font-size:13px;color:#94a3b8;text-align:center;">
                              验证码有效期为 <strong style="color:#eb5757;">5 分钟</strong>，请勿泄露给他人。
                            </p>
                            <p style="margin:0 0 4px;font-size:12px;color:#94a3b8;text-align:center;">
                              如非本人操作，请忽略此邮件。
                            </p>
                          </td>
                        </tr>
                        <!-- 底部分隔 -->
                        <tr>
                          <td style="padding:16px 36px;border-top:1px solid #edf2f7;text-align:center;">
                            <div style="font-size:11px;color:#a0afbe;line-height:1.5;">
                              此邮件由系统自动发送，请勿回复<br>
                              git-arena &copy; 2026
                            </div>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """;
    }
}