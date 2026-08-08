package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证相关请求/响应。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SendCode(@NotBlank @Email String email) {}

    /** 注册：两种路径二合一——只填 username+password 直注；带 email+code 则走邮箱验证。 */
    public record Register(
            @NotBlank @Size(min = 2, max = 32) String username,
            @NotBlank @Size(min = 6, max = 64) String password,
            @Email String email,
            String code
    ) {}

    public record Login(@NotBlank String usernameOrEmail, @NotBlank String password) {}

    public record UserView(
            Long id,
            String username,
            String displayName,
            String email,
            boolean guest,
            int totalPoints,
            String avatarColor
    ) {}

    public record AuthResponse(String token, UserView user) {}
}
